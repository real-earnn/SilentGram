package org.telegram.messenger;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
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
    // Only switch to a faster proxy if it beats the current one by this much.
    private static final long SWITCH_FASTER_BY_MS = 100;
    // At most this many proxies are ever added/saved — the full daily live
    // list (limit=50 above), so every live proxy reaches the app and the
    // fastest one can be picked. The /api/proxies fallback is also sorted
    // by latency, so capping it here keeps the 50 fastest of the full list.
    private static final int MAX_PROXIES = 50;
    // How many proxies to probe in parallel (up to 50 candidates now).
    private static final int PROBE_THREADS = 10;

    private static volatile boolean started;
    private static volatile boolean fetching;

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

        // Let Telegram rotate to the next proxy automatically if the active one dies.
        SharedConfig.proxyRotationEnabled = true;
        SharedPreferences mainPrefs = MessagesController.getGlobalMainSettings();
        mainPrefs.edit().putBoolean("proxyRotationEnabled", true).apply();

        lastCandidates = new ArrayList<>(all);
        probeAndConnect(all);

        // Schedule the next refresh.
        AndroidUtilities.runOnUIThread(SilentProxyManager::refreshAsync, REFRESH_INTERVAL_MS);
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
    private static void probeAndConnect(List<SharedConfig.ProxyInfo> candidates) {
        bestInfo.set(null);
        bestLatencyMs.set(Long.MAX_VALUE);
        // Fastest-first by effective latency (unknown sorts last). The
        // connect gate below walks this order, so the winner is always the
        // lowest-latency LIVE proxy.
        final List<SharedConfig.ProxyInfo> ranked = new ArrayList<>(candidates);
        Collections.sort(ranked, new Comparator<SharedConfig.ProxyInfo>() {
            @Override
            public int compare(SharedConfig.ProxyInfo a, SharedConfig.ProxyInfo b) {
                return Long.compare(effectiveLatencyMs(proxyKey(a)), effectiveLatencyMs(proxyKey(b)));
            }
        });
        final int n = ranked.size();
        // Probe outcome per slot, in ranked order: 0 = pending, 1 = live, 2 = dead/failed.
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
                    long ms = probeLatency(info.settings.getAddress(), info.settings.getPort());
                    synchronized (gateLock) {
                        if (ms >= 0) {
                            probeLatencyByKey.put(key, ms);
                            // Feed Telegram's own rotation too: its list is
                            // sorted by ping, so a real probe time here makes
                            // the built-in fallback order fastest-first as well.
                            // (availableCheckTime is NOT touched — upstream
                            // measures it with elapsedRealtime, not wall time.)
                            info.ping = ms;
                            info.available = true;
                            state[index] = 1;
                            // Track the fastest by own probe RTT (log only).
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
                        } else {
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
        // Wait for all probes in the background so we can log the outcome;
        // the user is NOT blocked on this — the fastest live proxy already
        // connected through the gate above.
        new Thread(() -> {
            try {
                done.await(PROBE_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            SharedConfig.ProxyInfo best = bestInfo.get();
            if (best != null) {
                FileLog.d("SilentProxy: fastest proxy " + best.settings.getAddress() + ":" + best.settings.getPort() + " (" + bestLatencyMs.get() + "ms probe, " + knownLatencyMs(proxyKey(best)) + "ms effective)");
            } else {
                FileLog.d("SilentProxy: no live proxy found in this round");
            }
        }, "silent-proxy-probe-wait").start();
    }

    /**
     * The connect gate: walk the ranked list from the fastest down and
     * connect to the first LIVE proxy. Stops at the first slot whose probe
     * is still pending — a faster proxy may still turn out live, so a
     * slower one must not win by finishing first. Dead, watchdog-blocked
     * or failed proxies are skipped (fall through to the next-fastest).
     * If a proxy is already active, only switch when the fastest live one
     * beats it by SWITCH_FASTER_BY_MS (or the current latency is unknown).
     */
    private static void tryConnectFastest(List<SharedConfig.ProxyInfo> ranked, int[] state, Object gateLock,
            AtomicBoolean decisionMade, AtomicBoolean connectedOnce, long activeLatency) {
        synchronized (gateLock) {
            if (decisionMade.get()) {
                return;
            }
            for (int i = 0; i < ranked.size(); i++) {
                if (state[i] == 0) {
                    // A faster proxy is still probing — wait for it.
                    return;
                }
                if (state[i] != 1) {
                    // Dead or failed — fall through to the next-fastest.
                    continue;
                }
                SharedConfig.ProxyInfo info = ranked.get(i);
                String key = proxyKey(info);
                if (isProxyBlocked(key)) {
                    // Watchdog-blocked — fall through to the next-fastest.
                    continue;
                }
                long ms = knownLatencyMs(key);
                if (connectedOnce.get()) {
                    SharedConfig.ProxyInfo curProxy = SharedConfig.currentProxy;
                    if (curProxy != null && sameProxy(curProxy, info)) {
                        // Already on the fastest live proxy — nothing to do.
                        decisionMade.set(true);
                        return;
                    }
                    long current = currentLatencyMs.get();
                    long bar = current >= 0 ? current : activeLatency;
                    if (bar >= 0 && ms + SWITCH_FASTER_BY_MS >= bar) {
                        // Fastest live proxy is not clearly faster than the
                        // active one — and every later candidate is slower —
                        // so keep the current proxy.
                        decisionMade.set(true);
                        return;
                    }
                    // Clearly faster (or current latency unknown): switch.
                    FileLog.d("SilentProxy: switching to faster proxy " + info.settings.getAddress() + ":" + info.settings.getPort() + " (" + ms + "ms, was " + bar + "ms)");
                }
                try {
                    enableProxy(info, ms);
                } catch (Throwable e) {
                    // Connecting to this one failed — do not stop here, fall
                    // through to the next-fastest live proxy.
                    FileLog.e("SilentProxy: failed to enable " + key + ", trying next-fastest", e);
                    state[i] = 2;
                    continue;
                }
                connectedOnce.set(true);
                decisionMade.set(true);
                return;
            }
            // Every candidate resolved and none was a live, unblocked proxy
            // better than the current one — nothing to connect to this round.
        }
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
        SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
        if (current == null) {
            return;
        }
        List<SharedConfig.ProxyInfo> candidates = lastCandidates;
        if (candidates == null || candidates.size() < 2) {
            return;
        }
        // Mark the bad proxy (blocked for 1 hour, stored in our prefs).
        String badKey = current.settings.getAddress() + ":" + current.settings.getPort();
        SharedPreferences prefs = ApplicationLoader.applicationContext
                .getSharedPreferences("silentgram", android.content.Context.MODE_PRIVATE);
        prefs.edit().putLong("proxy_bad_" + badKey, now).apply();
        FileLog.d("SilentProxy: download stalled on " + badKey + " — switching proxy");
        // Pick the next-fastest candidate that is not the bad one and not
        // blocked — fastest-first by effective latency, same ranking as the
        // probe gate above (API latency_ms, else probe RTT).
        List<SharedConfig.ProxyInfo> ranked = new ArrayList<>(candidates);
        Collections.sort(ranked, new Comparator<SharedConfig.ProxyInfo>() {
            @Override
            public int compare(SharedConfig.ProxyInfo a, SharedConfig.ProxyInfo b) {
                return Long.compare(effectiveLatencyMs(proxyKey(a)), effectiveLatencyMs(proxyKey(b)));
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
            enableProxy(info, knownLatencyMs(key));
            return;
        }
        FileLog.d("SilentProxy: no other candidate to switch to");
    }
}
