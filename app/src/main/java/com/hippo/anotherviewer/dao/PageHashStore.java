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
import java.util.List;
import java.util.Locale;

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
     */
    public static void init(@NonNull SQLiteDatabase db) {
        sDb = db;
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
