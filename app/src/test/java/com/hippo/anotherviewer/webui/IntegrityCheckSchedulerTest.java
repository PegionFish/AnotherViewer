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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.anotherviewer.dao.PageHashStore;
import com.hippo.unifile.UniFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for the {@link IntegrityCheckScheduler} decision logic (plan
 * 2026-09-19 A4): the gate combination ({@code shouldRun}), the scan
 * cooldown ({@code cooldownOver}), the per-gallery compare loop
 * ({@code scanGallery} with an in-memory {@link IntegrityCheckScheduler.BaselineStore}
 * and temp-dir page files), and the SpiderDen page-name resolution
 * ({@code findPageFile}, 8-digit and legacy 4-digit names). No Android
 * storage, network or database involved.
 */
@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner.class)
public class IntegrityCheckSchedulerTest {

    private static final long NOW = 1_700_000_000_000L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    // ==================== fixtures ====================

    /** A fixed-row {@link Baseline} record. */
    private static IntegrityCheckScheduler.Baseline baseline(final int page, final String hash) {
        return new IntegrityCheckScheduler.Baseline() {
            @Override public int page() { return page; }
            @Override public String hash() { return hash; }
        };
    }

    /** In-memory {@link BaselineStore} recording every verdict it is handed. */
    private static final class FakeStore implements IntegrityCheckScheduler.BaselineStore {
        final List<String> ops = new ArrayList<>();

        @NonNull
        @Override
        public List<? extends IntegrityCheckScheduler.Baseline> queryByGallery(long gid) {
            return new ArrayList<>();
        }

        @Override
        public void markVerified(long gid, int page, long at) {
            ops.add("ok:" + gid + ":" + page + "@" + at);
        }

        @Override
        public void markBad(long gid, int page, String verdict) {
            ops.add(verdict + ":" + gid + ":" + page);
        }
    }

    /** Page files backed by in-memory bytes; {@code throwOnOpen} simulates IO failure. */
    private static final class FakeOpener implements IntegrityCheckScheduler.PageOpener {
        final Map<Integer, byte[]> pages = new HashMap<>();
        boolean throwOnOpen = false;

        @Nullable
        @Override
        public InputStream open(int page) throws IOException {
            if (throwOnOpen) throw new IOException("storage stalled");
            byte[] bytes = pages.get(page);
            return bytes != null ? new ByteArrayInputStream(bytes) : null;
        }
    }

    private static final IntegrityCheckScheduler.KeepGoing GO =
            () -> true;

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder builder = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.US_ASCII);
    }

    // ==================== shouldRun: the gate ====================

    @Test
    public void gate_requiresAllThreeConditions() {
        // All eight combinations: only enabled + wifi + charging passes.
        assertTrue(IntegrityCheckScheduler.shouldRun(true, true, true));
        assertFalse("user switch off must gate the scan off",
                IntegrityCheckScheduler.shouldRun(false, true, true));
        assertFalse("no WiFi must gate the scan off",
                IntegrityCheckScheduler.shouldRun(true, false, true));
        assertFalse("not charging must gate the scan off",
                IntegrityCheckScheduler.shouldRun(true, true, false));
        assertFalse(IntegrityCheckScheduler.shouldRun(false, false, true));
        assertFalse(IntegrityCheckScheduler.shouldRun(false, true, false));
        assertFalse(IntegrityCheckScheduler.shouldRun(true, false, false));
        assertFalse(IntegrityCheckScheduler.shouldRun(false, false, false));
    }

    // ==================== cooldownOver ====================

    @Test
    public void cooldown_neverScannedIsOver() {
        assertTrue(IntegrityCheckScheduler.cooldownOver(NOW, 0L,
                IntegrityCheckScheduler.MIN_SCAN_INTERVAL_MS));
    }

    @Test
    public void cooldown_recentScanBlocks() {
        assertFalse("a scan 1h ago must be blocked by the 6h cooldown",
                IntegrityCheckScheduler.cooldownOver(NOW, NOW - 3600_000L,
                        IntegrityCheckScheduler.MIN_SCAN_INTERVAL_MS));
    }

    @Test
    public void cooldown_oldScanAllows() {
        assertTrue("a scan 7h ago may scan again",
                IntegrityCheckScheduler.cooldownOver(NOW, NOW - 7 * 3600_000L,
                        IntegrityCheckScheduler.MIN_SCAN_INTERVAL_MS));
        assertTrue("exactly at the interval boundary the cooldown is over",
                IntegrityCheckScheduler.cooldownOver(NOW, NOW - IntegrityCheckScheduler.MIN_SCAN_INTERVAL_MS,
                        IntegrityCheckScheduler.MIN_SCAN_INTERVAL_MS));
    }

    // ==================== scanGallery: the compare loop ====================

    @Test
    public void scan_matchingPagesAreMarkedVerified() {
        FakeOpener opener = new FakeOpener();
        byte[] a = bytes("page-zero-bytes");
        byte[] b = bytes("page-one-bytes");
        opener.pages.put(0, a);
        opener.pages.put(1, b);
        FakeStore store = new FakeStore();

        IntegrityCheckScheduler.GalleryReport report = IntegrityCheckScheduler.scanGallery(
                42L, Arrays.asList(baseline(0, sha256Hex(a)), baseline(1, sha256Hex(b))),
                opener, store, 0, NOW, GO);

        assertEquals(2, report.checked);
        assertEquals(0, report.bad);
        assertEquals(0, report.missing);
        assertEquals(0, report.errors);
        assertEquals(Arrays.asList("ok:42:0@" + NOW, "ok:42:1@" + NOW), store.ops);
    }

    @Test
    public void scan_corruptedPageIsMarkedBadAndOthersStillVerified() {
        FakeOpener opener = new FakeOpener();
        byte[] good = bytes("intact-page");
        byte[] corrupt = bytes("truncated...");
        opener.pages.put(0, corrupt); // on-disk bytes differ from the baseline
        opener.pages.put(1, good);
        FakeStore store = new FakeStore();

        IntegrityCheckScheduler.GalleryReport report = IntegrityCheckScheduler.scanGallery(
                7L, Arrays.asList(baseline(0, sha256Hex(good)), baseline(1, sha256Hex(good))),
                opener, store, 0, NOW, GO);

        assertEquals(2, report.checked);
        assertEquals("only the diverging page counts as bad", 1, report.bad);
        assertEquals(PageHashStore.VERDICT_STRUCT_BAD + ":7:0", store.ops.get(0));
        assertEquals("the healthy page is still stamped ok",
                "ok:7:1@" + NOW, store.ops.get(1));
    }

    @Test
    public void scan_missingPageFileLeavesBaselineUntouched() {
        FakeOpener opener = new FakeOpener();
        opener.pages.put(1, bytes("only-page-one")); // page 0 never downloaded (sparse)
        FakeStore store = new FakeStore();

        IntegrityCheckScheduler.GalleryReport report = IntegrityCheckScheduler.scanGallery(
                9L, Arrays.asList(baseline(0, sha256Hex(bytes("missing"))), baseline(1, sha256Hex(bytes("only-page-one")))),
                opener, store, 0, NOW, GO);

        assertEquals(1, report.checked);
        assertEquals(1, report.missing);
        assertEquals(0, report.bad);
        assertEquals("a sparse download is not corruption: no verdict write",
                1, store.ops.size());
        assertEquals("ok:9:1@" + NOW, store.ops.get(0));
    }

    @Test
    public void scan_unreadablePageCountsAsErrorNotBad() {
        FakeOpener opener = new FakeOpener();
        opener.throwOnOpen = true;
        FakeStore store = new FakeStore();

        IntegrityCheckScheduler.GalleryReport report = IntegrityCheckScheduler.scanGallery(
                11L, Arrays.asList(baseline(0, sha256Hex(bytes("x")))),
                opener, store, 0, NOW, GO);

        assertEquals("a transient read failure must not be recorded as bit rot",
                1, report.errors);
        assertEquals(0, report.checked);
        assertEquals(0, report.bad);
        assertTrue("no verdict write for unreadable pages", store.ops.isEmpty());
    }

    @Test
    public void scan_uppercaseBaselineHashStillMatches() {
        FakeOpener opener = new FakeOpener();
        byte[] data = bytes("case-insensitive");
        opener.pages.put(0, data);
        FakeStore store = new FakeStore();

        IntegrityCheckScheduler.GalleryReport report = IntegrityCheckScheduler.scanGallery(
                3L, Arrays.asList(baseline(0, sha256Hex(data).toUpperCase(
                        java.util.Locale.US))),
                opener, store, 0, NOW, GO);

        assertEquals(1, report.checked);
        assertEquals(0, report.bad);
        assertEquals(1, store.ops.size());
    }

    @Test
    public void scan_keepGoingFalseStopsPolitely() {
        FakeOpener opener = new FakeOpener();
        byte[] data = bytes("same");
        opener.pages.put(0, data);
        opener.pages.put(1, data);
        FakeStore store = new FakeStore();

        IntegrityCheckScheduler.GalleryReport report = IntegrityCheckScheduler.scanGallery(
                5L, Arrays.asList(baseline(0, sha256Hex(data)), baseline(1, sha256Hex(data))),
                opener, store, 0, NOW,
                () -> false);

        assertEquals("the scan must stop before the first page when told to",
                0, report.checked);
        assertTrue(store.ops.isEmpty());
    }

    @Test
    public void scan_pageSleepOnlyBetweenFiles() {
        FakeOpener opener = new FakeOpener();
        byte[] data = bytes("paced");
        opener.pages.put(0, data);
        opener.pages.put(1, data);
        FakeStore store = new FakeStore();

        long started = System.nanoTime();
        IntegrityCheckScheduler.GalleryReport report = IntegrityCheckScheduler.scanGallery(6L,
                Arrays.asList(baseline(0, sha256Hex(data)), baseline(1, sha256Hex(data))),
                opener, store, IntegrityCheckScheduler.PAGE_SLEEP_MS, NOW, GO);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(2, report.checked);
        assertTrue("two files must pace one inter-file sleep (" + elapsedMs + "ms)",
                elapsedMs >= IntegrityCheckScheduler.PAGE_SLEEP_MS);
    }

    // ==================== ScanReport summary ====================

    @Test
    public void scanReport_summarizesTotalsAndRoundTripsThroughKvFormat() {
        List<IntegrityCheckScheduler.GalleryReport> galleries = Arrays.asList(
                new IntegrityCheckScheduler.GalleryReport(1L, 10, 2, 1, 0),
                new IntegrityCheckScheduler.GalleryReport(2L, 5, 0, 0, 1));
        IntegrityCheckScheduler.ScanReport report = new IntegrityCheckScheduler.ScanReport(
                NOW - 1000, NOW, galleries, 3);

        assertEquals(15, report.totalChecked());
        assertEquals(2, report.totalBad());
        assertEquals(1, report.totalMissing());
        assertEquals(1, report.totalErrors());
        assertEquals("at=" + NOW + ";galleries=2;checked=15;bad=2;missing=1;errors=1;skipped=3",
                report.summarize());
    }

    // ==================== findPageFile: SpiderDen naming rules ====================

    @Test
    public void findPageFile_acceptsCurrentEightDigitNames() throws IOException {
        File dir = folder.newFolder("gallery-a");
        write(dir, "00000001.jpg", "one");
        write(dir, "00000002.png", "two");

        UniFile uniDir = UniFile.fromFile(dir);
        assertNotNull(uniDir);
        assertNotNull("8-digit jpg must be found",
                IntegrityCheckScheduler.findPageFile(uniDir, 0));
        assertNotNull("8-digit png must be found",
                IntegrityCheckScheduler.findPageFile(uniDir, 1));
        assertNull("a page with no file must resolve to null",
                IntegrityCheckScheduler.findPageFile(uniDir, 2));
    }

    @Test
    public void findPageFile_acceptsLegacyFourDigitNames() throws IOException {
        File dir = folder.newFolder("gallery-b");
        write(dir, "0001.webp", "legacy");
        write(dir, "0002.gif", "legacy-two");

        UniFile uniDir = UniFile.fromFile(dir);
        assertNotNull(uniDir);
        assertNotNull("legacy 4-digit webp must be found",
                IntegrityCheckScheduler.findPageFile(uniDir, 0));
        assertNotNull("legacy 4-digit gif must be found",
                IntegrityCheckScheduler.findPageFile(uniDir, 1));
    }

    private static void write(File dir, String name, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(bytes(content));
        }
    }
}
