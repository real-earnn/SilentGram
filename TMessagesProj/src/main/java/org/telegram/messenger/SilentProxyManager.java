package org.telegram.messenger;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.utils.proxy.ProxySettings;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SilentGram auto-proxy.
 *
 * Telegram is blocked in Pakistan / India, so the app must connect through an
 * MTProto proxy — but the user should never have to find one. This manager
 * fetches a fresh proxy list from our own API, checks every proxy IN PARALLEL
 * in the background (a fast TCP connect, milliseconds each), and connects to
 * the FASTEST one that answers — lowest latency (ms) first. Candidates are
 * ranked by the API's latency_ms (measured by the API, fastest first); the
 * app's own probe round-trip time is the fallback ranking when the API
 * reports no latency. The rest keep being checked in the background, and
 * if a faster one shows up the app switches to it in milliseconds.
 * Telegram's built-in proxy rotation is also left on, so if the active
 * proxy dies the next one takes over automatically.
 *
 * SilentGram K6: the switch decision uses latencies measured ON THIS
 * PHONE (TCP connect + MTProto handshake time in each probe round), not
 * the API's latency_ms — that number is measured from the API server and
 * says nothing about how fast a proxy is from here. Once connected, a
 * round only decides after EVERY candidate resolved, and switches to the
 * verified-live proxy with the lowest measured latency when it beats the
 * current one by 50ms. The refresh is re-scheduled even when a fetch
 * fails, so one bad fetch can no longer stop all future re-evaluation.
 *
 * SilentGram K7: proxies that fail the TCP probe in a round are removed
 * from the managed list — but only when that same round proves the
 * user's own network is fine (at least 3 proxies live and more live
 * than dead). If most fail at once, nothing is cleared. The active
 * proxy and hand-added proxies are never removed.
 *
 * SilentGram K6b (why batch J's K6 never switched in practice):
 * 1) The startup round connected ASAP to the first verified proxy in
 *    API-latency order and set decisionMade, so the rest of that round
 *    never compared on-device latencies — and the only re-evaluation
 *    was the hourly refresh. A 271ms proxy could sit connected for an
 *    hour with a 4ms proxy available. 2) Telegram's own
 *    ProxyRotationController (enabled by applyProxies) switches away
 *    from the current proxy to the lowest-ping OTHER available proxy
 *    whenever a check completes — with no threshold — and every switch
 *    re-triggers its 10s check timer, so it ping-ponged between
 *    proxies and reconnected again and again, undoing K6's switch.
 *    Rotation is now OFF; the watcher below owns switching.
 * 3) The probe published the raw TCP connect time as info.ping even
 *    when the MTProto handshake failed, so the list showed "Ping 4 ms"
 *    for a proxy the gate (rightly) never picks — display and decision
 *    disagreed. Only handshake-verified latencies are now stored or
 *    shown, and a handshake-failed current proxy counts as dead for
 *    the switch bar. 4) The watcher: while the app is open it
 *    re-probes the fastest candidates every 3 minutes (first round
 *    60s after startup) and switches to the lowest-ms verified proxy
 *    whenever it beats the current one by 50ms; a current proxy that
 *    fails verification in 2 rounds running is marked bad for an hour
 *    and abandoned for the fastest verified one. The K7 guard is
 *    unchanged: dead proxies are only cleared when live >= 3 and
 *    live > dead, so a dead phone network never mass-clears the list.
 *
 * The app itself never talks to the proxy-list sites: if a source changes,
 * only the API (silentgram-proxy.silenthost.site) needs updating — the app
 * keeps working.
 *
 * The proxy settings UI is hidden (see DataSettingsActivity, and the proxy
 * button is removed from the chat-list menu), so the user just sees
 * SilentGram working, as if no proxy were needed at all.
 */
public class SilentProxyManager {

    // Our own API — the single source of proxies for the app.
    // /api/live returns ONLY the live, fastest proxies (pre-checked by the
    // API, sorted by latency) — the app never has to probe hundreds of
    // dead ones. /api/proxies is the full list, used only as a fallback.
    // limit=50 fetches the WHOLE daily live list (currently ~20-26) — the
    // old limit=10 was only the user's rough estimate, not a real cap.
    private static final String API_LIVE_URL = "https://silentgram-proxy.silenthost.site/api/live?limit=50";
    private static final String API_URL = "https://silentgram-proxy.silenthost.site/api/proxies";
    // Refresh the list every 1 hour (the API itself refreshes every 1 hour).
    private static final long REFRESH_INTERVAL_MS = 60 * 60 * 1000L;
    // A proxy must answer a TCP connect within this time to count as live.
    private static final int PROBE_TIMEOUT_MS = 1500;
    // Only switch to a faster proxy if it beats the current one by this much
    // (measured ON-DEVICE latency, see measuredLatencyMs). 50ms: the live
    // list's API latencies span only ~155-347ms, so the old 100ms bar on
    // API numbers almost never fired — the app kept whatever proxy won the
    // first race, even the slowest one. On-device probe latencies differ
    // far more than that, and a 50ms margin still stops flapping between
    // near-equal proxies.
    private static final long SWITCH_FASTER_BY_MS = 50;
    // At most this many proxies are ever added/saved — the full daily live
    // list (limit=50 above), so every live proxy reaches the app and the
    // fastest one can be picked. The /api/proxies fallback is also sorted
    // by latency, so capping it here keeps the 50 fastest of the full list.
    private static final int MAX_PROXIES = 50;
    // How many proxies to probe in parallel (up to 50 candidates now).
    private static final int PROBE_THREADS = 10;
    // SilentGram K6b: the continuous watcher. While the app is open it
    // re-probes the fastest candidates every few minutes and switches to
    // the lowest-ms verified proxy — the hourly fetch alone left the app
    // stuck on a slow proxy for up to an hour (see class Javadoc).
    private static final long WATCHER_FIRST_DELAY_MS = 60 * 1000L;
    private static final long WATCHER_INTERVAL_MS = 3 * 60 * 1000L;
    // Watcher rounds probe only the fastest candidates (API order) — the
    // daily live list is ~20-26 proxies, so 25 covers it while bounding
    // each round to ~2 handshake waves.
    private static final int WATCHER_MAX_CANDIDATES = 25;
    // Watcher rounds in a row the current proxy must fail verification
    // before it is abandoned as unstable (see noteWatcherRound).
    private static final int WATCHER_UNSTABLE_ROUNDS = 2;

    private static volatile boolean started;
    private static volatile boolean fetching;
    private static volatile boolean watcherStarted;
    // One probe round at a time (fetch round vs watcher round).
    private static final AtomicBoolean roundRunning = new AtomicBoolean(false);
    // Consecutive watcher rounds the current proxy failed verification.
    private static volatile int watcherUnstableRounds;

    // Latency (ms) of the currently active proxy, -1 if unknown.
    private static final AtomicLong currentLatencyMs = new AtomicLong(-1);
    // The fastest proxy found in the current round (for background switching).
    private static final AtomicReference<SharedConfig.ProxyInfo> bestInfo = new AtomicReference<>();
    private static final AtomicLong bestLatencyMs = new AtomicLong(Long.MAX_VALUE);
    // Per-proxy latency, keyed by "address:port" — the API's latency_ms
    // (measured by the API) and the app's own probe round-trip time.
    // Ranking prefers the API latency when present, else the probe time.
    private static final ConcurrentHashMap<String, Long> apiLatencyByKey = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> probeLatencyByKey = new ConcurrentHashMap<>();

    private static String proxyKey(SharedConfig.ProxyInfo info) {
        return info.settings.getAddress() + ":" + info.settings.getPort();
    }

    private static String proxyKey(ProxySettings settings) {
        return settings.getAddress() + ":" + settings.getPort();
    }

    /** Latency used for RANKING: API latency_ms if present, else the app's own probe RTT, else "unknown" (sorts last). */
    private static long effectiveLatencyMs(String key) {
        Long api = apiLatencyByKey.get(key);
        if (api != null && api >= 0) {
            return api;
        }
        Long probe = probeLatencyByKey.get(key);
        if (probe != null && probe >= 0) {
            return probe;
        }
        return Long.MAX_VALUE;
    }

    /** This round's VERIFIED on-device latency for a proxy (MTProto handshake time), or -1 if it was not verified live this round. A bare TCP connect time is never stored here — a proxy that accepts TCP but fails the handshake has no usable latency. */
    private static long measuredLatencyMs(String key) {
        Long ms = probeLatencyByKey.get(key);
        return ms != null && ms >= 0 ? ms : -1;
    }

    /**
     * Latency used for the switch decision: the on-device measured latency
     * when present, else the API latency (or -1 if neither is known).
     * The API latency is only a fallback — it is measured from the API
     * server, not from this phone.
     */
    private static long latencyForDecision(String key) {
        long measured = measuredLatencyMs(key);
        return measured >= 0 ? measured : knownLatencyMs(key);
    }

    /** latencyForDecision with "unknown" sorted last (for ranking), so an unknown latency never beats a measured one. */
    private static long latencyForRank(String key) {
        long ms = latencyForDecision(key);
        return ms >= 0 ? ms : Long.MAX_VALUE;
    }

    /** Probe state of the currently active proxy in this round (0 = still probing, 1 = verified, 2 = dead, 3 = TCP-only), or -1 if it is not a candidate this round. */
    private static int currentProxyState(List<SharedConfig.ProxyInfo> ranked, int[] state) {
        SharedConfig.ProxyInfo curProxy = SharedConfig.currentProxy;
        if (curProxy == null) {
            return -1;
        }
        for (int i = 0; i < ranked.size(); i++) {
            if (sameProxy(ranked.get(i), curProxy)) {
                return state[i];
            }
        }
        return -1;
    }

    /**
     * Fresh on-device latency of the currently active proxy, measured in
     * THIS probe round: its handshake-verified probe time if it was
     * verified live, Long.MAX_VALUE if it probed dead OR if its MTProto
     * handshake failed (state 3) — a proxy that accepts TCP but never
     * completes a handshake cannot carry traffic, so its TCP connect
     * time must NOT be used as the bar: any handshake-verified proxy
     * beats it, however slow. The last recorded latency (activeLatency,
     * may be -1 = unknown) is used only if it was not a candidate this
     * round or is still probing.
     */
    private static long currentProxyMeasuredLatency(List<SharedConfig.ProxyInfo> ranked, int[] state, long activeLatency) {
        SharedConfig.ProxyInfo curProxy = SharedConfig.currentProxy;
        if (curProxy == null) {
            return activeLatency;
        }
        for (int i = 0; i < ranked.size(); i++) {
            if (sameProxy(ranked.get(i), curProxy)) {
                if (state[i] == 2 || state[i] == 3) {
                    // Dead, or TCP-only (handshake failed) — any verified
                    // proxy is better than staying on it.
                    return Long.MAX_VALUE;
                }
                if (state[i] == 1) {
                    long ms = measuredLatencyMs(proxyKey(curProxy));
                    if (ms >= 0) {
                        return ms;
                    }
                }
                // Still probing — no fresh number yet.
                return activeLatency;
            }
        }
        return activeLatency;
    }

    /** Best known latency for display/logging: API latency_ms if present, else probe RTT, else -1 (unknown). */
    private static long knownLatencyMs(String key) {
        Long api = apiLatencyByKey.get(key);
        if (api != null && api >= 0) {
            return api;
        }
        Long probe = probeLatencyByKey.get(key);
        if (probe != null && probe >= 0) {
            return probe;
        }
        return -1;
    }

    /** True if the download watchdog blocked this proxy (within the last hour). */
    private static boolean isProxyBlocked(String key) {
        SharedPreferences prefs = ApplicationLoader.applicationContext
                .getSharedPreferences("silentgram", android.content.Context.MODE_PRIVATE);
        long blockedAt = prefs.getLong("proxy_bad_" + key, 0);
        return blockedAt > 0 && System.currentTimeMillis() - blockedAt < BAD_PROXY_BLOCK_MS;
    }

    // Download watchdog: some proxies accept a TCP connect but never relay
    // real data — stickers, emoji and files just never download. The
    // watchdog watches active downloads; if one makes zero progress for
    // 30 seconds on a proxy, that proxy is marked bad and the app switches
    // to the next candidate. Cooldown stops it flapping.
    private static final long WATCHDOG_INTERVAL_MS = 10_000L;
    private static final int WATCHDOG_STALL_CHECKS = 3; // 3 x 10s = 30s
    private static final long WATCHDOG_SWITCH_COOLDOWN_MS = 2 * 60 * 1000L;
    private static final long BAD_PROXY_BLOCK_MS = 60 * 60 * 1000L;
    private static volatile boolean watchdogStarted;
    private static volatile long lastWatchdogSwitchMs;
    private static volatile List<SharedConfig.ProxyInfo> lastCandidates = new ArrayList<>();

    public static void init() {
        if (started) {
            return;
        }
        started = true;
        // App config (verified channels + sponsored row) from the same API.
        SilentConfig.loadCached();
        SilentConfig.refreshAsync();
        // If a proxy was already saved from a previous run, it is applied by
        // ConnectionsManager on startup — just refresh the list in background.
        refreshAsync();
        startDownloadWatchdog();
        startProxyWatcher();
    }

    /** Fetch the list from our API and apply the best proxy. Runs off the UI thread. */
    public static void refreshAsync() {
        if (fetching) {
            return;
        }
        fetching = true;
        new Thread(() -> {
            try {
                // /api/live first (the full daily live list, fastest first,
                // pre-checked by the API). Fall back to the full list,
                // capped at MAX_PROXIES below (it is latency-sorted too,
                // so the cap keeps the fastest ones).
                List<ProxySettings> proxies = fetchFromUrl(API_LIVE_URL);
                if (proxies == null || proxies.isEmpty()) {
                    proxies = fetchFromUrl(API_URL);
                }
                if (proxies != null && !proxies.isEmpty()) {
                    if (proxies.size() > MAX_PROXIES) {
                        proxies = new ArrayList<>(proxies.subList(0, MAX_PROXIES));
                    }
                    applyProxies(proxies);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                fetching = false;
                // SilentGram K6: ALWAYS schedule the next refresh, even
                // when this fetch failed. The old code only scheduled it
                // at the end of applyProxies, so one failed fetch (no
                // network at startup, one API hiccup) silently stopped
                // all future re-evaluation for the whole app session and
                // the app never switched proxies again.
                AndroidUtilities.runOnUIThread(SilentProxyManager::refreshAsync, REFRESH_INTERVAL_MS);
            }
        }, "silent-proxy-fetch").start();
    }

    private static List<ProxySettings> fetchProxyList() throws Exception {
        return fetchFromUrl(API_LIVE_URL);
    }

    private static List<ProxySettings> fetchFromUrl(String apiUrl) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "SilentGram");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("proxies");
            if (arr == null) {
                return null;
            }
            List<ProxySettings> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) {
                    continue;
                }
                String server = p.optString("server", "");
                int port = p.optInt("port", 0);
                String secret = p.optString("secret", "");
                if (server.isEmpty() || port <= 0 || secret.isEmpty()) {
                    continue;
                }
                ProxySettings settings = ProxySettings.builder()
                        .setType(ProxySettings.Type.MTPROTO)
                        .setAddress(server)
                        .setPort(port)
                        .setSecret(secret)
                        .setUser("")
                        .setPassword("")
                        .build();
                if (settings.isValid()) {
                    out.add(settings);
                    // Remember the API's latency_ms for ranking: the app
                    // prefers it over its own probe time (see
                    // effectiveLatencyMs). -1 / missing = unknown.
                    long apiMs = p.optLong("latency_ms", -1);
                    if (apiMs >= 0) {
                        apiLatencyByKey.put(proxyKey(settings), apiMs);
                    }
                }
            }
            // Rank fastest-first by the API latency (unknown sorts last).
            // The API already returns them sorted, but sort explicitly so
            // the order holds even if the API ever changes its ordering.
            Collections.sort(out, new Comparator<ProxySettings>() {
                @Override
                public int compare(ProxySettings a, ProxySettings b) {
                    return Long.compare(effectiveLatencyMs(proxyKey(a)), effectiveLatencyMs(proxyKey(b)));
                }
            });
            return out;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Keep at most MAX_PROXIES managed proxies saved (the fastest live ones
     * from the API). Old managed proxies that are no longer in the top 50
     * are removed, so the list never floods to hundreds and the network
     * never chokes on parallel checks. Proxies the user added by hand are
     * never touched. Then probe the candidates and connect to the
     * fastest live one.
     */
    private static void applyProxies(List<ProxySettings> proxies) {
        SharedConfig.loadProxyList();
        // Keys of the proxies this manager added (so hand-added ones survive).
        SharedPreferences prefs = ApplicationLoader.applicationContext
                .getSharedPreferences("silentgram", android.content.Context.MODE_PRIVATE);
        HashSet<String> managed = new HashSet<>();
        String savedManaged = prefs.getString("managed_proxies", "");
        if (savedManaged != null && !savedManaged.isEmpty()) {
            for (String k : savedManaged.split(",")) {
                if (!k.isEmpty()) managed.add(k);
            }
        }
        HashSet<String> wanted = new HashSet<>();
        for (ProxySettings s : proxies) {
            wanted.add(s.getAddress() + ":" + s.getPort());
        }
        // Remove managed proxies that are no longer in the top 50 (but never
        // the one currently active).
        String activeKey = SharedConfig.currentProxy != null
                ? SharedConfig.currentProxy.settings.getAddress() + ":" + SharedConfig.currentProxy.settings.getPort()
                : null;
        ArrayList<SharedConfig.ProxyInfo> toRemove = new ArrayList<>();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            String key = info.settings.getAddress() + ":" + info.settings.getPort();
            if (managed.contains(key) && !wanted.contains(key) && !key.equals(activeKey)) {
                toRemove.add(info);
            }
        }
        for (SharedConfig.ProxyInfo info : toRemove) {
            SharedConfig.deleteProxy(info);
            managed.remove(info.settings.getAddress() + ":" + info.settings.getPort());
        }
        // Add the new top proxies (managed).
        HashSet<String> seen = new HashSet<>();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            seen.add(info.settings.getAddress() + ":" + info.settings.getPort());
        }
        List<SharedConfig.ProxyInfo> all = new ArrayList<>();
        for (ProxySettings settings : proxies) {
            String key = settings.getAddress() + ":" + settings.getPort();
            if (seen.contains(key)) {
                for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
                    if ((info.settings.getAddress() + ":" + info.settings.getPort()).equals(key)) {
                        all.add(info);
                        break;
                    }
                }
                continue;
            }
            seen.add(key);
            SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(settings);
            SharedConfig.addProxy(info);
            managed.add(key);
            all.add(info);
        }
        // Remember which proxies are managed.
        StringBuilder sb = new StringBuilder();
        for (String k : managed) {
            if (sb.length() > 0) sb.append(',');
            sb.append(k);
        }
        prefs.edit().putString("managed_proxies", sb.toString()).apply();
        if (all.isEmpty()) {
            return;
        }

        // SilentGram K6b: Telegram's own proxy rotation stays OFF.
        // ProxyRotationController.switchToAvailable() switches away from
        // the current proxy to the lowest-ping OTHER available proxy
        // whenever a check completes — unconditionally, with no
        // threshold — and every switch re-triggers its 10s check timer
        // (it fires on ConnectingToProxy, which a switch itself causes),
        // so it ping-ponged between proxies, reconnected again and
        // again, and undid this manager's switch to the fastest proxy.
        // Our setProxySettings posts no proxySettingsChanged
        // notification either, so nothing ever cancelled its pending
        // check. The K6b watcher below owns switching now; rotation
        // would only fight it.
        SharedConfig.proxyRotationEnabled = false;
        SharedPreferences mainPrefs = MessagesController.getGlobalMainSettings();
        mainPrefs.edit().putBoolean("proxyRotationEnabled", false).apply();

        lastCandidates = new ArrayList<>(all);
        probeAndConnect(all, false);
    }

    /**
     * Probe every proxy in parallel (fast TCP connect) and connect to the
     * FASTEST live one — lowest latency first, never just the first probe
     * that happens to finish.
     *
     * How: candidates are sorted fastest-first by effective latency (the
     * API's latency_ms when present, else the app's own probe time), and
     * each probe records its result in that same order. As soon as the
     * fastest still-undecided candidate is known to be live, the gate
     * connects to it — so the user still gets connected in milliseconds
     * (the fastest proxy usually answers first anyway) while slower
     * candidates can no longer win the race by finishing first. A dead
     * candidate simply falls through to the next-fastest, and a proxy the
     * download watchdog blocked is skipped the same way.
     */
    private static void probeAndConnect(List<SharedConfig.ProxyInfo> candidates, boolean watcherRound) {
        // One probe round at a time: the hourly fetch round and the
        // watcher round share the latency maps and the gate, so a second
        // round starting mid-round would corrupt both decisions. The
        // skipped round is not lost — the watcher runs again in a few
        // minutes and the fetch re-schedules hourly.
        if (!roundRunning.compareAndSet(false, true)) {
            FileLog.d("SilentProxy: probe round already running — skipping this " + (watcherRound ? "watcher" : "fetch") + " round");
            return;
        }
        bestInfo.set(null);
        bestLatencyMs.set(Long.MAX_VALUE);
        // Fastest-first by effective latency (unknown sorts last). The
        // connect gate below walks this order, so the winner is always the
        // lowest-latency LIVE proxy.
        final List<SharedConfig.ProxyInfo> ranked = new ArrayList<>(candidates);
        // SilentGram K6: always probe the CURRENT proxy too, even if it
        // dropped out of this fetch's list — otherwise its latency is
        // never re-measured and a stale low number keeps a dead or slow
        // proxy active forever.
        SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
        if (current != null) {
            boolean currentIncluded = false;
            for (SharedConfig.ProxyInfo info : ranked) {
                if (sameProxy(info, current)) {
                    currentIncluded = true;
                    break;
                }
            }
            if (!currentIncluded) {
                ranked.add(current);
            }
        }
        Collections.sort(ranked, new Comparator<SharedConfig.ProxyInfo>() {
            @Override
            public int compare(SharedConfig.ProxyInfo a, SharedConfig.ProxyInfo b) {
                return Long.compare(effectiveLatencyMs(proxyKey(a)), effectiveLatencyMs(proxyKey(b)));
            }
        });
        final int n = ranked.size();
        // Probe outcome per slot, in ranked order: 0 = pending, 1 = live
        // (verified by a real MTProto handshake), 2 = dead/failed,
        // 3 = live by TCP only (handshake failed/timed out, fallback only).
        final int[] state = new int[n];
        final Object gateLock = new Object();
        final AtomicBoolean decisionMade = new AtomicBoolean(false);
        final AtomicBoolean connectedOnce = new AtomicBoolean(SharedConfig.currentProxy != null && SharedConfig.isProxyEnabled());
        // If we already have an active proxy, remember its latency as the bar
        // a challenger must beat (unknown -> the round's fastest wins).
        final long activeLatency = currentLatencyMs.get();
        CountDownLatch done = new CountDownLatch(n);
        ExecutorService pool = Executors.newFixedThreadPool(PROBE_THREADS);
        for (int i = 0; i < n; i++) {
            final int index = i;
            final SharedConfig.ProxyInfo info = ranked.get(i);
            pool.execute(() -> {
                try {
                    String key = proxyKey(info);
                    long tcpMs = probeLatency(info.settings.getAddress(), info.settings.getPort());
                    long ms = tcpMs;
                    boolean verified = false;
                    if (tcpMs >= 0) {
                        // SilentGram I6: a bare TCP connect only proves the
                        // proxy accepts connections — some proxies do that
                        // and then never deliver MTProto data (premium emoji
                        // and files just never arrive). Verify with a real
                        // MTProto handshake before trusting the proxy.
                        long checkMs = verifyProxyWithHandshake(info.settings);
                        if (checkMs == -2) {
                            // No account signed in yet — nothing to check
                            // against; trust the TCP probe (as before).
                            verified = true;
                        } else if (checkMs >= 0) {
                            verified = true;
                            ms = checkMs;
                        }
                    }
                    synchronized (gateLock) {
                        if (tcpMs >= 0 && verified) {
                            // Verified by a real MTProto handshake: this is
                            // a usable on-device latency — record it, and
                            // publish it as the proxy's ping so the list UI
                            // ("Available, Ping X ms") shows the same number
                            // the switch decision uses.
                            probeLatencyByKey.put(key, ms);
                            info.ping = ms;
                            info.available = true;
                            state[index] = 1;
                            // Track the fastest VERIFIED proxy (log only).
                            while (true) {
                                long best = bestLatencyMs.get();
                                if (ms >= best) {
                                    break;
                                }
                                if (bestLatencyMs.compareAndSet(best, ms)) {
                                    bestInfo.set(info);
                                    break;
                                }
                            }
                        } else if (tcpMs >= 0) {
                            // TCP connects but the MTProto handshake failed
                            // or timed out (state 3). The TCP time is NOT a
                            // usable latency — never store it as one, and
                            // never show it as the proxy's ping: the list
                            // would claim "Ping 4 ms" for a proxy the gate
                            // will never pick. Same convention as
                            // ProxyListActivity (handshake fail -> ping 0,
                            // unavailable).
                            probeLatencyByKey.put(key, -1L);
                            info.ping = 0;
                            info.available = false;
                            state[index] = 3;
                        } else {
                            // SilentGram K6: record the failure so a stale
                            // probe time from an earlier round is never
                            // mistaken for a fresh measurement.
                            probeLatencyByKey.put(key, -1L);
                            info.available = false;
                            state[index] = 2;
                        }
                    }
                    tryConnectFastest(ranked, state, gateLock, decisionMade, connectedOnce, activeLatency);
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    done.countDown();
                }
            });
        }
        pool.shutdown();
        // Wait for all probes in the background so we can log the outcome
        // and clean up dead proxies; the user is NOT blocked on this — the
        // fastest live proxy already connected through the gate above.
        // Await long enough for every wave (up to 50 candidates, 10
        // threads, ~8s handshake check each).
        new Thread(() -> {
            try {
                try {
                    done.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                // Safety net: if the last probe's gate call was missed, decide
                // now that every probe resolved.
                tryConnectFastest(ranked, state, gateLock, decisionMade, connectedOnce, activeLatency);
                // SilentGram K7: drop proxies that are dead this round — only
                // when the round proves the user's own network is fine.
                cleanupDeadProxies(ranked, state);
                // SilentGram K6b: a watcher round reports back so an
                // unstable current proxy can be abandoned (see
                // noteWatcherRound). Fetch rounds do not need this — the
                // gate already switched them when a verified proxy won.
                if (watcherRound) {
                    noteWatcherRound(ranked, state);
                }
                SharedConfig.ProxyInfo best = bestInfo.get();
                if (best != null) {
                    FileLog.d("SilentProxy: fastest proxy " + best.settings.getAddress() + ":" + best.settings.getPort() + " (" + bestLatencyMs.get() + "ms probe, " + knownLatencyMs(proxyKey(best)) + "ms effective)");
                } else {
                    FileLog.d("SilentProxy: no live proxy found in this round");
                }
            } finally {
                roundRunning.set(false);
            }
        }, "silent-proxy-probe-wait").start();
    }

    /**
     * SilentGram K7: remove proxies that are dead in this round from the
     * managed list — but ONLY when this round proves the user's own
     * network is fine. The guard is computed from this round's results:
     * at least 3 proxies live AND more live than dead. If all or most
     * proxies fail at once, the user's own network is the problem, so
     * NOTHING is cleared. Only state 2 (TCP connect failed) counts as
     * dead — state 3 (TCP live, handshake failed) and still-probing
     * slots never count. A removed proxy is not gone for good: the next
     * hourly fetch re-adds it if the API lists it live again.
     */
    private static void cleanupDeadProxies(List<SharedConfig.ProxyInfo> ranked, int[] state) {
        int live = 0;
        int dead = 0;
        ArrayList<SharedConfig.ProxyInfo> deadInfos = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            if (state[i] == 1 || state[i] == 3) {
                live++;
            } else if (state[i] == 2) {
                dead++;
                deadInfos.add(ranked.get(i));
            }
            // state 0 = still probing: never counted as dead.
        }
        if (deadInfos.isEmpty() || live < 3 || live <= dead) {
            FileLog.d("SilentProxy: K7 cleanup skipped (live=" + live + " dead=" + dead + " — network not proven OK)");
            return;
        }
        SharedPreferences prefs = ApplicationLoader.applicationContext
                .getSharedPreferences("silentgram", android.content.Context.MODE_PRIVATE);
        HashSet<String> managed = new HashSet<>();
        String savedManaged = prefs.getString("managed_proxies", "");
        if (savedManaged != null && !savedManaged.isEmpty()) {
            for (String k : savedManaged.split(",")) {
                if (!k.isEmpty()) managed.add(k);
            }
        }
        // Never remove the active proxy — deleting it would disable the
        // proxy entirely (SharedConfig.deleteProxy) — and never touch
        // proxies the user added by hand.
        String activeKey = SharedConfig.currentProxy != null
                ? SharedConfig.currentProxy.settings.getAddress() + ":" + SharedConfig.currentProxy.settings.getPort()
                : null;
        boolean changed = false;
        for (SharedConfig.ProxyInfo info : deadInfos) {
            String key = proxyKey(info);
            if (key.equals(activeKey) || !managed.contains(key)) {
                continue;
            }
            SharedConfig.deleteProxy(info);
            managed.remove(key);
            lastCandidates.remove(info);
            changed = true;
            FileLog.d("SilentProxy: K7 removed dead proxy " + key);
        }
        if (changed) {
            StringBuilder sb = new StringBuilder();
            for (String k : managed) {
                if (sb.length() > 0) sb.append(',');
                sb.append(k);
            }
            prefs.edit().putString("managed_proxies", sb.toString()).apply();
        }
    }

    /**
     * The connect gate: walk the ranked list from the fastest down and
     * connect to the first LIVE proxy. Dead, watchdog-blocked or failed
     * proxies are skipped (fall through to the next-fastest).
     *
     * Two modes. Not connected yet: connect ASAP to the first verified
     * live proxy in API-latency order — the user needs a connection in
     * milliseconds (a later refresh re-evaluates with measured
     * latencies). Already connected (SilentGram K6 re-evaluation): do
     * NOT decide on the first candidate — wait until EVERY candidate
     * resolved, then judge the verified-live proxy with the LOWEST
     * measured on-device latency. Judging the first one in API order
     * could switch to a proxy that is fast from the API server but slow
     * from this phone, and judging before the round finished could keep
     * a slow proxy because the faster one had not resolved yet.
     * If a proxy is already active, only switch when the fastest live
     * one beats it by SWITCH_FASTER_BY_MS (or the current latency is
     * unknown, or the current proxy probed dead). SilentGram I6: state
     * 3 means TCP-live but the MTProto handshake failed — only a
     * fallback, and only when the current proxy probed dead (state 2);
     * swapping between two handshake-failed proxies is churn, so the
     * gate stays put instead. SilentGram K6b: a state-3 current proxy
     * counts as dead for the bar (see currentProxyMeasuredLatency) —
     * its TCP time is not a usable latency — so any verified proxy
     * beats it.
     */
    private static void tryConnectFastest(List<SharedConfig.ProxyInfo> ranked, int[] state, Object gateLock,
            AtomicBoolean decisionMade, AtomicBoolean connectedOnce, long activeLatency) {
        synchronized (gateLock) {
            if (decisionMade.get()) {
                return;
            }
            // Fresh on-device latency of the current proxy, measured in
            // THIS round (Long.MAX_VALUE if it probed dead, -1 if
            // unknown). The old code compared the candidate's API latency
            // against a latency recorded when the current proxy was
            // enabled — a stale number measured from the API server, never
            // re-measured — so the keep-current branch won almost every
            // round and the app stayed on the slowest proxy.
            long bar = currentProxyMeasuredLatency(ranked, state, activeLatency);
            if (connectedOnce.get()) {
                // SilentGram K6 (re-evaluation): already connected, so
                // there is no rush — wait for the full picture.
                for (int i = 0; i < ranked.size(); i++) {
                    if (state[i] == 0) {
                        // Still probing — wait for every candidate.
                        return;
                    }
                }
                int bestVerified = -1;
                long bestVerifiedMs = Long.MAX_VALUE;
                int bestUnverified = -1;
                long bestUnverifiedMs = Long.MAX_VALUE;
                for (int i = 0; i < ranked.size(); i++) {
                    if (state[i] != 1 && state[i] != 3) {
                        // Dead or failed — skip.
                        continue;
                    }
                    SharedConfig.ProxyInfo info = ranked.get(i);
                    if (isProxyBlocked(proxyKey(info))) {
                        // Watchdog-blocked — skip.
                        continue;
                    }
                    long ms = latencyForDecision(proxyKey(info));
                    if (ms < 0) {
                        continue;
                    }
                    if (state[i] == 1) {
                        if (ms < bestVerifiedMs) {
                            bestVerifiedMs = ms;
                            bestVerified = i;
                        }
                    } else if (ms < bestUnverifiedMs) {
                        bestUnverifiedMs = ms;
                        bestUnverified = i;
                    }
                }
                if (bestVerified >= 0) {
                    tryConnectCandidate(ranked, state, bestVerified, decisionMade, connectedOnce, bar);
                } else if (bestUnverified >= 0 && currentProxyState(ranked, state) == 2) {
                    // No verified proxy this round, and the current proxy
                    // probed DEAD — fall back to the fastest TCP-live one
                    // rather than staying on a dead proxy. If the current
                    // proxy is only unverified (state 3, handshake
                    // failed), stay put: swapping between two
                    // handshake-failed proxies is pure reconnect churn,
                    // and the watcher retries in a few minutes.
                    tryConnectCandidate(ranked, state, bestUnverified, decisionMade, connectedOnce, bar);
                }
                // Otherwise no live, unblocked proxy this round — stay
                // on the current one; the next refresh tries again.
                return;
            }
            // Not connected yet: first verified live proxy in ranked
            // order wins ASAP. Stops at the first slot whose probe is
            // still pending — a faster proxy may still turn out live, so
            // a slower one must not win by finishing first.
            int firstUnverified = -1;
            for (int i = 0; i < ranked.size(); i++) {
                if (state[i] == 0) {
                    // A faster proxy is still probing — wait for it.
                    return;
                }
                if (state[i] != 1 && state[i] != 3) {
                    // Dead or failed — fall through to the next-fastest.
                    continue;
                }
                SharedConfig.ProxyInfo info = ranked.get(i);
                String key = proxyKey(info);
                if (isProxyBlocked(key)) {
                    // Watchdog-blocked — fall through to the next-fastest.
                    continue;
                }
                if (state[i] == 3) {
                    if (firstUnverified < 0) {
                        firstUnverified = i;
                    }
                    continue;
                }
                if (tryConnectCandidate(ranked, state, i, decisionMade, connectedOnce, bar)) {
                    return;
                }
            }
            // Every candidate resolved and none was a live, unblocked,
            // VERIFIED proxy better than the current one — fall back to the
            // fastest unverified one rather than staying disconnected.
            if (firstUnverified >= 0) {
                tryConnectCandidate(ranked, state, firstUnverified, decisionMade, connectedOnce, bar);
            }
            // Otherwise every candidate resolved and none was a live,
            // unblocked proxy better than the current one — nothing to
            // connect to this round.
        }
    }

    /**
     * Try to make ranked[index] the active proxy: keep the current proxy
     * unless this one is clearly faster on THIS phone (or the current
     * latency is unknown, or the current proxy probed dead this round).
     * bar is the current proxy's fresh measured latency from this round
     * (Long.MAX_VALUE = probed dead, -1 = unknown). Returns true if a
     * decision was made (connected, or kept the current proxy); false if
     * this candidate failed and the next one should be tried.
     */
    private static boolean tryConnectCandidate(List<SharedConfig.ProxyInfo> ranked, int[] state, int index,
            AtomicBoolean decisionMade, AtomicBoolean connectedOnce, long bar) {
        SharedConfig.ProxyInfo info = ranked.get(index);
        String key = proxyKey(info);
        long ms = latencyForDecision(key);
        if (connectedOnce.get()) {
            SharedConfig.ProxyInfo curProxy = SharedConfig.currentProxy;
            if (curProxy != null && sameProxy(curProxy, info)) {
                // Already on the fastest live proxy — nothing to do.
                decisionMade.set(true);
                return true;
            }
            if (bar >= 0 && bar != Long.MAX_VALUE && (ms < 0 || ms + SWITCH_FASTER_BY_MS >= bar)) {
                // Fastest live proxy is not clearly faster than the
                // active one — and every other candidate is slower — so
                // keep the current proxy. (ms < 0 = its latency is
                // unknown, so staying put is the safe choice.)
                decisionMade.set(true);
                return true;
            }
            // Clearly faster (bar == Long.MAX_VALUE means the current
            // proxy probed dead; bar < 0 means its latency is unknown):
            // switch.
            FileLog.d("SilentProxy: switching to faster proxy " + info.settings.getAddress() + ":" + info.settings.getPort() + " (" + ms + "ms measured, was " + (bar == Long.MAX_VALUE ? "dead" : bar + "ms") + ")");
        }
        try {
            enableProxy(info, ms);
        } catch (Throwable e) {
            // Connecting to this one failed — do not stop here, fall
            // through to the next-fastest live proxy.
            FileLog.e("SilentProxy: failed to enable " + key + ", trying next-fastest", e);
            state[index] = 2;
            return false;
        }
        connectedOnce.set(true);
        decisionMade.set(true);
        return true;
    }

    private static boolean sameProxy(SharedConfig.ProxyInfo a, SharedConfig.ProxyInfo b) {
        return a.settings.getAddress().equals(b.settings.getAddress())
                && a.settings.getPort() == b.settings.getPort();
    }

    /** Fast TCP connect probe. Returns connect latency in ms, or -1 if unreachable. */
    private static long probeLatency(String host, int port) {
        Socket socket = new Socket();
        try {
            long start = System.currentTimeMillis();
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            return System.currentTimeMillis() - start;
        } catch (Throwable e) {
            return -1;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // SilentGram I6: how long to wait for a proxy's MTProto handshake check.
    private static final int CHECK_PROXY_TIMEOUT_MS = 8000;

    /**
     * SilentGram I6: verify a proxy with a real MTProto handshake
     * (ConnectionsManager.checkProxy — the same check ProxyListActivity
     * uses), not just a TCP connect. A proxy that accepts TCP but never
     * delivers data passes the TCP probe yet fails this check. Returns
     * the handshake round-trip in ms, -1 if the handshake failed or timed
     * out, or -2 when no account is signed in yet and there is nothing
     * to check against. Never throws.
     */
    private static long verifyProxyWithHandshake(ProxySettings settings) {
        if (!UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
            return -2;
        }
        final AtomicLong result = new AtomicLong(-1);
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(settings, time -> {
                result.set(time);
                latch.countDown();
            });
        } catch (Throwable e) {
            return -1;
        }
        try {
            latch.await(CHECK_PROXY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return result.get();
    }

    /** Make the given proxy the active one and push it to the connection layer. */
    private static void enableProxy(SharedConfig.ProxyInfo info, long latencyMs) {
        SharedConfig.currentProxy = info;
        currentLatencyMs.set(latencyMs);
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        info.settings.toSharedPreferences(editor);
        editor.putBoolean("proxy_enabled", true);
        editor.apply();
        ConnectionsManager.setProxySettings(true, info.settings);
        FileLog.d("SilentProxy: enabled proxy " + info.settings.getAddress() + ":" + info.settings.getPort() + " (" + latencyMs + "ms)");
    }

    /**
     * Download watchdog — catches proxies that connect but never deliver
     * data (no stickers / emoji / files download through them). Every 10s
     * it checks each actively-downloading file's size on disk; if a file
     * makes zero progress for 30s straight, the current proxy is marked bad
     * (blocked for 1 hour) and the app switches to the next candidate.
     * A 2-minute cooldown stops it switching back and forth.
     */
    private static void startDownloadWatchdog() {
        if (watchdogStarted) {
            return;
        }
        watchdogStarted = true;
        new Thread(() -> {
            HashMap<String, long[]> tracked = new HashMap<>(); // path -> [size, stallChecks]
            while (true) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS);
                } catch (InterruptedException ignored) {
                    return;
                }
                try {
                    checkDownloads(tracked);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }, "silent-proxy-watchdog").start();
    }

    private static void checkDownloads(HashMap<String, long[]> tracked) {
        // Only relevant while a proxy is active.
        if (!SharedConfig.isProxyEnabled() || SharedConfig.currentProxy == null) {
            tracked.clear();
            return;
        }
        int account = UserConfig.selectedAccount;
        ArrayList<MessageObject> downloading;
        try {
            downloading = new ArrayList<>(DownloadController.getInstance(account).downloadingFiles);
        } catch (Throwable e) {
            return;
        }
        if (downloading.isEmpty()) {
            tracked.clear();
            return;
        }
        boolean anyStalled = false;
        HashMap<String, long[]> next = new HashMap<>();
        for (MessageObject msg : downloading) {
            try {
                TLRPC.Document doc = msg.getDocument();
                if (doc == null) {
                    continue;
                }
                File file = FileLoader.getInstance(account).getPathToAttach(doc, true);
                if (file == null) {
                    continue;
                }
                String path = file.getAbsolutePath();
                long size = file.exists() ? file.length() : 0;
                long[] prev = tracked.get(path);
                if (prev != null && prev[0] == size) {
                    long stalls = prev[1] + 1;
                    next.put(path, new long[]{size, stalls});
                    if (stalls >= WATCHDOG_STALL_CHECKS) {
                        anyStalled = true;
                    }
                } else {
                    next.put(path, new long[]{size, 0});
                }
            } catch (Throwable ignored) {
            }
        }
        tracked.clear();
        tracked.putAll(next);
        if (anyStalled) {
            switchAwayFromBadProxy();
            tracked.clear();
        }
    }

    /** The current proxy is blocking downloads — mark it bad, switch to the next candidate. */
    private static void switchAwayFromBadProxy() {
        long now = System.currentTimeMillis();
        if (now - lastWatchdogSwitchMs < WATCHDOG_SWITCH_COOLDOWN_MS) {
            return;
        }
        switchToNextProxy(true);
    }

    /**
     * SilentGram I8: manual proxy switch (the chat-list "Refresh" button).
     * Marks the current proxy bad for an hour — the same as the download
     * watchdog does — connects to the next-fastest candidate, then fetches
     * a fresh list in the background. Unlike the watchdog this is never
     * throttled by the switch cooldown: the user asked for it.
     * Returns true if the app switched to a different proxy.
     */
    public static boolean rotateProxy() {
        boolean switched = switchToNextProxy(true);
        refreshAsync();
        return switched;
    }

    /**
     * Mark the current proxy bad (unless markCurrentBad is false) and
     * connect to the next-fastest candidate that is neither the bad one
     * nor blocked. Returns true if the app switched proxies.
     */
    private static boolean switchToNextProxy(boolean markCurrentBad) {
        SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
        if (current == null) {
            return false;
        }
        List<SharedConfig.ProxyInfo> candidates = lastCandidates;
        if (candidates == null || candidates.size() < 2) {
            return false;
        }
        long now = System.currentTimeMillis();
        String badKey = proxyKey(current);
        if (markCurrentBad) {
            SharedPreferences prefs = ApplicationLoader.applicationContext
                    .getSharedPreferences("silentgram", android.content.Context.MODE_PRIVATE);
            prefs.edit().putLong("proxy_bad_" + badKey, now).apply();
            FileLog.d("SilentProxy: marking " + badKey + " bad — switching proxy");
        }
        // Pick the next-fastest candidate that is not the bad one and not
        // blocked — fastest-first by ON-DEVICE measured latency (API
        // latency only as fallback), so the abandon path lands on the
        // proxy that is actually fastest from this phone, not the one
        // that is fastest from the API server.
        List<SharedConfig.ProxyInfo> ranked = new ArrayList<>(candidates);
        Collections.sort(ranked, new Comparator<SharedConfig.ProxyInfo>() {
            @Override
            public int compare(SharedConfig.ProxyInfo a, SharedConfig.ProxyInfo b) {
                return Long.compare(latencyForRank(proxyKey(a)), latencyForRank(proxyKey(b)));
            }
        });
        for (SharedConfig.ProxyInfo info : ranked) {
            String key = proxyKey(info);
            if (key.equals(badKey)) {
                continue;
            }
            if (isProxyBlocked(key)) {
                continue;
            }
            lastWatchdogSwitchMs = now;
            // Record the on-device measured latency when known, so the
            // K6 switch bar stays an on-device number.
            enableProxy(info, latencyForDecision(key));
            return true;
        }
        FileLog.d("SilentProxy: no other candidate to switch to");
        return false;
    }

    // ------------------------------------------------------------------
    // SilentGram K6b: continuous background watcher.
    //
    // While the app is open, re-probe the fastest candidates every few
    // minutes and switch to the lowest-ms verified proxy — not only at
    // the hourly fetch. The watcher reuses the same probe round and
    // gate as the fetch path (same 50ms bar, same K7 cleanup guard),
    // just on a short interval and over a trimmed candidate list.
    // ------------------------------------------------------------------

    private static void startProxyWatcher() {
        if (watcherStarted) {
            return;
        }
        watcherStarted = true;
        new Thread(() -> {
            try {
                Thread.sleep(WATCHER_FIRST_DELAY_MS);
            } catch (InterruptedException ignored) {
                return;
            }
            while (true) {
                runWatcherRound();
                try {
                    Thread.sleep(WATCHER_INTERVAL_MS);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }, "silent-proxy-watcher").start();
    }

    /** One watcher round: re-probe the fastest saved candidates (no API fetch) and let the gate switch if a faster verified proxy exists. */
    private static void runWatcherRound() {
        try {
            if (ForegroundDetector.getInstance().isBackground()) {
                // App is in the background: do not probe or switch proxies.
                // A live session in the background makes Telegram's server
                // think we are online, which suppresses push notifications.
                return;
            }
            if (SharedConfig.currentProxy == null) {
                // Nothing connected yet — the startup fetch owns the
                // first connect; the watcher only switches while open.
                return;
            }
            List<SharedConfig.ProxyInfo> saved = lastCandidates;
            if (saved == null || saved.isEmpty()) {
                // No candidates cached (fetch never succeeded) — re-fetch
                // instead of probing nothing.
                refreshAsync();
                return;
            }
            List<SharedConfig.ProxyInfo> trimmed = new ArrayList<>(saved);
            Collections.sort(trimmed, new Comparator<SharedConfig.ProxyInfo>() {
                @Override
                public int compare(SharedConfig.ProxyInfo a, SharedConfig.ProxyInfo b) {
                    return Long.compare(effectiveLatencyMs(proxyKey(a)), effectiveLatencyMs(proxyKey(b)));
                }
            });
            if (trimmed.size() > WATCHER_MAX_CANDIDATES) {
                trimmed = new ArrayList<>(trimmed.subList(0, WATCHER_MAX_CANDIDATES));
            }
            probeAndConnect(trimmed, true);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Watcher-round report card, run after the round's gate and K7
     * cleanup. The gate already switches away from a dead or
     * handshake-failed current proxy when a verified proxy exists —
     * this covers the case where it could NOT switch (no verified
     * proxy that round): if the current proxy fails verification in
     * WATCHER_UNSTABLE_ROUNDS rounds running, it is unstable (the
     * "keeps reconnecting" proxy) — mark it bad for an hour, exactly
     * like the download watchdog does, and switch NOW to the fastest
     * verified proxy in this round. A proxy that verifies fine resets
     * the count, so one bad round never condemns a good proxy.
     */
    private static void noteWatcherRound(List<SharedConfig.ProxyInfo> ranked, int[] state) {
        SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
        if (current == null) {
            watcherUnstableRounds = 0;
            return;
        }
        int currentState = currentProxyState(ranked, state);
        if (currentState == 1) {
            watcherUnstableRounds = 0;
            return;
        }
        if (currentState != 2 && currentState != 3) {
            // Still probing, or not a candidate this round — do not count.
            return;
        }
        watcherUnstableRounds++;
        FileLog.d("SilentProxy: K6b watcher: current proxy " + proxyKey(current) + " not verified for " + watcherUnstableRounds + " round(s) (state=" + currentState + ")");
        if (watcherUnstableRounds < WATCHER_UNSTABLE_ROUNDS) {
            return;
        }
        watcherUnstableRounds = 0;
        String badKey = proxyKey(current);
        SharedPreferences prefs = ApplicationLoader.applicationContext
                .getSharedPreferences("silentgram", android.content.Context.MODE_PRIVATE);
        prefs.edit().putLong("proxy_bad_" + badKey, System.currentTimeMillis()).apply();
        FileLog.d("SilentProxy: K6b watcher: abandoning unstable proxy " + badKey);
        switchToBestVerified(ranked, state);
    }

    /**
     * Switch to the fastest handshake-verified, unblocked proxy in this
     * round, regardless of the 50ms margin — the current proxy was
     * already condemned as unstable, so any verified proxy beats it.
     * Tries each verified candidate in latency order until one
     * connects; returns true if the app switched (or was already on
     * the best one).
     */
    private static boolean switchToBestVerified(List<SharedConfig.ProxyInfo> ranked, int[] state) {
        ArrayList<Integer> verifiedIdx = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            if (state[i] == 1 && !isProxyBlocked(proxyKey(ranked.get(i)))) {
                verifiedIdx.add(i);
            }
        }
        Collections.sort(verifiedIdx, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Long.compare(latencyForRank(proxyKey(ranked.get(a))), latencyForRank(proxyKey(ranked.get(b))));
            }
        });
        for (int index : verifiedIdx) {
            if (tryConnectCandidate(ranked, state, index, new AtomicBoolean(false), new AtomicBoolean(true), Long.MAX_VALUE)) {
                return true;
            }
        }
        FileLog.d("SilentProxy: K6b watcher: no verified proxy to abandon to");
        return false;
    }
}
