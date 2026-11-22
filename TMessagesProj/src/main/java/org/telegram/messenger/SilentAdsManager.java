package org.telegram.messenger;

import android.content.Context;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdLoader;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.nativead.NativeAd;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * SilentGram v2.0.28: AdMob Native Advanced ads.
 *
 * App ID and ad unit ID were given by the user (AdMob console, app
 * "SilentGram", ad unit "My"). The App ID also lives in the manifest
 * meta-data com.google.android.gms.ads.APPLICATION_ID — the SDK reads
 * it from there at startup; the constants here are for the debug
 * report and for the ad-unit used by AdLoader.
 *
 * Placements (user spec):
 * - broadcast channels only (not megagroups, not private chats, not
 *   saved messages, not secret chats), and only where the user is NOT
 *   an admin/creator of the channel, and only for NON-Premium users;
 * - bot chats: a small full-width strip just below the top bar, also
 *   non-Premium only, and only when Telegram's own bot sponsored ad
 *   is not showing (we layer on top of the upstream system, never
 *   replace it).
 *
 * The manager owns the SDK init and a small pool of loaded NativeAds.
 * Each NativeAd is handed out once (takeAd) and then belongs to its
 * slot/strip until that chat is destroyed. Pool refills run in the
 * background (AdLoader.loadAds, 3 at a time). A pool refill notifies
 * registered listeners so empty ad rows can rebind.
 *
 * TEST MODE (user order 2026-11-23): this build loads Google's demo
 * test ads only (USE_TEST_ADS). A failed test-ad load would be a real
 * bug; a failed REAL-ad load is expected while the AdMob account
 * application is declined — the two must not be confused, and the
 * debug report labels which mode each load used. Never click own ads.
 */
public class SilentAdsManager {

    /** AdMob App ID (app "SilentGram"). Also in AndroidManifest.xml. */
    public static final String APP_ID = "ca-app-pub-1963262096178695~6236657040";
    /** Google's official demo App ID (developers.google.com/admob/android/test-ads). */
    public static final String TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713";
    /** Native Advanced ad unit ID (ad unit "My"). */
    public static final String NATIVE_AD_UNIT_ID = "ca-app-pub-1963262096178695/2858976825";
    /** Google's official demo Native ad unit (developers.google.com/admob/android/test-ads). */
    public static final String TEST_NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110";

    /**
     * TEST ADS (user order 2026-11-23 ~02:10 PKT): this build uses
     * Google's official TEST IDs — test App ID in the manifest, test
     * Native ad unit in code. Reason: the user's AdMob account
     * application was DECLINED twice ("does not meet our program
     * criteria" — most likely because SilentGram is not published on
     * Play Store yet, still in closed testing, so AdMob has no live app
     * to review). Real ads will NOT serve until the application is
     * re-accepted AND the app is published + linked + readiness-reviewed.
     * Test ads always fill, are labelled "Test Ad" in the headline, and
     * carry zero invalid-traffic risk. NEVER click real ads.
     * RELEASE SWITCH (one change in each of two places): set
     * USE_TEST_ADS = false below AND swap the manifest APPLICATION_ID
     * meta-data to the real App ID above. That is the whole swap.
     */
    public static final boolean USE_TEST_ADS = true;

    /** The App ID actually used (test ID while USE_TEST_ADS; mirrors the manifest). */
    public static String getActiveAppId() {
        return USE_TEST_ADS ? TEST_APP_ID : APP_ID;
    }

    /** The ad unit actually used for loads (test unit while USE_TEST_ADS). */
    public static String getActiveAdUnitId() {
        return USE_TEST_ADS ? TEST_NATIVE_AD_UNIT_ID : NATIVE_AD_UNIT_ID;
    }

    public static boolean isTestMode() {
        return USE_TEST_ADS;
    }

    /** How many native ads to request in one background refill. */
    private static final int REFILL_COUNT = 3;
    /** Max ads kept pooled. */
    private static final int POOL_MAX = 5;

    private static SilentAdsManager instance;

    public static synchronized SilentAdsManager getInstance() {
        if (instance == null) {
            instance = new SilentAdsManager();
        }
        return instance;
    }

    private boolean initStarted;
    private boolean initialized;
    private String initError;

    /** Last load outcome, human readable, for the debug report. */
    private String lastLoadState = "not started";
    private String lastLoadError;
    private long lastLoadAtMs;
    private int loadAttempts;

    /** Last ad actually shown (bound to a view), for the debug report. */
    private String lastShownPlacement;
    private String lastShownDialog;
    private long lastShownAtMs;

    private final ArrayList<NativeAd> pool = new ArrayList<>();
    private boolean loadInFlight;
    private long lastLoadFailedAtMs;
    private final ArrayList<Runnable> poolListeners = new ArrayList<>();

    private SilentAdsManager() {
    }

    /** Initialize the Mobile Ads SDK once, on the main thread. Safe to call repeatedly. */
    public void initialize(Context context) {
        if (initStarted || context == null) {
            return;
        }
        initStarted = true;
        try {
            Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            MobileAds.initialize(appContext, status -> {
                initialized = true;
                FileLog.d("SilentAds: MobileAds initialized");
                ensureLoaded();
            });
        } catch (Throwable e) {
            initError = String.valueOf(e.getMessage());
            FileLog.e("SilentAds: MobileAds.initialize failed", e);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getInitError() {
        return initError;
    }

    /**
     * Take one loaded ad from the pool, or null when the pool is empty.
     * Taking the last ad triggers a background refill. The caller owns
     * the returned ad and must destroy() it when done with it.
     */
    public synchronized NativeAd takeAd() {
        NativeAd ad = null;
        if (!pool.isEmpty()) {
            ad = pool.remove(pool.size() - 1);
        }
        if (pool.size() < 2) {
            ensureLoaded();
        }
        return ad;
    }

    public synchronized int getPoolSize() {
        return pool.size();
    }

    /** Start a background refill when the pool is low and no load is running. */
    public synchronized void ensureLoaded() {
        if (!initialized || loadInFlight || pool.size() >= 2) {
            return;
        }
        // Backoff: a persistently failing load must not retry in a tight loop.
        if (lastLoadFailedAtMs > 0 && System.currentTimeMillis() - lastLoadFailedAtMs < 30000) {
            return;
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        loadInFlight = true;
        loadAttempts++;
        lastLoadState = "loading";
        try {
            AdLoader loader = new AdLoader.Builder(context, getActiveAdUnitId())
                    .forNativeAd(ad -> {
                        synchronized (SilentAdsManager.this) {
                            if (pool.size() < POOL_MAX) {
                                pool.add(ad);
                            } else {
                                ad.destroy();
                            }
                        }
                        onAdLoaded();
                    })
                    .withAdListener(new AdListener() {
                        @Override
                        public void onAdFailedToLoad(LoadAdError error) {
                            synchronized (SilentAdsManager.this) {
                                loadInFlight = false;
                                lastLoadAtMs = System.currentTimeMillis();
                                lastLoadFailedAtMs = lastLoadAtMs;
                                lastLoadError = error == null ? "unknown" : ("code " + error.getCode() + ": " + error.getMessage());
                                lastLoadState = "failed (" + lastLoadError + ")";
                            }
                            FileLog.d("SilentAds: load failed: " + lastLoadError);
                            notifyPoolListeners();
                        }
                    })
                    .build();
            loader.loadAds(new AdRequest.Builder().build(), REFILL_COUNT);
        } catch (Throwable e) {
            loadInFlight = false;
            lastLoadError = String.valueOf(e.getMessage());
            lastLoadState = "failed (exception: " + lastLoadError + ")";
            FileLog.e("SilentAds: loadAds threw", e);
        }
    }

    private void onAdLoaded() {
        synchronized (this) {
            loadInFlight = false;
            lastLoadAtMs = System.currentTimeMillis();
            lastLoadError = null;
            lastLoadState = "loaded (pool " + pool.size() + ")";
        }
        FileLog.d("SilentAds: native ad loaded, pool=" + pool.size());
        notifyPoolListeners();
    }

    /** Called when a native ad is bound to a view and becomes visible. */
    public synchronized void recordShown(String placement, String dialogLabel) {
        lastShownPlacement = placement;
        lastShownDialog = dialogLabel == null ? "" : dialogLabel;
        lastShownAtMs = System.currentTimeMillis();
        if (lastShownDialog.length() > 100) {
            lastShownDialog = lastShownDialog.substring(0, 100);
        }
        lastShownAtMs = System.currentTimeMillis();
    }

    public void addPoolListener(Runnable listener) {
        if (listener == null) {
            return;
        }
        synchronized (poolListeners) {
            if (!poolListeners.contains(listener)) {
                poolListeners.add(listener);
            }
        }
    }

    public void removePoolListener(Runnable listener) {
        synchronized (poolListeners) {
            poolListeners.remove(listener);
        }
    }

    private void notifyPoolListeners() {
        ArrayList<Runnable> copy;
        synchronized (poolListeners) {
            copy = new ArrayList<>(poolListeners);
        }
        for (Runnable r : copy) {
            try {
                AndroidUtilities.runOnUIThread(r);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    // ---- Debug report state (all plain strings, no secrets) ----

    public synchronized String getLastLoadState() {
        return lastLoadState;
    }

    public synchronized String getLastLoadError() {
        return lastLoadError;
    }

    public synchronized long getLastLoadAtMs() {
        return lastLoadAtMs;
    }

    public synchronized int getLoadAttempts() {
        return loadAttempts;
    }

    public synchronized String getLastShownPlacement() {
        return lastShownPlacement;
    }

    public synchronized String getLastShownDialog() {
        return lastShownDialog;
    }

    public synchronized long getLastShownAtMs() {
        return lastShownAtMs;
    }

    public static String formatTime(long ms) {
        if (ms <= 0) {
            return "never";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }
}
