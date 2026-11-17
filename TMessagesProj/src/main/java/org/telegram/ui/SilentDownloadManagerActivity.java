package org.telegram.ui;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentToast;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SilentGram E6 + K12: Download Manager, redesigned.
 *
 * - NO channel list: rows show the FILE (name, size, live progress) —
 *   the chat a file came from is deliberately not shown here.
 * - Auto Download Boost toggle at the top: ON boosts every new download
 *   to PRIORITY_HIGH automatically; OFF shows a per-file BOOST button
 *   for a manual boost.
 * - Segment selector (1..8 parts): stored in prefs and used by the
 *   Boost speed test, which really downloads the test file in that many
 *   parallel ranged parts.
 * - Speed Test: "Test with Boost" / "Test without Boost" download a real
 *   10 MB file and report the real measured MB/s (spinner while running).
 *
 * HONEST LIMIT (segments vs Telegram downloads): true multi-part
 * downloading of a Telegram file is NOT possible with the current loader.
 * FileLoader.loadFile() has no offset/range/segment parameters and hands
 * each file to exactly ONE FileLoadOperation (FileLoader.java, one
 * operation per file name), which walks its byte ranges sequentially with
 * a single outstanding MTProto request (startDownloadRequest is only
 * re-armed when the previous chunk finishes). Splitting one Telegram file
 * across N parallel operations would need a loader rewrite plus
 * reassembly, so the segment selector does NOT change how Telegram files
 * download — it controls the Boost speed test's parallel parts. The UI
 * says so under the selector.
 */
public class SilentDownloadManagerActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final String PREFS_NAME = "silentgram";
    private static final String PREF_AUTO_BOOST = "dl_auto_boost";
    private static final String PREF_SEGMENTS = "dl_segments";
    private static final int DEFAULT_SEGMENTS = 4;
    private static final int MAX_SEGMENTS = 8;

    /** Cloudflare's public speed-test endpoint: returns exactly N bytes. */
    private static final String SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=10000000";
    private static final long SPEED_TEST_BYTES = 10_000_000L;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ArrayList<MessageObject> files = new ArrayList<>();
    private TextView emptyView;

    private TextCheckCell autoBoostCell;
    private TextSettingsCell segmentsCell;
    private TextView btnBoostTest;
    private TextView btnNoBoostTest;
    private ProgressBar speedSpinner;
    private TextView resultBoostView;
    private TextView resultNoBoostView;

    /** Attach file names already auto-boosted (boost each file once). */
    private HashSet<String> autoBoostedNames = new HashSet<>();
    /** Attach file names the user boosted manually from this screen. */
    private HashSet<String> manuallyBoostedNames = new HashSet<>();

    private boolean speedTestRunning;
    private int speedTestToken;
    private long lastProgressRefreshMs;

    @Override
    public boolean onFragmentCreate() {
        // SilentGram K4: refresh live while open — a finished/failed
        // download disappears and a newly started one appears, instead
        // of the list only refreshing when the screen is re-opened.
        getNotificationCenter().addObserver(this, NotificationCenter.onDownloadingFilesChanged);
        // K12: live progress % — refresh rows (throttled) as chunks land.
        getNotificationCenter().addObserver(this, NotificationCenter.fileLoadProgressChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        speedTestToken++; // invalidate any speed test still running
        getNotificationCenter().removeObserver(this, NotificationCenter.onDownloadingFilesChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.fileLoadProgressChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.onDownloadingFilesChanged) {
            reload();
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            long now = SystemClock.uptimeMillis();
            if (adapter != null && now - lastProgressRefreshMs > 500) {
                lastProgressRefreshMs = now;
                adapter.notifyDataSetChanged();
            }
        }
    }

    private android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private boolean isAutoBoostEnabled() {
        return prefs().getBoolean(PREF_AUTO_BOOST, false);
    }

    private int getSegments() {
        int segments = prefs().getInt(PREF_SEGMENTS, DEFAULT_SEGMENTS);
        return Math.max(1, Math.min(MAX_SEGMENTS, segments));
    }

    private String getSegmentsLabel(int segments) {
        return segments == 1 ? "1 part" : segments + " parts";
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentDownloadManagerTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        frameLayout.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // (b) Auto Download Boost toggle, at the top of the screen.
        autoBoostCell = new TextCheckCell(context);
        autoBoostCell.setTextAndValueAndCheck(
                LocaleController.getString(R.string.SilentDownloadAutoBoost),
                LocaleController.getString(R.string.SilentDownloadAutoBoostSub),
                isAutoBoostEnabled(), false, true);
        autoBoostCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        autoBoostCell.setOnClickListener(v -> {
            boolean enabled = !isAutoBoostEnabled();
            prefs().edit().putBoolean(PREF_AUTO_BOOST, enabled).apply();
            autoBoostCell.setChecked(enabled);
            if (enabled) {
                applyAutoBoost();
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
        column.addView(autoBoostCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // (c) Segment count selector — "download in how many parts".
        segmentsCell = new TextSettingsCell(context);
        segmentsCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        updateSegmentsRow();
        segmentsCell.setOnClickListener(v -> showSegmentsPicker());
        column.addView(segmentsCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView segmentsHint = new TextView(context);
        segmentsHint.setText(LocaleController.getString(R.string.SilentDownloadSegmentsHint));
        segmentsHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        segmentsHint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        segmentsHint.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        column.addView(segmentsHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // (d) Speed Test section.
        HeaderCell speedHeader = new HeaderCell(context);
        speedHeader.setText(LocaleController.getString(R.string.SilentDownloadSpeedTest));
        column.addView(speedHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout speedRow = new LinearLayout(context);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        speedRow.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        btnBoostTest = makeSpeedButton(context, LocaleController.getString(R.string.SilentDownloadTestWithBoost));
        btnBoostTest.setOnClickListener(v -> startSpeedTest(true));
        speedRow.addView(btnBoostTest, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f));
        btnNoBoostTest = makeSpeedButton(context, LocaleController.getString(R.string.SilentDownloadTestWithoutBoost));
        btnNoBoostTest.setOnClickListener(v -> startSpeedTest(false));
        speedRow.addView(btnNoBoostTest, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f));
        speedSpinner = new ProgressBar(context);
        speedSpinner.setVisibility(View.GONE);
        speedRow.addView(speedSpinner, LayoutHelper.createLinear(AndroidUtilities.dp(24), AndroidUtilities.dp(24), Gravity.CENTER_VERTICAL, AndroidUtilities.dp(8), 0, 0, 0));
        column.addView(speedRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        resultBoostView = makeSpeedResult(context, LocaleController.getString(R.string.SilentDownloadSpeedWithBoost));
        column.addView(resultBoostView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        resultNoBoostView = makeSpeedResult(context, LocaleController.getString(R.string.SilentDownloadSpeedWithoutBoost));
        column.addView(resultNoBoostView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HeaderCell filesHeader = new HeaderCell(context);
        filesHeader.setText(LocaleController.getString(R.string.SilentDownloadDownloads));
        column.addView(filesHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        column.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1.0f));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.SilentDownloadManagerEmpty));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        emptyView.setVisibility(View.GONE);
        column.addView(emptyView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1.0f));

        reload();
        return fragmentView;
    }

    private TextView makeSpeedButton(Context context, CharSequence text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        button.setGravity(Gravity.CENTER);
        button.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10));
        return button;
    }

    private TextView makeSpeedResult(Context context, CharSequence label) {
        TextView result = new TextView(context);
        result.setText(label + ": —");
        result.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        result.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        result.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(2), AndroidUtilities.dp(16), AndroidUtilities.dp(2));
        return result;
    }

    private void updateSegmentsRow() {
        if (segmentsCell == null) {
            return;
        }
        segmentsCell.setTextAndValue(
                LocaleController.getString(R.string.SilentDownloadSegmentsTitle),
                getSegmentsLabel(getSegments()), true);
    }

    private void showSegmentsPicker() {
        final int current = getSegments();
        CharSequence[] items = new CharSequence[MAX_SEGMENTS];
        int[] icons = new int[MAX_SEGMENTS];
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            items[i] = getSegmentsLabel(i + 1);
            if (i + 1 == current) {
                icons[i] = R.drawable.msg_check_s;
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.SilentDownloadSegmentsTitle));
        builder.setItems(items, icons, (dialog, which) -> {
            prefs().edit().putInt(PREF_SEGMENTS, which + 1).apply();
            updateSegmentsRow();
        });
        builder.show();
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        files.clear();
        files.addAll(DownloadController.getInstance(currentAccount).downloadingFiles);
        applyAutoBoost();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmpty();
    }

    private void updateEmpty() {
        if (emptyView == null) {
            return;
        }
        boolean empty = files.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (listView != null) {
            listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    /** Auto Boost ON: every downloading file gets top priority, once each. */
    private void applyAutoBoost() {
        if (!isAutoBoostEnabled()) {
            return;
        }
        HashSet<String> current = new HashSet<>();
        for (int i = 0; i < files.size(); i++) {
            MessageObject msg = files.get(i);
            if (msg == null || msg.getDocument() == null) {
                continue;
            }
            String attachName = FileLoader.getAttachFileName(msg.getDocument());
            current.add(attachName);
            if (!autoBoostedNames.contains(attachName)) {
                autoBoostedNames.add(attachName);
                boostFile(currentAccount, msg);
            }
        }
        autoBoostedNames.retainAll(current);
    }

    /** Boost one file: highest priority + force it to the front of the queue. */
    public static void boostFile(int account, MessageObject msg) {
        if (msg == null || msg.getDocument() == null) {
            return;
        }
        FileLoader.getInstance(account).changePriority(FileLoader.PRIORITY_HIGH, msg.getDocument(), null, null, null, null, null);
    }

    // ---- Speed test: real downloads, real measured speeds, nothing faked. ----

    private void setSpeedTestUiRunning(boolean running) {
        if (speedSpinner != null) {
            speedSpinner.setVisibility(running ? View.VISIBLE : View.GONE);
        }
        if (btnBoostTest != null) {
            btnBoostTest.setEnabled(!running);
            btnBoostTest.setAlpha(running ? 0.5f : 1.0f);
        }
        if (btnNoBoostTest != null) {
            btnNoBoostTest.setEnabled(!running);
            btnNoBoostTest.setAlpha(running ? 0.5f : 1.0f);
        }
    }

    private void startSpeedTest(boolean withBoost) {
        if (speedTestRunning) {
            return;
        }
        speedTestRunning = true;
        final int token = ++speedTestToken;
        final boolean boost = withBoost;
        final int parts = getSegments();
        setSpeedTestUiRunning(true);
        new Thread(() -> {
            double mbps;
            if (boost && parts > 1) {
                // Boosted run: the test file in `parts` parallel ranged parts.
                mbps = downloadParallel(parts);
            } else {
                // Plain run: one single-stream download.
                mbps = downloadSingle();
            }
            final double resultMbps = mbps;
            AndroidUtilities.runOnUIThread(() -> {
                if (token != speedTestToken) {
                    return;
                }
                speedTestRunning = false;
                setSpeedTestUiRunning(false);
                showSpeedResult(boost, resultMbps);
            });
        }).start();
    }

    private void showSpeedResult(boolean withBoost, double mbps) {
        TextView target = withBoost ? resultBoostView : resultNoBoostView;
        if (target == null) {
            return;
        }
        String label = LocaleController.getString(withBoost ? R.string.SilentDownloadSpeedWithBoost : R.string.SilentDownloadSpeedWithoutBoost);
        if (mbps < 0) {
            target.setText(label + ": " + LocaleController.getString(R.string.SilentDownloadSpeedFailed));
        } else {
            target.setText(label + ": " + String.format(Locale.US, "%.1f MB/s", mbps));
        }
    }

    /** One single-stream download of the test file; returns MB/s, or -1 on failure. */
    private double downloadSingle() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(SPEED_TEST_URL).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "SilentGram Speed Test");
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return -1;
            }
            long startNs = System.nanoTime();
            long total = readAndCount(connection);
            long elapsedNs = System.nanoTime() - startNs;
            if (total <= 0 || elapsedNs <= 0) {
                return -1;
            }
            return total * 1e9 / (double) elapsedNs / 1000000.0;
        } catch (Exception e) {
            return -1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * The test file in `parts` parallel ranged downloads (HTTP Range).
     * Returns MB/s, or -1 on failure. If the server ignores Range
     * (no 206) or a part fails, falls back to one honest single-stream run.
     */
    private double downloadParallel(int parts) {
        final long totalSize = SPEED_TEST_BYTES;
        final AtomicLong downloaded = new AtomicLong(0);
        final AtomicBoolean failed = new AtomicBoolean(false);
        final AtomicBoolean rangeOk = new AtomicBoolean(true);
        final CountDownLatch latch = new CountDownLatch(parts);
        ExecutorService pool = Executors.newFixedThreadPool(parts);
        long startNs = System.nanoTime();
        for (int i = 0; i < parts; i++) {
            final long partStart = totalSize * i / parts;
            final long partEnd = (i == parts - 1) ? totalSize - 1 : totalSize * (i + 1) / parts - 1;
            pool.execute(() -> {
                HttpURLConnection partConnection = null;
                try {
                    partConnection = (HttpURLConnection) new URL(SPEED_TEST_URL).openConnection();
                    partConnection.setConnectTimeout(10000);
                    partConnection.setReadTimeout(30000);
                    partConnection.setRequestProperty("User-Agent", "SilentGram Speed Test");
                    partConnection.setRequestProperty("Range", "bytes=" + partStart + "-" + partEnd);
                    partConnection.connect();
                    if (partConnection.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) {
                        rangeOk.set(false);
                        failed.set(true);
                        return;
                    }
                    downloaded.addAndGet(readAndCount(partConnection));
                } catch (Exception e) {
                    failed.set(true);
                } finally {
                    if (partConnection != null) {
                        partConnection.disconnect();
                    }
                    latch.countDown();
                }
            });
        }
        pool.shutdown();
        try {
            if (!latch.await(120, TimeUnit.SECONDS)) {
                failed.set(true);
            }
        } catch (InterruptedException e) {
            failed.set(true);
        }
        if (!rangeOk.get() || failed.get()) {
            return downloadSingle();
        }
        long elapsedNs = System.nanoTime() - startNs;
        long total = downloaded.get();
        if (total <= 0 || elapsedNs <= 0) {
            return -1;
        }
        return total * 1e9 / (double) elapsedNs / 1000000.0;
    }

    /** Read a connection fully (bytes discarded) and return how many arrived. */
    private long readAndCount(HttpURLConnection connection) throws IOException {
        InputStream in = null;
        try {
            in = connection.getInputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
            }
            return total;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static class FileRowHolder extends RecyclerListView.Holder {
        TextView nameView;
        TextView subView;
        TextView boostView;

        FileRowHolder(View itemView, TextView nameView, TextView subView, TextView boostView) {
            super(itemView);
            this.nameView = nameView;
            this.subView = subView;
            this.boostView = boostView;
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            // File row, not a channel row: file name on top, size +
            // live progress below, BOOST button on the right.
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
            row.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            TextView subView = new TextView(context);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            texts.addView(subView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f));

            TextView boostView = new TextView(context);
            boostView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            boostView.setTypeface(AndroidUtilities.bold());
            boostView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            boostView.setGravity(Gravity.CENTER);
            boostView.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
            row.addView(boostView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            return new FileRowHolder(row, nameView, subView, boostView);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final MessageObject msg = files.get(position);
            FileRowHolder rowHolder = (FileRowHolder) holder;
            String name = msg.getFileName();
            if (name == null || name.isEmpty()) {
                name = LocaleController.getString(R.string.SilentDownloadUnknown);
            }
            rowHolder.nameView.setText(name);

            StringBuilder sub = new StringBuilder();
            final String attachName = msg.getDocument() != null ? FileLoader.getAttachFileName(msg.getDocument()) : null;
            if (msg.getDocument() != null) {
                sub.append(AndroidUtilities.formatFileSize(msg.getDocument().size));
                Float progress = attachName != null ? ImageLoader.getInstance().getFileProgress(attachName) : null;
                if (progress != null) {
                    sub.append("  •  ").append(Math.round(progress * 100)).append("%");
                }
                if (isAutoBoostEnabled()) {
                    sub.append("  •  ").append(LocaleController.getString(R.string.SilentDownloadAutoBoosted));
                }
            }
            if (sub.length() == 0) {
                sub.append(LocaleController.getString(R.string.SilentDownloadDownloading));
            }
            rowHolder.subView.setText(sub.toString());

            // (e) Per-file BOOST button only while Auto Boost is OFF;
            // when Auto Boost is ON the file is already boosted (see
            // applyAutoBoost), so there is nothing to tap.
            final boolean autoBoost = isAutoBoostEnabled();
            boolean boosted = attachName != null && manuallyBoostedNames.contains(attachName);
            if (autoBoost) {
                rowHolder.boostView.setVisibility(View.GONE);
            } else {
                rowHolder.boostView.setVisibility(View.VISIBLE);
                rowHolder.boostView.setText(boosted ? R.string.SilentDownloadBoostedShort : R.string.SilentBoostDownload);
                rowHolder.boostView.setEnabled(!boosted);
                rowHolder.boostView.setAlpha(boosted ? 0.4f : 1.0f);
                rowHolder.boostView.setOnClickListener(v -> {
                    boostFile(currentAccount, msg);
                    if (attachName != null) {
                        manuallyBoostedNames.add(attachName);
                    }
                    SilentToast.show(getParentActivity(), LocaleController.getString(R.string.SilentDownloadBoosted), android.widget.Toast.LENGTH_SHORT);
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }
    }
}
