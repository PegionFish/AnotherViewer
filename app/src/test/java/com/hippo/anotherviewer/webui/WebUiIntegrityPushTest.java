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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.anotherviewer.dao.PageHashStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * 下载文件完整性 Wave 3 A5：peer 证据上送链（同步周期末尾的
 * {@link WebUiSyncEngine#pushIntegrityEvidence}）。覆盖四个面：
 * <ul>
 *   <li>HTTP 层（真实 OkHttp 栈 + {@link LocalHttpServer}）——请求形状
 *       （POST /api/v1/integrity/hashes/{gid}、Bearer、JSON body）与三态
 *       结果分类（2xx=accepted，400/404=permanent，其余/断网=retryable）；</li>
 *   <li>账本（{@link PageHashStore} 的脏集合与一次性回填队列）——upsert
 *       打标、去重、成功清/失败留、50 本/周期上限、已启动标记；</li>
 *   <li>契约换算——page 0-based → 1-based、ext 去点号、hash 小写、
 *       algo 固定 SHA-256（存储值是 sha256，直接透传会被服务端 400）；</li>
 *   <li>周期编排（fake pusher 驱动完整 syncInternal）——成功清账、可重试
 *       失败留下周期、判定性拒绝终止重试、空基线画廊跳过、pusher 抛异常
 *       不打断同步主流程。</li>
 * </ul>
 */
@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner.class)
public class WebUiIntegrityPushTest {

    /** A valid 64-hex string alternating the two characters. */
    private static String hexHash(char a, char b) {
        StringBuilder builder = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            builder.append(i % 2 == 0 ? a : b);
        }
        return builder.toString();
    }

    /**
     * Fresh in-memory database with the {@code page_file_hash} side table
     * (exact mirror of SiteDB's v10 DDL — SiteDB's helper is package-private
     * and this test package cannot reach it). {@link PageHashStore#init}
     * adds the {@code integrity_ledger} KV table on top.
     */
    private static SQLiteDatabase newDb() {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE IF NOT EXISTS \"page_file_hash\" (" +
                "\"gid\" INTEGER NOT NULL ," +
                "\"page\" INTEGER NOT NULL ," +
                "\"ext\" TEXT NOT NULL DEFAULT '' ," +
                "\"size\" INTEGER NOT NULL DEFAULT 0 ," +
                "\"hash\" TEXT NOT NULL DEFAULT '' ," +
                "\"algo\" TEXT NOT NULL DEFAULT 'sha256' ," +
                "\"origin\" TEXT NOT NULL DEFAULT 'downloader' ," +
                "\"created_at\" INTEGER NOT NULL DEFAULT 0 ," +
                "\"last_verified_at\" INTEGER ," +
                "\"verdict\" TEXT," +
                "PRIMARY KEY (\"gid\", \"page\"));");
        PageHashStore.init(db);
        return db;
    }

    // ------------------------------------------------------------------
    // HTTP client: request shape + tri-state classification
    // ------------------------------------------------------------------

    private LocalHttpServer http;

    @After
    public void tearDownHttp() {
        if (http != null) {
            http.stop();
            http = null;
        }
        // Never leak a fake pusher into other test classes.
        WebUiSyncEngine.setIntegrityEvidencePusher(null);
    }

    @Test
    public void testPostIntegrityHashesRequestShapeAndAccepted() throws Exception {
        http = LocalHttpServer.start(request ->
                LocalHttpServer.Response.json(200, "{\"accepted\":2}"));
        WebUiConfig config = new WebUiConfig("http", http.host(), http.port(), "", "secret-token");

        WebUiUploadClient.IntegrityPushResult result =
                WebUiUploadClient.postIntegrityHashes(config, 42, "[{\"page\":1,\"ext\":\"jpg\"}]");

        assertEquals(WebUiUploadClient.IntegrityPushResult.ACCEPTED, result);
        http.awaitHandled();
        LocalHttpServer.Request request = http.lastRequest();
        assertEquals("POST", request.method);
        assertEquals("/api/v1/integrity/hashes/42", request.path);
        assertEquals("Bearer secret-token", request.header("authorization"));
        assertNotNull(request.header("content-type"));
        assertTrue(request.header("content-type").startsWith("application/json"));
        assertEquals("[{\"page\":1,\"ext\":\"jpg\"}]", request.bodyText());
    }

    @Test
    public void testPostIntegrityHashesClassifiesFailures() throws Exception {
        http = LocalHttpServer.start(request ->
                LocalHttpServer.Response.json(status, "{\"code\":\"X\"}"));
        WebUiConfig config = new WebUiConfig("http", http.host(), http.port(), "", "");

        status = 400; // INTEGRITY_INVALID_HASH / INTEGRITY_INVALID_PAGE / VALIDATION_ERROR
        assertEquals(WebUiUploadClient.IntegrityPushResult.PERMANENT_FAILURE,
                WebUiUploadClient.postIntegrityHashes(config, 1, "[]"));

        http.nextRequest();
        status = 404; // INTEGRITY_NOT_FOUND: the server has no download row for the gid
        assertEquals(WebUiUploadClient.IntegrityPushResult.PERMANENT_FAILURE,
                WebUiUploadClient.postIntegrityHashes(config, 1, "[]"));

        http.nextRequest();
        status = 500;
        assertEquals(WebUiUploadClient.IntegrityPushResult.RETRYABLE_FAILURE,
                WebUiUploadClient.postIntegrityHashes(config, 1, "[]"));

        http.nextRequest();
        status = 401; // transient here: the sync itself just authenticated fine
        assertEquals(WebUiUploadClient.IntegrityPushResult.RETRYABLE_FAILURE,
                WebUiUploadClient.postIntegrityHashes(config, 1, "[]"));
    }

    private volatile int status = 200;

    @Test
    public void testPostIntegrityHashesNetworkErrorIsRetryableNotThrow() {
        // A port with nothing listening: OkHttp gets ConnectException, the
        // client must swallow it and answer RETRYABLE (never throw).
        WebUiConfig config = new WebUiConfig("http", "127.0.0.1", 1, "", "");
        assertEquals(WebUiUploadClient.IntegrityPushResult.RETRYABLE_FAILURE,
                WebUiUploadClient.postIntegrityHashes(config, 7, "[]"));
    }

    // ------------------------------------------------------------------
    // Ledger (PageHashStore dirty set + one-shot backfill queue)
    // ------------------------------------------------------------------

    @Test
    public void testUpsertMarksDirtyDedupesAndClearRemoves() {
        PageHashStore.init(newDb());

        assertTrue(PageHashStore.loadDirtyGids().isEmpty());

        PageHashStore.upsert(1, 0, "jpg", 10L, hexHash('a', 'b'), PageHashStore.ORIGIN_DOWNLOADER);
        PageHashStore.upsert(1, 1, "jpg", 11L, hexHash('c', 'd'), PageHashStore.ORIGIN_DOWNLOADER);
        PageHashStore.upsert(2, 0, "png", 12L, hexHash('e', 'f'), PageHashStore.ORIGIN_HEAL);
        assertEquals(new HashSet<>(Arrays.asList(1L, 2L)), PageHashStore.loadDirtyGids());

        // Re-upserting the same page stays one dirty entry.
        PageHashStore.upsert(1, 1, "jpg", 13L, hexHash('a', 'b'), PageHashStore.ORIGIN_HEAL);
        assertEquals(new HashSet<>(Arrays.asList(1L, 2L)), PageHashStore.loadDirtyGids());

        // gid <= 0 (corrupt row) never enters the ledger.
        PageHashStore.upsert(0, 0, "jpg", 1L, hexHash('1', '2'), PageHashStore.ORIGIN_DOWNLOADER);
        assertEquals(new HashSet<>(Arrays.asList(1L, 2L)), PageHashStore.loadDirtyGids());

        // Successful push clears only the delivered gid.
        PageHashStore.clearDirtyGids(Collections.singleton(1L));
        assertEquals(Collections.singleton(2L), PageHashStore.loadDirtyGids());
    }

    @Test
    public void testBackfillQueueCapsAtFiftyPerCycleAndDrains() {
        PageHashStore.init(newDb());
        for (long gid = 1; gid <= 55; gid++) {
            PageHashStore.upsert(gid, 0, "jpg", 1L, hexHash('a', 'b'),
                    PageHashStore.ORIGIN_DOWNLOADER);
        }

        List<Long> first = PageHashStore.takeBackfillBatch(50);
        assertEquals(50, first.size());
        assertEquals(Long.valueOf(1L), first.get(0));
        assertEquals(Long.valueOf(50L), first.get(49));

        // Entries stay in the queue until the push settles: re-taking without
        // dropping yields the same head (a failed cycle retries, never skips).
        assertEquals(first, PageHashStore.takeBackfillBatch(50));

        PageHashStore.dropBackfill(first);
        List<Long> rest = PageHashStore.takeBackfillBatch(50);
        assertEquals(5, rest.size());
        assertEquals(Long.valueOf(51L), rest.get(0));

        PageHashStore.dropBackfill(rest);
        assertTrue(PageHashStore.takeBackfillBatch(50).isEmpty());

        // One-shot semantics: a baseline written after the queue was built
        // must not join the (already drained) queue — it flows through the
        // dirty set instead.
        PageHashStore.upsert(100, 0, "jpg", 1L, hexHash('c', 'd'), PageHashStore.ORIGIN_HEAL);
        assertTrue(PageHashStore.takeBackfillBatch(50).isEmpty());
        assertTrue(PageHashStore.loadDirtyGids().contains(100L));
    }

    @Test
    public void testBackfillOnEmptyDatabaseIsANoOp() {
        PageHashStore.init(newDb());
        assertTrue(PageHashStore.takeBackfillBatch(50).isEmpty());
        assertTrue(PageHashStore.takeBackfillBatch(50).isEmpty());
    }

    // ------------------------------------------------------------------
    // Contract conversion (0-based → 1-based, ext, hash, algo)
    // ------------------------------------------------------------------

    @Test
    public void testBuildIntegrityHashPayloadMatchesContract() {
        PageHashStore.init(newDb());
        String hashA = hexHash('a', 'b');
        String hashB = hexHash('c', 'd');
        // The recorder lowercases extensions; the store does not — a dotted
        // ext exercises the payload's dot-stripping, an uppercase input hash
        // the store's own lowercase normalization.
        PageHashStore.upsert(7, 0, "jpg", 1234L, hashA, PageHashStore.ORIGIN_DOWNLOADER);
        PageHashStore.upsert(7, 2, ".PNG", 5678L, hashB.toUpperCase(Locale.US),
                PageHashStore.ORIGIN_READ_SYNC);

        String json = WebUiSyncEngine.buildIntegrityHashPayload(PageHashStore.queryByGallery(7));

        JSONArray array = JSON.parseArray(json);
        assertEquals(2, array.size());
        JSONObject first = array.getJSONObject(0);
        assertEquals(1, first.getIntValue("page")); // 0-based local → 1-based wire
        assertEquals("jpg", first.getString("ext"));
        assertEquals(1234L, first.getLongValue("size"));
        assertEquals(hashA, first.getString("hash"));
        assertEquals("SHA-256", first.getString("algo"));

        JSONObject second = array.getJSONObject(1);
        assertEquals(3, second.getIntValue("page"));
        assertEquals("PNG", second.getString("ext"));
        assertEquals(5678L, second.getLongValue("size"));
        assertEquals(hashB, second.getString("hash"));
        assertEquals("SHA-256", second.getString("algo"));
    }

    // ------------------------------------------------------------------
    // Cycle orchestration (full syncInternal against the fake pusher)
    // ------------------------------------------------------------------

    /** Fake pusher: records calls, returns a scripted outcome (or throws). */
    private static final class RecordingPusher implements WebUiSyncEngine.IntegrityEvidencePusher {
        final List<Long> gids = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();
        volatile WebUiUploadClient.IntegrityPushResult nextResult =
                WebUiUploadClient.IntegrityPushResult.ACCEPTED;
        volatile RuntimeException throwOnPush;

        @NonNull
        @Override
        public WebUiUploadClient.IntegrityPushResult push(@NonNull WebUiConfig config,
                long gid, @NonNull String json) {
            RuntimeException e = throwOnPush;
            if (e != null) {
                throw e;
            }
            gids.add(gid);
            payloads.add(json);
            return nextResult;
        }
    }

    private InMemoryWebUiSyncStore store;
    private InMemorySyncServer server;
    private WebUiSyncEngine engine;
    private WebUiConfig config;
    private RecordingPusher pusher;

    @Before
    public void setUpEngine() {
        // Fresh in-memory database per test: the ledger lives in it.
        PageHashStore.init(newDb());
        config = new WebUiConfig("http", "127.0.0.1", 8080, "user", "token");
        store = new InMemoryWebUiSyncStore();
        server = new InMemorySyncServer();
        engine = new WebUiSyncEngine(store, server);
        pusher = new RecordingPusher();
        WebUiSyncEngine.setIntegrityEvidencePusher(pusher);
    }

    @Test
    public void testSyncPushesDirtyGalleryOneBasedAndClearsOnSuccess() throws IOException {
        PageHashStore.upsert(7, 0, "jpg", 100L, hexHash('a', 'b'), PageHashStore.ORIGIN_DOWNLOADER);
        PageHashStore.upsert(7, 1, "png", 200L, hexHash('c', 'd'), PageHashStore.ORIGIN_READ_SYNC);

        WebUiSyncEngine.Result result = engine.syncInternal(config, "android-test", 0L);

        assertNotNull(result);
        // The gid is both dirty and (first run) in the backfill queue — pushed once.
        assertEquals(Collections.singletonList(7L), pusher.gids);
        JSONArray array = JSON.parseArray(pusher.payloads.get(0));
        assertEquals(2, array.size());
        assertEquals(1, array.getJSONObject(0).getIntValue("page"));
        assertEquals(2, array.getJSONObject(1).getIntValue("page"));
        // Accepted: the dirty book closed; a second cycle has nothing to send.
        assertTrue(PageHashStore.loadDirtyGids().isEmpty());
        engine.syncInternal(config, "android-test", result.serverTimestamp);
        assertEquals(1, pusher.gids.size());
    }

    @Test
    public void testRetryableFailureKeepsLedgerForNextCycle() throws IOException {
        PageHashStore.upsert(7, 0, "jpg", 100L, hexHash('a', 'b'), PageHashStore.ORIGIN_DOWNLOADER);
        pusher.nextResult = WebUiUploadClient.IntegrityPushResult.RETRYABLE_FAILURE;

        WebUiSyncEngine.Result result = engine.syncInternal(config, "android-test", 0L);

        assertEquals(1, pusher.gids.size());
        assertEquals(Collections.singleton(7L), PageHashStore.loadDirtyGids());

        // Next cycle, the server accepts: delivered exactly once more.
        pusher.nextResult = WebUiUploadClient.IntegrityPushResult.ACCEPTED;
        engine.syncInternal(config, "android-test", result.serverTimestamp);
        assertEquals(2, pusher.gids.size());
        assertTrue(PageHashStore.loadDirtyGids().isEmpty());
    }

    @Test
    public void testPermanentFailureStopsRetrying() throws IOException {
        PageHashStore.upsert(7, 0, "jpg", 100L, hexHash('a', 'b'), PageHashStore.ORIGIN_DOWNLOADER);
        pusher.nextResult = WebUiUploadClient.IntegrityPushResult.PERMANENT_FAILURE;

        engine.syncInternal(config, "android-test", 0L);

        assertEquals(1, pusher.gids.size());
        // A deterministically rejected payload is never retried: book closed.
        assertTrue(PageHashStore.loadDirtyGids().isEmpty());
        engine.syncInternal(config, "android-test", 1L);
        assertEquals(1, pusher.gids.size());
    }

    @Test
    public void testBackfillPushesAtMostFiftyGalleriesPerCycle() throws IOException {
        // Simulate baselines that predate this feature (the backfill's whole
        // point): written without a live dirty mark — exactly the state of
        // rows created before the ledger existed.
        for (long gid = 1; gid <= 55; gid++) {
            PageHashStore.upsert(gid, 0, "jpg", 1L, hexHash('a', 'b'),
                    PageHashStore.ORIGIN_DOWNLOADER);
        }
        PageHashStore.clearDirtyGids(PageHashStore.loadDirtyGids());

        engine.syncInternal(config, "android-test", 0L);
        assertEquals(50, pusher.gids.size());
        assertEquals(Long.valueOf(1L), pusher.gids.get(0));
        assertEquals(Long.valueOf(50L), pusher.gids.get(49));

        pusher.gids.clear();
        engine.syncInternal(config, "android-test", 1L);
        assertEquals(5, pusher.gids.size());
        assertEquals(Long.valueOf(51L), pusher.gids.get(0));

        // Queue drained: the third cycle stays idle.
        pusher.gids.clear();
        engine.syncInternal(config, "android-test", 2L);
        assertEquals(0, pusher.gids.size());
    }

    @Test
    public void testEmptyBaselineGalleryIsSkippedAndDroppedFromLedger() throws IOException {
        // Baselines deleted after the gid was marked dirty: nothing to push,
        // and the stale entry must not spin in the ledger forever.
        PageHashStore.upsert(9, 0, "jpg", 100L, hexHash('a', 'b'), PageHashStore.ORIGIN_DOWNLOADER);
        assertEquals(1, PageHashStore.deleteByGallery(9));

        engine.syncInternal(config, "android-test", 0L);

        assertEquals(0, pusher.gids.size());
        assertTrue(PageHashStore.loadDirtyGids().isEmpty());
    }

    @Test
    public void testBrokenPusherNeverFailsTheSyncCycle() throws IOException {
        PageHashStore.upsert(7, 0, "jpg", 100L, hexHash('a', 'b'), PageHashStore.ORIGIN_DOWNLOADER);
        pusher.throwOnPush = new RuntimeException("boom");

        // Must complete normally: evidence is best-effort, the sync already
        // succeeded and was saved.
        WebUiSyncEngine.Result result = engine.syncInternal(config, "android-test", 0L);
        assertNotNull(result);
        assertEquals(0, pusher.gids.size());
        assertEquals(Collections.singleton(7L), PageHashStore.loadDirtyGids());

        // A healthy pusher delivers the retained evidence next cycle.
        pusher.throwOnPush = null;
        engine.syncInternal(config, "android-test", result.serverTimestamp);
        assertEquals(Collections.singletonList(7L), pusher.gids);
    }
}
