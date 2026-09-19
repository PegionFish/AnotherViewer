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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.hippo.anotherviewer.dao.DownloadInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * U3: the pull path of {@link WebUiSyncEngine} writes only the store, so the
 * running DownloadManager needs a notification to mirror applied rows into
 * memory. These tests pin the engine-side contract of the
 * {@link WebUiSyncEngine.DownloadRefreshSink}: which gids are reported after
 * apply (written rows and honored tombstones, never guard-kept local rows),
 * that a kept alive local row is untouched (object identity preserved), and
 * that a throwing sink never fails the sync cycle.
 */
@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner.class)
public class WebUiSyncDownloadRefreshTest {

    private WebUiConfig config;
    private InMemoryWebUiSyncStore storeA;
    private InMemoryWebUiSyncStore storeB;
    private InMemorySyncServer server;
    private WebUiSyncEngine engineA;
    private WebUiSyncEngine engineB;

    /** Records one report per syncInternal call (in call order). */
    private List<Set<Long>> reports;

    @Before
    public void setUp() {
        config = new WebUiConfig("http", "127.0.0.1", 8080, "user", "token");
        storeA = new InMemoryWebUiSyncStore();
        storeB = new InMemoryWebUiSyncStore();
        server = new InMemorySyncServer();
        engineA = new WebUiSyncEngine(storeA, server);
        engineB = new WebUiSyncEngine(storeB, server);
        reports = new ArrayList<>();
        WebUiSyncEngine.setDownloadRefreshSink(
                gids -> reports.add(new HashSet<>(gids)));
    }

    @After
    public void tearDown() {
        WebUiSyncEngine.setDownloadRefreshSink(null);
    }

    private static DownloadInfo download(long gid, long time, long lastModified, int state) {
        DownloadInfo info = new DownloadInfo();
        info.gid = gid;
        info.token = "tok" + gid;
        info.title = "download " + gid;
        info.state = state;
        info.legacy = 2;
        info.time = time;
        info.label = "default";
        info.total = 10;
        info.finished = 3;
        info.lastModified = lastModified;
        info.rated = true;
        info.simpleTags = new String[] {"x", "y"};
        info.pages = 10;
        return info;
    }

    @Test
    public void pullAppliedRow_isReportedToSink() throws IOException {
        storeA.putDownloadInfo(download(4, 4000, 9000, DownloadInfo.STATE_NONE));
        engineA.syncInternal(config, "devA", 0);
        // A's own cycle re-applies its self-echo and reports it; we only care
        // about B's genuine pull below.
        reports.clear();

        engineB.syncInternal(config, "devB", 0);

        assertEquals(1, reports.size());
        assertEquals(Collections.singleton(4L), reports.get(0));
        // The row really was applied to B's store.
        assertEquals(9000L, storeB.downloads.get(4L).lastModified);
    }

    @Test
    public void pullTombstone_isReportedToSink() throws IOException {
        storeB.putDownloadInfo(download(5, 4000, 9000, DownloadInfo.STATE_NONE));
        // Cycle 1 registers B's row on the server; the tombstone must land
        // above the resulting watermark to be pulled.
        WebUiSyncEngine.Result first = engineB.syncInternal(config, "devB", 0);
        reports.clear();

        WebUiSyncModels.SyncDownload tomb = new WebUiSyncModels.SyncDownload();
        tomb.gid = 5;
        tomb.token = "tok5";
        tomb.lastModified = 10_000;
        tomb.deviceId = "web-browser-1";
        tomb.deleted = true;
        server.downloads.put(5L, new InMemorySyncServer.Record(
                first.serverTimestamp + 1, true, tomb));

        engineB.syncInternal(config, "devB", first.serverTimestamp);

        assertEquals(1, reports.size());
        assertEquals(Collections.singleton(5L), reports.get(0));
        assertNull("honored tombstone removes the local row", storeB.downloads.get(5L));
    }

    @Test
    public void pull_noDownloadChanges_noSinkReport() throws IOException {
        // Nothing pulled at all: the sink must stay silent (no empty reports).
        engineB.syncInternal(config, "devB", 0);
        assertEquals(0, reports.size());
    }

    @Test
    public void aliveLocalRow_newerThanServer_keptUntouchedAndUnreported() throws IOException {
        DownloadInfo local = download(6, 4000, 20_000, DownloadInfo.STATE_DOWNLOAD);
        local.finished = 8;
        storeB.putDownloadInfo(local);
        // Cycle 1: B pushes its newer row (and adopts the ledger entry).
        WebUiSyncEngine.Result first = engineB.syncInternal(config, "devB", 0);
        reports.clear();

        // A stale snapshot from another device lands above the watermark:
        // older stamp, lower progress, state regressed to NONE.
        WebUiSyncModels.SyncDownload stale = new WebUiSyncModels.SyncDownload();
        stale.gid = 6;
        stale.token = "tok6";
        stale.title = "stale snapshot";
        stale.lastModified = 9_000;
        stale.time = 4000;
        stale.state = DownloadInfo.STATE_NONE;
        stale.finished = 3;
        stale.total = 10;
        stale.deviceId = "web-browser-1";
        server.downloads.put(6L, new InMemorySyncServer.Record(
                first.serverTimestamp + 1, false, stale));

        engineB.syncInternal(config, "devB", first.serverTimestamp);

        // The alive local row survived verbatim — same object (no rewrite),
        // same stamp, progress and running state.
        assertSame("guard-kept row must not be rewritten", local, storeB.downloads.get(6L));
        assertEquals(20_000L, storeB.downloads.get(6L).lastModified);
        assertEquals(8, storeB.downloads.get(6L).finished);
        assertEquals(DownloadInfo.STATE_DOWNLOAD, storeB.downloads.get(6L).state);
        // Nothing was written, so the refresh sink stayed silent entirely.
        assertTrue("kept row must not be reported to the sink", reports.isEmpty());
    }

    @Test
    public void aliveLocalRow_olderThanServer_followsServerAndIsReported() throws IOException {
        DownloadInfo local = download(7, 4000, 1_000, DownloadInfo.STATE_DOWNLOAD);
        local.finished = 1;
        storeB.putDownloadInfo(local);
        // The server already holds a clearly newer copy (another device
        // progressed further); B's push loses the server-side LWW.
        WebUiSyncModels.SyncDownload fresh = new WebUiSyncModels.SyncDownload();
        fresh.gid = 7;
        fresh.token = "tok7";
        fresh.title = "download 7";
        fresh.lastModified = 9_000;
        fresh.time = 4000;
        fresh.state = DownloadInfo.STATE_NONE;
        fresh.finished = 9;
        fresh.total = 10;
        fresh.deviceId = "web-browser-1";
        server.downloads.put(7L, new InMemorySyncServer.Record(1, false, fresh));

        engineB.syncInternal(config, "devB", 0);

        // Local row was behind: it follows the server...
        assertEquals(9_000L, storeB.downloads.get(7L).lastModified);
        assertEquals(9, storeB.downloads.get(7L).finished);
        assertEquals(DownloadInfo.STATE_NONE, storeB.downloads.get(7L).state);
        // ...and the manager is told to mirror it.
        assertEquals(1, reports.size());
        assertEquals(Collections.singleton(7L), reports.get(0));
    }

    @Test
    public void throwingSink_doesNotFailTheSync() throws IOException {
        WebUiSyncEngine.setDownloadRefreshSink(gids -> {
            throw new RuntimeException("broken listener");
        });
        storeA.putDownloadInfo(download(8, 4000, 9000, DownloadInfo.STATE_NONE));
        engineA.syncInternal(config, "devA", 0);

        WebUiSyncEngine.Result result = engineB.syncInternal(config, "devB", 0);

        // The store apply still happened; the cycle completed normally.
        assertEquals(1, result.pulledDownloads);
        assertEquals(9000L, storeB.downloads.get(8L).lastModified);
        assertFalse(server.rejectPushes);
    }

    @Test
    public void unwiredSink_syncStillWorks() throws IOException {
        WebUiSyncEngine.setDownloadRefreshSink(null);
        storeA.putDownloadInfo(download(9, 4000, 9000, DownloadInfo.STATE_NONE));
        engineA.syncInternal(config, "devA", 0);

        WebUiSyncEngine.Result result = engineB.syncInternal(config, "devB", 0);

        assertEquals(1, result.pulledDownloads);
        assertNotNull(storeB.downloads.get(9L));
    }
}
