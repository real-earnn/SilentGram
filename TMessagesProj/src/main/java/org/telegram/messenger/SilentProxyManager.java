package org.telegram.messenger;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * the first one that answers — so the user gets no delay. The rest keep being
 * checked in the background, and if a faster one shows up the app switches
 * to it in milliseconds. Telegram's built-in proxy rotation is also left on,
 * so if the active proxy dies the next one takes over automatically.
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
    private static final String API_LIVE_URL = "https://silentgram-proxy.silenthost.site/api/live?limit=10";
    private static final String API_URL = "https://silentgram-proxy.silenthost.site/api/proxies";
    // Refresh the list every 1 hour (the API itself refreshes every 1 hour).
    private static final long REFRESH_INTERVAL_MS = 60 * 60 * 1000L;
    // A proxy must answer a TCP connect within this time to count as live.
    private static final int PROBE_TIMEOUT_MS = 1500;
    // Only switch to a faster proxy if it beats the current one by this much.
    private static final long SWITCH_FASTER_BY_MS = 100;
    // At most this many proxies are ever added/saved — the user asked for the
    // 10 fastest only, so the list never floods and the network never chokes
    // on hundreds of parallel checks.
    private static final int MAX_PROXIES = 10;
    // How many proxies to probe in parallel (only 10 candidates now).
    private static final int PROBE_THREADS = 5;

    private static volatile boolean started;
    private static volatile boolean fetching;

    // Latency (ms) of the currently active proxy, -1 if unknown.
    private static final AtomicLong currentLatencyMs = new AtomicLong(-1);
    // The fastest proxy found in the current round (for background switching).
    private static final AtomicReference<SharedConfig.ProxyInfo> bestInfo = new AtomicReference<>();
    private static final AtomicLong bestLatencyMs = new AtomicLong(Long.MAX_VALUE);

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
    }

    /** Fetch the list from our API and apply the best proxy. Runs off the UI thread. */
    public static void refreshAsync() {
        if (fetching) {
            return;
        }
        fetching = true;
        new Thread(() -> {
            try {
                // /api/live first (only the fastest live proxies, pre-checked
                // by the API). Fall back to the full list, capped at 10.
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
                }
            }
            return out;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Keep at most MAX_PROXIES managed proxies saved (the fastest live ones
     * from the API). Old managed proxies that are no longer in the top 10
     * are removed, so the list never floods to hundreds and the network
     * never chokes on parallel checks. Proxies the user added by hand are
     * never touched. Then probe the (few) candidates and connect to the
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
        // Remove managed proxies that are no longer in the top 10 (but never
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

        probeAndConnect(all);

        // Schedule the next refresh.
        AndroidUtilities.runOnUIThread(SilentProxyManager::refreshAsync, REFRESH_INTERVAL_MS);
    }

    /**
     * Probe every proxy in parallel (fast TCP connect). The first one that
     * answers is connected to immediately — no waiting for the rest. The
     * remaining probes keep running in the background; if one turns out
     * clearly faster than the active proxy, the app switches to it.
     */
    private static void probeAndConnect(List<SharedConfig.ProxyInfo> candidates) {
        bestInfo.set(null);
        bestLatencyMs.set(Long.MAX_VALUE);
        AtomicBoolean connectedOnce = new AtomicBoolean(SharedConfig.currentProxy != null && SharedConfig.isProxyEnabled());
        // If we already have an active proxy, remember its latency as the bar
        // a challenger must beat (unknown -> any live proxy wins).
        final long activeLatency = currentLatencyMs.get();
        CountDownLatch done = new CountDownLatch(candidates.size());
        ExecutorService pool = Executors.newFixedThreadPool(PROBE_THREADS);
        for (SharedConfig.ProxyInfo info : candidates) {
            pool.execute(() -> {
                try {
                    long ms = probeLatency(info.settings.getAddress(), info.settings.getPort());
                    if (ms >= 0) {
                        onProxyLive(info, ms, connectedOnce, activeLatency);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    done.countDown();
                }
            });
        }
        pool.shutdown();
        // Wait for all probes in the background so we can log the outcome;
        // the user is NOT blocked on this — the first live proxy already
        // connected above.
        new Thread(() -> {
            try {
                done.await(PROBE_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            SharedConfig.ProxyInfo best = bestInfo.get();
            if (best != null) {
                FileLog.d("SilentProxy: fastest proxy " + best.settings.getAddress() + ":" + best.settings.getPort() + " (" + bestLatencyMs.get() + "ms)");
            }
        }, "silent-proxy-probe-wait").start();
    }

    /** A probe finished: this proxy is live with the given connect latency. */
    private static void onProxyLive(SharedConfig.ProxyInfo info, long ms, AtomicBoolean connectedOnce, long activeLatency) {
        // Track the fastest proxy seen in this round.
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
        // First live proxy: connect right away, no waiting.
        if (connectedOnce.compareAndSet(false, true)) {
            enableProxy(info, ms);
            return;
        }
        // Already connected: only switch if this one is clearly faster.
        long current = currentLatencyMs.get();
        long bar = current >= 0 ? current : activeLatency;
        if (bar >= 0 && ms + SWITCH_FASTER_BY_MS < bar) {
            SharedConfig.ProxyInfo curProxy = SharedConfig.currentProxy;
            if (curProxy == null || !sameProxy(curProxy, info)) {
                FileLog.d("SilentProxy: switching to faster proxy " + info.settings.getAddress() + ":" + info.settings.getPort() + " (" + ms + "ms, was " + bar + "ms)");
                enableProxy(info, ms);
            }
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
}
