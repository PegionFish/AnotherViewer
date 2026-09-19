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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.hippo.anotherviewer.util.VGate;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The download hook's logic ({@link PageHashRecorder}) against the shared
 * integrity fixtures (classpath {@code /integrity/}, byte-identical to the
 * MANIFEST.sha256 caliber). Three paths SpiderQueen relies on: a valid page
 * file records its baseline, a truncated one is rejected with the file
 * removed and no baseline, a write failure propagates and records nothing.
 */
public class PageHashRecorderTest {

    /** Authoritative caliber: app/src/test/resources/integrity/MANIFEST.sha256. */
    private static final String VALID_JPG_SHA256 =
            "24ac74130806ae02d7e4ee72881b977601999c6c9f94ee1545ed4830459b737f";
    private static final int VALID_JPG_SIZE = 159;
    private static final int TRUNCATED_JPG_SIZE = 151;

    /** One captured baseline row. */
    private static final class Row {
        final long gid;
        final int page;
        final String ext;
        final long size;
        final String hash;
        final String origin;

        Row(long gid, int page, String ext, long size, String hash, String origin) {
            this.gid = gid;
            this.page = page;
            this.ext = ext;
            this.size = size;
            this.hash = hash;
            this.origin = origin;
        }
    }

    private static final class CapturingWriter implements PageHashRecorder.BaselineWriter {
        final List<Row> rows = new ArrayList<>();

        @Override
        public void upsert(long gid, int page, String ext, long size, String hash, String origin) {
            rows.add(new Row(gid, page, ext, size, hash, origin));
        }
    }

    private static final class CapturingRemover implements PageHashRecorder.BadFileRemover {
        int removed;

        @Override
        public void removeFile() {
            removed++;
        }
    }

    private static byte[] fixture(String name) throws IOException {
        InputStream in = PageHashRecorderTest.class.getResourceAsStream("/integrity/" + name);
        if (in == null) {
            throw new AssertionError("Missing fixture: " + name);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) != -1; ) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Writes {@code bytes} through the recorder's tee in odd-sized chunks. */
    private static ByteArrayOutputStream teeWrite(PageHashRecorder recorder, byte[] bytes)
            throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        OutputStream tee = recorder.tee(target);
        int offset = 0;
        int[] chunkSizes = {1, 17, 5, 64, 100, 3};
        for (int chunk : chunkSizes) {
            if (offset >= bytes.length) {
                break;
            }
            int len = Math.min(chunk, bytes.length - offset);
            tee.write(bytes, offset, len);
            offset += len;
        }
        if (offset < bytes.length) {
            tee.write(bytes, offset, bytes.length - offset);
        }
        tee.flush();
        return target;
    }

    // ── path 1: valid page file → accepted, baseline row correct ────────────

    @Test
    public void validJpg_isAccepted_baselineRowMatchesFile() throws IOException {
        byte[] valid = fixture("valid.jpg");
        assertEquals(VALID_JPG_SIZE, valid.length);

        PageHashRecorder recorder = PageHashRecorder.sha256();
        ByteArrayOutputStream file = teeWrite(recorder, valid);
        assertEquals("tee must pass every byte through unchanged",
                valid.length, file.size());
        assertEquals("tee must count the written bytes",
                (long) valid.length, recorder.count());
        assertEquals("tee's hash must be the file's SHA-256 (manifest caliber)",
                VALID_JPG_SHA256, recorder.hashHex());

        CapturingWriter writer = new CapturingWriter();
        CapturingRemover remover = new CapturingRemover();
        boolean accepted = recorder.verifyAndRecord(
                new ByteArrayInputStream(file.toByteArray()), remover,
                4242L, 7, ".jpg", "downloader", writer);

        assertTrue(accepted);
        assertEquals("a valid file must never be removed", 0, remover.removed);
        assertEquals(1, writer.rows.size());
        Row row = writer.rows.get(0);
        assertEquals(4242L, row.gid);
        assertEquals(7, row.page);
        assertEquals("extension stored without the dot", "jpg", row.ext);
        assertEquals((long) VALID_JPG_SIZE, row.size);
        assertEquals("baseline hash lowercase hex of the file bytes",
                VALID_JPG_SHA256, row.hash);
        assertEquals("downloader", row.origin);
    }

    @Test
    public void teeMatchesDirectSha256_onArbitraryBytes() throws IOException {
        byte[] bytes = new byte[100_000];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + i / 251);
        }
        PageHashRecorder recorder = PageHashRecorder.sha256();
        ByteArrayOutputStream file = teeWrite(recorder, bytes);
        java.security.MessageDigest direct;
        try {
            direct = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        String expected = recorder.hashHex(); // tee result
        byte[] directDigest = direct.digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : directDigest) {
            hex.append(String.format("%02x", b));
        }
        assertEquals(expected, hex.toString());
        assertArrayEquals(bytes, file.toByteArray());
    }

    // ── path 2: truncated page file → rejected, file removed, no baseline ──

    @Test
    public void truncatedJpg_isRejected_fileRemoved_noBaseline() throws IOException {
        byte[] truncated = fixture("truncated.jpg");
        assertEquals(TRUNCATED_JPG_SIZE, truncated.length);

        // Sanity: the fixture really is a V2 reject (contract §5)
        VGate.Result gate = VGate.check(new ByteArrayInputStream(truncated));
        assertFalse(gate.accepted);
        assertEquals(VGate.RejectReason.V2_TAIL, gate.reason);

        PageHashRecorder recorder = PageHashRecorder.sha256();
        ByteArrayOutputStream file = teeWrite(recorder, truncated);

        CapturingWriter writer = new CapturingWriter();
        CapturingRemover remover = new CapturingRemover();
        boolean accepted = recorder.verifyAndRecord(
                new ByteArrayInputStream(file.toByteArray()), remover,
                4242L, 7, ".jpg", "downloader", writer);

        assertFalse("truncated bytes must be rejected", accepted);
        assertEquals("the bad file must be removed", 1, remover.removed);
        assertTrue("no baseline for a rejected file", writer.rows.isEmpty());
    }

    @Test
    public void htmlDisguisedJpg_isRejected_fileRemoved_noBaseline() throws IOException {
        byte[] disguised = fixture("html_disguised.jpg");
        PageHashRecorder recorder = PageHashRecorder.sha256();
        ByteArrayOutputStream file = teeWrite(recorder, disguised);

        CapturingWriter writer = new CapturingWriter();
        CapturingRemover remover = new CapturingRemover();
        boolean accepted = recorder.verifyAndRecord(
                new ByteArrayInputStream(file.toByteArray()), remover,
                4242L, 0, ".jpg", "downloader", writer);

        assertFalse(accepted);
        assertEquals(1, remover.removed);
        assertTrue(writer.rows.isEmpty());
    }

    // ── path 3: write failure → propagates, nothing recorded, cleanup ──────

    @Test
    public void writeFailure_propagates_countStops_noBaseline() throws IOException {
        byte[] valid = fixture("valid.jpg");
        OutputStream failing = new OutputStream() {
            private int writes;

            @Override
            public void write(int b) throws IOException {
                if (writes++ >= 3) {
                    throw new IOException("ENOSPC: disk full");
                }
            }
        };

        PageHashRecorder recorder = PageHashRecorder.sha256();
        OutputStream tee = recorder.tee(failing);
        try {
            tee.write(valid, 0, 100);
            fail("the target's IOException must propagate to the download loop");
        } catch (IOException expected) {
            // SpiderQueen's existing IO failure handling takes over from here:
            // the attempt fails, the loop exit removes the partial file, and
            // no baseline is ever written from this recorder.
        }
        assertEquals("count only grows by bytes the target accepted", 0, recorder.count());

        // The poisoned recorder must not be reused into a baseline: verifying
        // whatever partial bytes exist cannot produce a row (SpiderQueen
        // creates a fresh recorder per attempt).
        CapturingWriter writer = new CapturingWriter();
        CapturingRemover remover = new CapturingRemover();
        boolean accepted = recorder.verifyAndRecord(
                new ByteArrayInputStream(valid, 0, 3), remover,
                1L, 0, ".jpg", "downloader", writer);
        assertFalse(accepted);
        assertEquals("partial file cleanup runs on the reject path too", 1, remover.removed);
        assertTrue(writer.rows.isEmpty());
    }

    // ── origin=null (read-cache-only write): gated, never recorded ──────────

    @Test
    public void nullOrigin_gatesWithoutBaseline() throws IOException {
        byte[] valid = fixture("valid.jpg");
        PageHashRecorder recorder = PageHashRecorder.sha256();
        ByteArrayOutputStream file = teeWrite(recorder, valid);

        CapturingWriter writer = new CapturingWriter();
        CapturingRemover remover = new CapturingRemover();
        boolean accepted = recorder.verifyAndRecord(
                new ByteArrayInputStream(file.toByteArray()), remover,
                4242L, 7, ".jpg", null, writer);

        assertTrue(accepted);
        assertEquals(0, remover.removed);
        assertTrue("cache-only writes get no baseline row", writer.rows.isEmpty());
    }

    @Test
    public void nullOrigin_rejectedTruncated_stillRemoves() throws IOException {
        byte[] truncated = fixture("truncated.jpg");
        PageHashRecorder recorder = PageHashRecorder.sha256();
        ByteArrayOutputStream file = teeWrite(recorder, truncated);

        CapturingWriter writer = new CapturingWriter();
        CapturingRemover remover = new CapturingRemover();
        boolean accepted = recorder.verifyAndRecord(
                new ByteArrayInputStream(file.toByteArray()), remover,
                4242L, 7, ".jpg", null, writer);

        assertFalse(accepted);
        assertEquals(1, remover.removed);
        assertTrue(writer.rows.isEmpty());
    }

    // ── small pieces ─────────────────────────────────────────────────────────

    @Test
    public void normalizeExtension_stripsDotAndLowercases() {
        assertEquals("jpg", PageHashRecorder.normalizeExtension(".jpg"));
        assertEquals("jpg", PageHashRecorder.normalizeExtension(".JPG"));
        assertEquals("webp", PageHashRecorder.normalizeExtension(".webp"));
        assertEquals("png", PageHashRecorder.normalizeExtension("png"));
    }

    @Test
    public void webp_validFixture_acceptedAndRecorded() throws IOException {
        byte[] valid = fixture("valid.webp");
        PageHashRecorder recorder = PageHashRecorder.sha256();
        ByteArrayOutputStream file = teeWrite(recorder, valid);

        CapturingWriter writer = new CapturingWriter();
        CapturingRemover remover = new CapturingRemover();
        boolean accepted = recorder.verifyAndRecord(
                new ByteArrayInputStream(file.toByteArray()), remover,
                1L, 0, ".webp", "read_sync", writer);

        assertTrue(accepted);
        assertEquals(0, remover.removed);
        assertEquals(1, writer.rows.size());
        Row row = writer.rows.get(0);
        assertEquals("webp", row.ext);
        assertEquals((long) valid.length, row.size);
        assertEquals("read_sync", row.origin);
    }
}
