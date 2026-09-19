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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.anotherviewer.dao.DownloadInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * U3 alive-row guard: branch matrix of the pure predicate
 * {@link WebUiSyncEngine#shouldKeepLocalDownload}, which decides whether a
 * locally alive download row survives a pulled server copy. Covers local
 * newer / server newer / equal stamps / null (0) stamps, the progress
 * tie-break, and every alive vs non-alive local state.
 */
@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner.class)
public class WebUiSyncDownloadGuardTest {

    private static final int[] ALIVE_STATES = {
            DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD, DownloadInfo.STATE_FINISH,
    };
    private static final int[] NON_ALIVE_STATES = {
            DownloadInfo.STATE_NONE, DownloadInfo.STATE_INVALID,
            DownloadInfo.STATE_FAILED, DownloadInfo.STATE_UPDATE,
    };

    @Test
    public void nonAliveLocalRow_alwaysFollowsServer() {
        // No in-flight work to protect: server wins even when the local stamp
        // is newer (old behaviour preserved).
        for (int state : NON_ALIVE_STATES) {
            assertFalse("state " + state + " must follow the server",
                    WebUiSyncEngine.shouldKeepLocalDownload(20_000, state, 8, 9_000, 3));
        }
    }

    @Test
    public void aliveLocalRow_newerThanServer_isKept() {
        for (int state : ALIVE_STATES) {
            assertTrue("state " + state + " must survive a stale server row",
                    WebUiSyncEngine.shouldKeepLocalDownload(20_000, state, 8, 9_000, 3));
        }
    }

    @Test
    public void aliveLocalRow_olderThanServer_followsServer() {
        for (int state : ALIVE_STATES) {
            assertFalse("state " + state + " must follow a newer server row",
                    WebUiSyncEngine.shouldKeepLocalDownload(1_000, state, 1, 9_000, 3));
        }
    }

    @Test
    public void equalStamps_equalProgress_keepsLocal() {
        // The self-push echo of a running download: nothing to change, and a
        // rewrite could regress in-memory progress.
        for (int state : ALIVE_STATES) {
            assertTrue("state " + state + " echo must be a no-op",
                    WebUiSyncEngine.shouldKeepLocalDownload(9_000, state, 3, 9_000, 3));
        }
    }

    @Test
    public void equalStamps_serverProgressHigher_followsServer() {
        for (int state : ALIVE_STATES) {
            assertFalse("state " + state + " must adopt higher server progress",
                    WebUiSyncEngine.shouldKeepLocalDownload(9_000, state, 1, 9_000, 3));
        }
    }

    @Test
    public void equalStamps_localProgressHigher_keepsLocal() {
        for (int state : ALIVE_STATES) {
            assertTrue("state " + state + " must keep its higher progress",
                    WebUiSyncEngine.shouldKeepLocalDownload(9_000, state, 5, 9_000, 3));
        }
    }

    @Test
    public void newerLocalStamp_losesAgainstHigherServerProgress() {
        // Progress decides: a locally "newer" row that actually downloaded
        // less than the server copy must not freeze the sync mid-gallery.
        assertFalse(WebUiSyncEngine.shouldKeepLocalDownload(
                20_000, DownloadInfo.STATE_DOWNLOAD, 2, 9_000, 7));
    }

    @Test
    public void zeroStamps_legacyRows() {
        // Null (0) vs null (0): an echo of an unstamped row — keep local.
        assertTrue(WebUiSyncEngine.shouldKeepLocalDownload(
                0, DownloadInfo.STATE_DOWNLOAD, 3, 0, 3));
        // Unstamped local row vs stamped server row: server wins.
        assertFalse(WebUiSyncEngine.shouldKeepLocalDownload(
                0, DownloadInfo.STATE_DOWNLOAD, 3, 5_000, 3));
        // Stamped local row vs unstamped server row: local wins.
        assertTrue(WebUiSyncEngine.shouldKeepLocalDownload(
                5_000, DownloadInfo.STATE_DOWNLOAD, 3, 0, 3));
    }

    @Test
    public void aliveStateClassification() {
        assertTrue(WebUiSyncEngine.isAliveDownloadState(DownloadInfo.STATE_WAIT));
        assertTrue(WebUiSyncEngine.isAliveDownloadState(DownloadInfo.STATE_DOWNLOAD));
        assertTrue(WebUiSyncEngine.isAliveDownloadState(DownloadInfo.STATE_FINISH));
        assertFalse(WebUiSyncEngine.isAliveDownloadState(DownloadInfo.STATE_NONE));
        assertFalse(WebUiSyncEngine.isAliveDownloadState(DownloadInfo.STATE_INVALID));
        assertFalse(WebUiSyncEngine.isAliveDownloadState(DownloadInfo.STATE_FAILED));
        assertFalse(WebUiSyncEngine.isAliveDownloadState(DownloadInfo.STATE_UPDATE));
    }
}
