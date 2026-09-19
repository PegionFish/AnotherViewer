/*
 * Copyright 2026 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.anotherviewer.webui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.hippo.anotherviewer.Settings;
import com.hippo.anotherviewer.dao.PageHashStore;
import com.hippo.anotherviewer.gallery.GalleryProvider2;
import com.hippo.anotherviewer.spider.SpiderDen;
import com.hippo.anotherviewer.ui.MainActivity;
import com.hippo.unifile.UniFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opportunistic download-integrity scan (Wave 3, plan 2026-09-19 A4): mirrors
 * the {@link WebUiAutoSyncScheduler} shape — a default-network callback plus a
 * periodic tick feed one trigger, and a single background worker runs the
 * scan when the gate allows it. The gate is the conjunction of the user
 * switch ({@link Settings} {@code integrity_check_enabled}, default off),
 * WiFi connectivity and charging (BatteryManager sticky intent). A
 * min-interval cooldown keeps repeated network flaps from rehashing the
 * library back to back.
 *
 * <p>The scan walks the local download galleries (SiteDB's DOWNLOADS table),
 * and per gallery reads the page files out of the existing SpiderDen download
 * directory (read-only, 8-digit and legacy 4-digit page names), hashes them
 * with SHA-256 and compares against the {@link PageHashStore} baseline:
 * equal marks {@code verdict=ok}, different marks {@code verdict=struct_bad}.
 * Nothing is ever repaired here — fixing a bad page is the user-driven
 * long-press refresh in the reader. The scan is deliberately slow-friendly:
 * one page file at a time with a small sleep between files, on a
 * minimum-priority thread. When a scan completes, a system notification
 * summarizes it (checked / bad / missing counts; the alarming content text
 * only when bad pages were found). Progress and the last report are kept in
 * memory and in the generic Settings KV, respectively.
 *
 * <p>Testability seams mirror the reference scheduler: {@link SettingsSource},
 * {@link BaselineStore} and {@link PageOpener} are injected fakes in unit
 * tests, while {@link #shouldRun}, {@link #cooldownOver} and
 * {@link #scanGallery} are the pure decision/comparison cores.
 */
public final class IntegrityCheckScheduler {

    private static final String TAG = "IntegrityCheck";

    /** Notification channel id; registered lazily, project-standard pattern. */
    public static final String CHANNEL_ID = "integrity_check";
    /** Notification id ("ICC"); distinct from the other services' ids. */
    private static final int NOTIFICATION_ID = 0x494343;

    /** How often the periodic leg re-evaluates the gate. */
    static final long PERIODIC_INTERVAL_MS = 60L * 60L * 1000L; // 1 h
    /** Minimum time between scan starts, so network flaps do not rehash everything. */
    static final long MIN_SCAN_INTERVAL_MS = 6L * 60L * 60L * 1000L; // 6 h
    /** Small pause between page files: stay polite to the storage. */
    static final long PAGE_SLEEP_MS = 80L;

    /**
     * Generic Settings KV keys (the generic {@code getLong}/{@code getString}
     * accessors need no dedicated typed key): last scan start, for the
     * cooldown, and the last report summary.
     */
    static final String KEY_LAST_SCAN_AT = "integrity_last_scan_at";
    static final String KEY_LAST_RESULT = "integrity_last_result";

    /**
     * Testability seam: the settings the scheduler reads/writes. Production
     * wraps {@link Settings}; tests inject a fake.
     */
    public interface SettingsSource {
        boolean enabled();
        long lastScanAt();
        void setLastScanAt(long at);
        @Nullable
        String lastResult();
        void setLastResult(String value);
    }

    /**
     * Testability seam: the (gid, page) baseline rows to compare against.
     * Production delegates to the static {@link PageHashStore}; tests inject
     * an in-memory collector.
     */
    public interface BaselineStore {
        /** The gallery's baselines, page ascending; empty when none. */
        @NonNull
        List<? extends Baseline> queryByGallery(long gid);
        void markVerified(long gid, int page, long at);
        void markBad(long gid, int page, String verdict);
    }

    /** One baseline row to verify: page number and expected lowercase hash. */
    public interface Baseline {
        int page();
        String hash();
    }

    /**
     * Testability seam: opens one page file of a single gallery's download
     * directory. Returns {@code null} when the page has no file on disk
     * (sparse download — nothing to verify, baseline left untouched); throws
     * {@link IOException} when the file exists but cannot be read.
     */
    public interface PageOpener {
        @Nullable
        InputStream open(int page) throws IOException;
    }

    /** Abortion check between page files (own interface: no java.util.function on API 23). */
    public interface KeepGoing {
        boolean keepGoing();
    }

    /** Per-gallery scan outcome. */
    public static final class GalleryReport {
        public final long gid;
        public final int checked;
        public final int bad;
        public final int missing;
        public final int errors;

        GalleryReport(long gid, int checked, int bad, int missing, int errors) {
            this.gid = gid;
            this.checked = checked;
            this.bad = bad;
            this.missing = missing;
            this.errors = errors;
        }
    }

    /** One scan cycle's outcome, kept in memory and summarized into KV. */
    public static final class ScanReport {
        public final long startedAt;
        public final long finishedAt;
        /** Galleries actually scanned; skipped ones (no dir / no baselines) are not here. */
        @NonNull
        public final List<GalleryReport> galleries;
        public final int skippedGalleries;

        ScanReport(long startedAt, long finishedAt,
                   @NonNull List<GalleryReport> galleries, int skippedGalleries) {
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.galleries = galleries;
            this.skippedGalleries = skippedGalleries;
        }

        public int totalChecked() {
            int total = 0;
            for (GalleryReport report : galleries) total += report.checked;
            return total;
        }

        public int totalBad() {
            int total = 0;
            for (GalleryReport report : galleries) total += report.bad;
            return total;
        }

        public int totalMissing() {
            int total = 0;
            for (GalleryReport report : galleries) total += report.missing;
            return total;
        }

        public int totalErrors() {
            int total = 0;
            for (GalleryReport report : galleries) total += report.errors;
            return total;
        }

        /** The KV summary line: {@code at=...;galleries=...;checked=...;bad=...;missing=...;errors=...;skipped=...}. */
        @NonNull
        public String summarize() {
            return "at=" + finishedAt
                    + ";galleries=" + galleries.size()
                    + ";checked=" + totalChecked()
                    + ";bad=" + totalBad()
                    + ";missing=" + totalMissing()
                    + ";errors=" + totalErrors()
                    + ";skipped=" + skippedGalleries;
        }
    }

    // ==================== Pure decision cores (unit-tested) ====================

    /** The gate: the user switch, WiFi and charging must all hold. */
    static boolean shouldRun(boolean enabled, boolean wifi, boolean charging) {
        return enabled && wifi && charging;
    }

    /**
     * The cooldown: {@code true} when a scan may start — never scanned
     * ({@code lastScanAt == 0}) or the last scan started long enough ago.
     */
    static boolean cooldownOver(long now, long lastScanAt, long minIntervalMs) {
        return lastScanAt <= 0 || now - lastScanAt >= minIntervalMs;
    }

    /**
     * The pure compare loop for one gallery: for every baseline (page
     * ascending) read the page file through {@code opener}, hash it with
     * SHA-256, and stamp the verdict into {@code store}. A page with no file
     * on disk counts as {@code missing} and leaves the baseline untouched (a
     * sparse download is not corruption). A page whose bytes cannot be read
     * counts as {@code error} and also leaves the baseline untouched (a
     * transient storage failure must not be recorded as bit rot). Sleeps
     * {@code pageSleepMs} between files ({@code 0} disables, for tests) and
     * bails out politely when {@code keepGoing} turns false or the thread is
     * interrupted. Never repairs anything.
     */
    @NonNull
    public static GalleryReport scanGallery(long gid, @NonNull List<? extends Baseline> baselines,
            @NonNull PageOpener opener, @NonNull BaselineStore store,
            long pageSleepMs, long now, @NonNull KeepGoing keepGoing) {
        int checked = 0;
        int bad = 0;
        int missing = 0;
        int errors = 0;

        for (int i = 0; i < baselines.size(); i++) {
            if (!keepGoing.keepGoing()) {
                break;
            }
            Baseline baseline = baselines.get(i);
            if (i > 0 && pageSleepMs > 0) {
                try {
                    Thread.sleep(pageSleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            InputStream in;
            try {
                in = opener.open(baseline.page());
            } catch (IOException e) {
                errors++;
                continue;
            }
            if (in == null) {
                missing++;
                continue;
            }

            String actual;
            try {
                actual = sha256Hex(in);
            } catch (IOException e) {
                errors++;
                continue;
            }

            checked++;
            if (actual.equalsIgnoreCase(baseline.hash())) {
                store.markVerified(gid, baseline.page(), now);
            } else {
                store.markBad(gid, baseline.page(), PageHashStore.VERDICT_STRUCT_BAD);
                bad++;
            }
        }

        return new GalleryReport(gid, checked, bad, missing, errors);
    }

    /** Lowercase hex SHA-256 of a stream's bytes; consumes and closes it. */
    @NonNull
    private static String sha256Hex(@NonNull InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Every Java platform has SHA-256", e);
        }
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        char[] hex = new char[digest.getDigestLength() * 2];
        byte[] bytes = digest.digest();
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            hex[i * 2] = digits[(bytes[i] & 0xFF) >>> 4];
            hex[i * 2 + 1] = digits[bytes[i] & 0x0F];
        }
        return new String(hex);
    }

    // ==================== Production wiring ====================

    /**
     * The production {@link BaselineStore}: the static {@link PageHashStore}
     * rows mapped onto the {@link Baseline} seam (the dao class stays
     * untouched).
     */
    private static final BaselineStore PAGE_HASH_STORE_ADAPTER = new BaselineStore() {
        @NonNull
        @Override
        public List<? extends Baseline> queryByGallery(long gid) {
            return toBaselines(PageHashStore.queryByGallery(gid));
        }

        @Override
        public void markVerified(long gid, int page, long at) {
            PageHashStore.markVerified(gid, page, at);
        }

        @Override
        public void markBad(long gid, int page, String verdict) {
            PageHashStore.markBad(gid, page, verdict);
        }
    };

    /** Maps dao rows onto the pure {@link Baseline} seam. */
    @NonNull
    private static List<? extends Baseline> toBaselines(@NonNull List<PageHashStore.PageFileHash> rows) {
        List<Baseline> result = new ArrayList<>(rows.size());
        for (final PageHashStore.PageFileHash row : rows) {
            result.add(new Baseline() {
                @Override public int page() { return row.page; }
                @Override public String hash() { return row.hash; }
            });
        }
        return result;
    }

    private final Context appContext;
    private final SettingsSource settings;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "integrity-check");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean running;
    /** In-memory progress/last result for any future panel. */
    private volatile ScanReport lastReport;
    private volatile long progressGid = -1;
    private volatile int progressPage = -1;

    public IntegrityCheckScheduler(@NonNull Context context) {
        appContext = context.getApplicationContext();
        settings = wrap();
    }

    private SettingsSource wrap() {
        return new SettingsSource() {
            @Override public boolean enabled() { return Settings.getIntegrityCheckEnabled(); }
            @Override public long lastScanAt() { return Settings.getLong(KEY_LAST_SCAN_AT, 0L); }
            @Override public void setLastScanAt(long at) { Settings.putLong(KEY_LAST_SCAN_AT, at); }
            @Nullable
            @Override public String lastResult() { return Settings.getString(KEY_LAST_RESULT, null); }
            @Override public void setLastResult(String value) { Settings.putString(KEY_LAST_RESULT, value); }
        };
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        registerNetworkCallback();
        scheduleNextPeriodic();
    }

    public synchronized void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        unregisterNetworkCallback();
    }

    /** In-memory progress: the gallery currently being scanned, {@code -1} when idle. */
    public long progressGid() {
        return progressGid;
    }

    /** In-memory progress: the page currently being scanned, {@code -1} when idle. */
    public int progressPage() {
        return progressPage;
    }

    /** The last completed scan's report, {@code null} until the first one finishes. */
    @Nullable
    public ScanReport lastReport() {
        return lastReport;
    }

    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                trigger();
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {
            // Best effort: some OEMs throw on late registration.
            networkCallback = null;
        }
    }

    private void unregisterNetworkCallback() {
        if (networkCallback == null) return;
        ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
                // Already unregistered.
            }
        }
        networkCallback = null;
    }

    private void scheduleNextPeriodic() {
        handler.postDelayed(() -> {
            trigger();
            synchronized (IntegrityCheckScheduler.this) {
                if (running) scheduleNextPeriodic();
            }
        }, PERIODIC_INTERVAL_MS);
    }

    /**
     * Opportunistic trigger: evaluates the gate on the worker and runs one
     * full scan only when switch + WiFi + charging hold and the cooldown is
     * over; otherwise it is a no-op. Never runs two scans at once.
     */
    public void trigger() {
        executor.submit(this::runTriggerOnce);
    }

    private void runTriggerOnce() {
        if (!scanning.compareAndSet(false, true)) {
            return; // Reentrancy guard: one scan at a time, ever.
        }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }
        try {
            long now = System.currentTimeMillis();
            if (!shouldRun(settings.enabled(), isWifi(), isCharging())) return;
            if (!cooldownOver(now, settings.lastScanAt(), MIN_SCAN_INTERVAL_MS)) return;

            settings.setLastScanAt(now); // Stamp at start: a killed scan still counts.
            ScanReport report = scanAll(now);
            lastReport = report;
            settings.setLastResult(report.summarize());
            postSummaryNotification(report);
        } catch (Throwable t) {
            Log.w(TAG, "Integrity scan cycle failed", t);
        } finally {
            progressGid = -1;
            progressPage = -1;
            scanning.set(false);
        }
    }

    /**
     * Walks every local download gallery (SiteDB DOWNLOADS) and scans the ones
     * that both exist on disk and have baselines; stops politely once
     * {@link #stop()} ran or the gate dropped (checked at gallery boundaries).
     */
    @NonNull
    private ScanReport scanAll(long now) {
        long startedAt = System.currentTimeMillis();
        List<GalleryReport> reports = new ArrayList<>();
        int skipped = 0;

        List<com.hippo.anotherviewer.dao.DownloadInfo> infos;
        try {
            infos = com.hippo.anotherviewer.SiteDB.getAllDownloadInfo();
        } catch (Throwable t) {
            Log.w(TAG, "Cannot list downloads; scan aborted", t);
            return new ScanReport(startedAt, System.currentTimeMillis(), reports, skipped);
        }

        for (int i = 0; i < infos.size(); i++) {
            if (!running) break; // App shut down / scheduler stopped.
            if (!isWifi() || !isCharging()) break; // Gate dropped: graceful stop.

            final long gid = infos.get(i).gid;
            List<? extends Baseline> baselines;
            try {
                baselines = PAGE_HASH_STORE_ADAPTER.queryByGallery(gid);
            } catch (Throwable t) {
                Log.w(TAG, "Cannot read baselines for gid " + gid, t);
                skipped++;
                continue;
            }
            if (baselines.isEmpty()) {
                skipped++; // Nothing recorded: the scan compares, it does not backfill.
                continue;
            }
            UniFile dir = SpiderDen.getExistingGalleryDownloadDir(infos.get(i));
            if (dir == null) {
                skipped++; // Storage gone or never downloaded to a folder here.
                continue;
            }

            progressGid = gid;
            final UniFile galleryDir = dir;
            GalleryReport report = scanGallery(gid, baselines,
                    page -> {
                        progressPage = page;
                        UniFile file = findPageFile(galleryDir, page);
                        return file != null ? file.openInputStream() : null;
                    },
                    PAGE_HASH_STORE_ADAPTER,
                    PAGE_SLEEP_MS, now,
                    () -> running);
            reports.add(report);
        }

        return new ScanReport(startedAt, System.currentTimeMillis(), reports, skipped);
    }

    /**
     * One page file inside a download dir: the current 8-digit page name over
     * every supported image extension, then the legacy 4-digit name. Read-only
     * reuse of the SpiderDen naming rules.
     */
    @Nullable
    static UniFile findPageFile(@NonNull UniFile dir, int page) {
        for (String extension : GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            UniFile file = dir.findFile(SpiderDen.generateImageFilename(page, extension));
            if (file != null) {
                return file;
            }
        }
        for (String extension : GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            UniFile file = dir.findFile(String.format(Locale.US, "%04d%s", page + 1, extension));
            if (file != null) {
                return file;
            }
        }
        return null;
    }

    /** The gate probes: charging via the BatteryManager sticky broadcast. */
    private boolean isCharging() {
        try {
            Intent intent = appContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return false;
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The gate probes: WiFi (or Ethernet) via the active network capabilities. */
    private boolean isWifi() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The scan-completion summary notification. Channel registered lazily
     * (project-standard pattern); the alarming content text appears only when
     * bad pages were found.
     */
    private void postSummaryNotification(@NonNull ScanReport report) {
        NotificationManager nm =
                (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                        "Download integrity", NotificationManager.IMPORTANCE_LOW));
            }
            if (report.totalChecked() == 0 && report.totalBad() == 0) {
                return; // Nothing was actually verified; stay silent.
            }
            int bad = report.totalBad();
            String text = bad > 0
                    ? report.totalChecked() + " pages checked, " + bad
                            + " bad page(s) differ from their baseline — refresh the page in its reader to repair."
                    : report.totalChecked() + " pages checked, all match their baselines.";
            Intent intent = new Intent(appContext, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(appContext, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Download integrity check")
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIFICATION_ID, notification);
        } catch (Throwable t) {
            // Notification is best-effort; never fail the scan over it.
            Log.w(TAG, "Cannot post integrity summary notification", t);
        }
    }
}
