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

package com.hippo.anotherviewer.spider;

import androidx.annotation.Nullable;

import com.hippo.anotherviewer.util.VGate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * One page-file write's integrity bookkeeping (Wave 2, plan 2026-09-19 A3):
 * tee every written byte into SHA-256 while {@link SpiderQueen} downloads a
 * page, then after the write completes either record the file's baseline or
 * reject it. Pure {@code java.io}/{@code java.security} plus
 * {@link VGate} — no Android types — so the whole flow is plain-JVM testable
 * ({@code PageHashRecorderTest}, fixtures under {@code /integrity/}).
 *
 * <p>Lifecycle, one instance per download attempt, never reused across
 * attempts (a failed attempt leaves the digest half-fed):
 * <ol>
 *   <li>{@link #sha256()} — create;</li>
 *   <li>{@link #tee(OutputStream)} — wrap the page file's output stream, the
 *       download loop writes through it as before;</li>
 *   <li>{@link #verifyAndRecord(InputStream, BadFileRemover, long, int, String,
 *       String, BaselineWriter)} — with the file closed, V-gate it by reading
 *       the bytes back (always through {@link VGate}, never a local
 *       re-implementation), then on accept hand the baseline row to the
 *       writer, on reject remove the file and record nothing.</li>
 * </ol>
 *
 * <p>{@code origin} selects the baseline's origin column and doubles as the
 * "should this write get a baseline at all" flag: the side table
 * ({@code PageHashStore}) describes download-dir page files, so read-mode
 * writes that only landed in the read cache pass {@code null} — they are
 * still V-gated, just not recorded.
 */
public final class PageHashRecorder {

    /** Sink for one baseline row; production impl is {@code PageHashStore::upsert}. */
    public interface BaselineWriter {
        void upsert(long gid, int page, String ext, long size, String hash, String origin);
    }

    /** Deletes the just-written bad file; production impl is {@code SpiderDen.remove(index)}. */
    public interface BadFileRemover {
        void removeFile();
    }

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private final MessageDigest mDigest;
    private long mCount;
    /** Memoized: {@code MessageDigest.digest()} finalizes and resets the engine. */
    private String mHashHex;

    private PageHashRecorder(MessageDigest digest) {
        mDigest = digest;
    }

    /** A fresh SHA-256 recorder with a zero byte count. */
    public static PageHashRecorder sha256() {
        try {
            return new PageHashRecorder(MessageDigest.getInstance("SHA-256"));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Every Java platform has SHA-256", e);
        }
    }

    /**
     * Wraps {@code target} so every byte that reaches it is hashed and
     * counted. Exceptions from {@code target} propagate unchanged and the
     * count only grows by bytes the target accepted — after a write failure
     * the recorder's state is unusable; drop it, the caller cleans the file
     * up through the download failure path.
     */
    public OutputStream tee(OutputStream target) {
        return new TeeOutputStream(target);
    }

    /** Bytes written so far; equals the page file's size once the write completed. */
    public long count() {
        return mCount;
    }

    /**
     * Lowercase hex SHA-256 of the bytes written so far. The value freezes on
     * first call (finalizing a digest resets it), so only call this after the
     * write phase is over.
     */
    public String hashHex() {
        if (mHashHex == null) {
            byte[] digest = mDigest.digest();
            char[] hex = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int b = digest[i] & 0xFF;
                hex[i * 2] = HEX_DIGITS[b >>> 4];
                hex[i * 2 + 1] = HEX_DIGITS[b & 0x0F];
            }
            mHashHex = new String(hex);
        }
        return mHashHex;
    }

    /**
     * V-gates the written file and, when it passes and {@code origin} is
     * non-null, records its baseline.
     *
     * @param readBack a stream of the just-written file's bytes (the V gate
     *                 consumes it to EOF and leaves it open)
     * @param remover  deletes the file when the V gate rejects it; it must
     *                 remove every copy (download dir and read cache) so no
     *                 half-written file survives the rejection
     * @param ext      file extension, with or without the leading dot; stored
     *                 dotless lowercase
     * @param origin   one of {@code PageHashStore.ORIGIN_*}, or {@code null}
     *                 to gate without recording (read-cache-only write)
     * @return true when the file is accepted (baseline recorded unless
     * {@code origin} is null); false when rejected — the file is gone, the
     * caller applies the download failure semantics and no baseline exists
     * @throws IOException when reading the file back fails; the caller treats
     *                     it like any other download IO failure
     */
    public boolean verifyAndRecord(InputStream readBack, BadFileRemover remover,
            long gid, int page, String ext, @Nullable String origin, BaselineWriter writer)
            throws IOException {
        // The V gate runs on the file's bytes, not on the tee'd count: the two
        // agree only if the target stored everything it accepted, and the file
        // on disk is what later readers (and the integrity scan) will see.
        VGate.Result result = VGate.check(readBack);
        if (!result.accepted) {
            remover.removeFile();
            return false;
        }
        if (origin != null) {
            writer.upsert(gid, page, normalizeExtension(ext), mCount, hashHex(), origin);
        }
        return true;
    }

    /** {@code ".JPG"} → {@code "jpg"}; a bare {@code "jpg"} passes through. */
    static String normalizeExtension(String ext) {
        String normalized = ext;
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return normalized.toLowerCase(Locale.US);
    }

    private final class TeeOutputStream extends DigestOutputStream {

        private TeeOutputStream(OutputStream target) {
            super(target, mDigest);
        }

        @Override
        public void write(int b) throws IOException {
            super.write(b);
            mCount++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
            mCount += len;
        }
    }
}
