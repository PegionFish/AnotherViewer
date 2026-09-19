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

package com.hippo.anotherviewer.dao;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Data access for the {@code page_file_hash} side table (SiteDB schema v10,
 * plan 2026-09-19 A1): the SHA-256 baseline of every page file this device
 * downloaded, healed or imported, read back by the integrity worker to detect
 * bit rot and truncation, and pushed to the server as peer evidence. Raw SQL
 * on purpose — the table is created by SiteDB itself (fresh installs via
 * {@code DBOpenHelper.onCreate}, upgrades via {@code upgradeDB} case 9) with
 * columns aligned to the server entity {@code PageFileHashEntity}; greenDAO
 * is untouched.
 *
 * <p>The store is wired to the live database by {@code SiteDB.initialize};
 * tests inject an in-memory database through {@link #init(SQLiteDatabase)}.
 * Methods are static and synchronized like SiteDB's (one shared connection);
 * the verification writers ({@link #markVerified}, {@link #markBad}) simply
 * match nothing when the baseline row is absent.
 *
 * <p>Column contract (server-aligned):
 * <ul>
 *   <li>{@code gid} / {@code page} — composite PK; page is 0-based, the same
 *       scale as download_info and the server;</li>
 *   <li>{@code hash} — lowercase 64-hex SHA-256 of the file bytes;</li>
 *   <li>{@code algo} — {@code sha256} (reserved for future algorithms);</li>
 *   <li>{@code origin} — {@code downloader | read_sync | heal | import};</li>
 *   <li>{@code created_at} — baseline write time, epoch millis;</li>
 *   <li>{@code last_verified_at} — last scan time, epoch millis, unset = never;</li>
 *   <li>{@code verdict} — {@code ok | struct_bad}, null = never scanned.</li>
 * </ul>
 */
public final class PageHashStore {

    public static final String TABLE = "page_file_hash";

    /**
     * Tiny key/value side table for the peer-evidence push ledger (Wave 3 A5):
     * the dirty-gid set and the one-shot backfill queue, persisted so the
     * push survives process death. Created idempotently by {@link #init} —
     * deliberately outside greenDAO and outside the DBOpenHelper upgrade
     * cascade, exactly like {@link #TABLE} itself.
     */
    public static final String LEDGER_TABLE = "integrity_ledger";

    /** Ledger key: compact gid set whose baselines changed since the last accepted push. */
    private static final String KEY_DIRTY_GIDS = "integrity.dirty.gids";
    /** Ledger key: compact gid queue of the one-shot baseline backfill (drains ≤50/cycle). */
    private static final String KEY_BACKFILL_QUEUE = "integrity.backfill.queue";
    /** Ledger key: "1" once the backfill queue was built (the "全集已排队" marker). */
    private static final String KEY_BACKFILL_STARTED = "integrity.backfill.started";

    /** Baseline written by the downloader (page write hook). */
    public static final String ORIGIN_DOWNLOADER = "downloader";
    /** Baseline written while streaming a page during reading (read-sync). */
    public static final String ORIGIN_READ_SYNC = "read_sync";
    /** Baseline rewritten by the heal path (page refreshed from the source). */
    public static final String ORIGIN_HEAL = "heal";
    /** Baseline written by a bulk backfill over already-downloaded files. */
    public static final String ORIGIN_IMPORT = "import";

    /** Scan passed: file bytes still match the baseline. */
    public static final String VERDICT_OK = "ok";
    /** Scan failed (after re-read): file bytes differ from the baseline. */
    public static final String VERDICT_STRUCT_BAD = "struct_bad";

    /** Fixed hash algorithm until a migration changes it (server: algo='sha256'). */
    private static final String ALGO = "sha256";

    /** Selected columns in {@link PageFileHash} field order. */
    private static final String COLUMNS =
            "gid, page, ext, size, hash, algo, origin, created_at, last_verified_at, verdict";

    /** The single shared connection, injected by SiteDB.initialize / tests. */
    private static volatile SQLiteDatabase sDb;

    private PageHashStore() {
    }

    /**
     * Wires the store to the database SiteDB opened. Last call wins; every
     * other method throws {@link IllegalStateException} until this is called.
     * Also creates the {@link #LEDGER_TABLE} key/value side table if missing
     * ({@code IF NOT EXISTS}: re-inits and re-imports are harmless).
     */
    public static void init(@NonNull SQLiteDatabase db) {
        sDb = db;
        db.execSQL("CREATE TABLE IF NOT EXISTS \"" + LEDGER_TABLE + "\" (" +
                "\"key\" TEXT PRIMARY KEY, \"value\" TEXT NOT NULL DEFAULT '');");
    }

    private static SQLiteDatabase db() {
        SQLiteDatabase db = sDb;
        if (db == null) {
            throw new IllegalStateException("PageHashStore used before SiteDB.initialize");
        }
        return db;
    }

    /** One {@code page_file_hash} row. */
    public static final class PageFileHash {
        public final long gid;
        public final int page;
        public final String ext;
        public final long size;
        public final String hash;
        public final String algo;
        public final String origin;
        public final long createdAt;
        /** {@code 0} = never verified. */
        public final long lastVerifiedAt;
        /** {@code null} = never scanned. */
        @Nullable
        public final String verdict;

        PageFileHash(long gid, int page, String ext, long size, String hash,
                     String algo, String origin, long createdAt,
                     long lastVerifiedAt, @Nullable String verdict) {
            this.gid = gid;
            this.page = page;
            this.ext = ext;
            this.size = size;
            this.hash = hash;
            this.algo = algo;
            this.origin = origin;
            this.createdAt = createdAt;
            this.lastVerifiedAt = lastVerifiedAt;
            this.verdict = verdict;
        }
    }

    /**
     * Records (or fully replaces) the baseline of one page file. A same
     * (gid, page) row is overwritten: new bytes are a new baseline, so the
     * previous scan state is reset ({@code created_at} restarted,
     * {@code last_verified_at}/verdict back to unset).
     *
     * @param ext    file extension without the dot, e.g. {@code jpg} (required
     *               — the heal path rebuilds source URLs from it)
     * @param hash   64 hex chars, any case; stored lowercase
     * @param origin one of the {@code ORIGIN_*} constants
     */
    public static synchronized void upsert(long gid, int page, @NonNull String ext,
            long size, @NonNull String hash, @NonNull String origin) {
        db().execSQL("INSERT OR REPLACE INTO " + TABLE + " (" + COLUMNS + ") " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)",
                new Object[]{gid, page, ext, size, normalizeHash(hash), ALGO, origin,
                        System.currentTimeMillis()});
        // Wave 3 A5: the gallery's peer evidence is now stale on the server —
        // mark the gid so the next sync cycle re-pushes the whole gallery
        // (same monitor, re-entrant).
        markDirty(gid);
    }

    /** The page's baseline row, or {@code null} when never recorded. */
    @Nullable
    public static synchronized PageFileHash queryPage(long gid, int page) {
        Cursor cursor = db().rawQuery("SELECT " + COLUMNS + " FROM " + TABLE +
                " WHERE gid = ? AND page = ?",
                new String[]{String.valueOf(gid), String.valueOf(page)});
        try {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    /** All baselines of one gallery, page ascending; empty when none. */
    @NonNull
    public static synchronized List<PageFileHash> queryByGallery(long gid) {
        List<PageFileHash> result = new ArrayList<>();
        Cursor cursor = db().rawQuery("SELECT " + COLUMNS + " FROM " + TABLE +
                " WHERE gid = ? ORDER BY page ASC", new String[]{String.valueOf(gid)});
        try {
            while (cursor.moveToNext()) {
                result.add(fromCursor(cursor));
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    /**
     * Scan verdict "matches the baseline": sets {@code verdict = ok} and
     * {@code last_verified_at = lastVerifiedAt}. No-op when the row is absent.
     */
    public static synchronized void markVerified(long gid, int page, long lastVerifiedAt) {
        db().execSQL("UPDATE " + TABLE + " SET verdict = ?, last_verified_at = ? " +
                        "WHERE gid = ? AND page = ?",
                new Object[]{VERDICT_OK, lastVerifiedAt, gid, page});
    }

    /**
     * Scan verdict "differs from the baseline" (normally
     * {@code verdict = struct_bad}). {@code last_verified_at} is stamped with
     * the current time because the scan pass did run. No-op when the row is
     * absent.
     */
    public static synchronized void markBad(long gid, int page, @NonNull String verdict) {
        db().execSQL("UPDATE " + TABLE + " SET verdict = ?, last_verified_at = ? " +
                        "WHERE gid = ? AND page = ?",
                new Object[]{verdict, System.currentTimeMillis(), gid, page});
    }

    /** Drops every baseline of one gallery; returns the removed row count. */
    public static synchronized int deleteByGallery(long gid) {
        return db().delete(TABLE, "gid = ?", new String[]{String.valueOf(gid)});
    }

    // --- Peer-evidence push ledger (Wave 3 A5) ---
    // The WebUI sync engine pushes per-page hashes to the server as peer
    // evidence at the end of every successful sync cycle. Two bookkeeping
    // structures, both persisted in LEDGER_TABLE as compact comma-separated
    // gid sets so a push survives process death:
    //
    // - dirty set (integrity.dirty.gids): gids whose baselines changed since
    //   their last accepted push (markDirty from upsert); a gid is removed
    //   when the server accepts its evidence (or deterministically rejects it).
    // - one-shot backfill queue (integrity.backfill.queue): on the first run
    //   ever ("已上送全集" marker integrity.backfill.started absent) every gid
    //   that already has baselines is enqueued, so pre-existing downloads are
    //   backfilled in chunks; the engine drains at most 50 gids per cycle,
    //   dropping entries only after the push settled.

    /**
     * Marks a gallery's peer evidence as stale on the server. Called by
     * {@link #upsert}; gids ≤ 0 (corrupt rows, like everywhere else in the
     * sync) are ignored. Adding an already-present gid is a no-op.
     */
    public static synchronized void markDirty(long gid) {
        if (gid <= 0L) {
            return;
        }
        Set<Long> dirty = parseGidSet(kvGet(KEY_DIRTY_GIDS));
        if (dirty.add(gid)) {
            kvPut(KEY_DIRTY_GIDS, joinGids(dirty));
        }
    }

    /** Snapshot of the dirty-gid set; empty when everything was pushed. */
    @NonNull
    public static synchronized Set<Long> loadDirtyGids() {
        return parseGidSet(kvGet(KEY_DIRTY_GIDS));
    }

    /**
     * Removes gids from the dirty set — after the server accepted their
     * evidence, or after a deterministic rejection (retrying identical
     * payloads can never succeed). Unknown gids are ignored.
     */
    public static synchronized void clearDirtyGids(@NonNull Collection<Long> gids) {
        if (gids.isEmpty()) {
            return;
        }
        Set<Long> dirty = parseGidSet(kvGet(KEY_DIRTY_GIDS));
        if (dirty.removeAll(gids)) {
            kvPut(KEY_DIRTY_GIDS, joinGids(dirty));
        }
    }

    /**
     * Pops the next backfill batch of at most {@code limit} gids (ascending).
     * On the first call ever it builds the queue from every gid that has
     * baselines right now; later baseline writes reach the server through the
     * dirty set instead. Queue entries stay until {@link #dropBackfill}
     * removes them — a cycle that pushes nothing leaves the queue untouched,
     * so a killed process never loses backfill work. An empty database simply
     * marks the backfill started with an empty queue.
     */
    @NonNull
    public static synchronized List<Long> takeBackfillBatch(int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        if (!"1".equals(kvGet(KEY_BACKFILL_STARTED))) {
            // Queue first, marker second: a crash in between re-enqueues the
            // same gids next run (harmless — pushes are server-side upserts) —
            // the reverse order would silently lose the whole backfill.
            kvPut(KEY_BACKFILL_QUEUE, joinGids(queryBaselineGids()));
            kvPut(KEY_BACKFILL_STARTED, "1");
        }
        List<Long> queue = new ArrayList<>(parseGidSet(kvGet(KEY_BACKFILL_QUEUE)));
        return queue.size() <= limit ? queue : new ArrayList<>(queue.subList(0, limit));
    }

    /**
     * Removes gids from the backfill queue — after their push settled
     * (accepted, or deterministically rejected). Failed-but-retryable gids
     * stay at the head and are re-taken next cycle.
     */
    public static synchronized void dropBackfill(@NonNull Collection<Long> gids) {
        if (gids.isEmpty()) {
            return;
        }
        Set<Long> queue = parseGidSet(kvGet(KEY_BACKFILL_QUEUE));
        if (queue.removeAll(gids)) {
            kvPut(KEY_BACKFILL_QUEUE, joinGids(queue));
        }
    }

    /** Every gid that has at least one baseline row, ascending. */
    @NonNull
    private static List<Long> queryBaselineGids() {
        List<Long> gids = new ArrayList<>();
        Cursor cursor = db().rawQuery(
                "SELECT DISTINCT gid FROM " + TABLE + " ORDER BY gid ASC", null);
        try {
            while (cursor.moveToNext()) {
                long gid = cursor.getLong(0);
                if (gid > 0L) {
                    gids.add(gid);
                }
            }
        } finally {
            cursor.close();
        }
        return gids;
    }

    private static void kvPut(@NonNull String key, @NonNull String value) {
        db().execSQL("INSERT OR REPLACE INTO \"" + LEDGER_TABLE + "\" (key, value) " +
                "VALUES (?, ?)", new Object[]{key, value});
    }

    @Nullable
    private static String kvGet(@NonNull String key) {
        Cursor cursor = db().rawQuery(
                "SELECT value FROM \"" + LEDGER_TABLE + "\" WHERE key = ?",
                new String[]{key});
        try {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        } finally {
            cursor.close();
        }
    }

    /** Comma-separated decimal gids → insertion-ordered set; junk tokens are skipped. */
    private static Set<Long> parseGidSet(@Nullable String value) {
        Set<Long> result = new LinkedHashSet<>();
        if (value == null || value.isEmpty()) {
            return result;
        }
        for (String token : value.split(",")) {
            try {
                long gid = Long.parseLong(token.trim());
                if (gid > 0L) {
                    result.add(gid);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    @NonNull
    private static String joinGids(Collection<Long> gids) {
        StringBuilder builder = new StringBuilder();
        for (Long gid : gids) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(gid);
        }
        return builder.toString();
    }

    private static PageFileHash fromCursor(Cursor cursor) {
        return new PageFileHash(
                cursor.getLong(0),
                cursor.getInt(1),
                cursor.getString(2),
                cursor.getLong(3),
                cursor.getString(4),
                cursor.getString(5),
                cursor.getString(6),
                cursor.getLong(7),
                cursor.getLong(8),
                cursor.isNull(9) ? null : cursor.getString(9));
    }

    private static String normalizeHash(String hash) {
        String normalized = hash.toLowerCase(Locale.US);
        if (normalized.length() != 64) {
            throw new IllegalArgumentException("hash must be 64 hex chars: " + hash);
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                throw new IllegalArgumentException("hash must be 64 hex chars: " + hash);
            }
        }
        return normalized;
    }
}
