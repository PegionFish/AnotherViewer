package com.hippo.anotherviewer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V 门断言矩阵（contracts/integrity-vgate.md §5/§6）。
 *
 * fixtures 由 Wave 0 铺设于 src/test/resources/integrity/（9 个样本 + MANIFEST.sha256），
 * 从 classpath {@code /integrity/} 加载，口径以 MANIFEST.sha256 为准。bytes 版与流式版跑
 * 同一矩阵；流式版另以多种分块大小压滚动尾窗口的分块边界，合成样本压 V1/V2 最小长度边界。
 */
public class VGateTest {

    // ── fixtures 口径 ─────────────────────────────────────────────────────────

    /** 每个样本的期望判定：accept，或 reject + 拒因。 */
    private static final class Expectation {
        final boolean accept;
        final VGate.RejectReason reason; // accept 时为 null

        private Expectation(boolean accept, VGate.RejectReason reason) {
            this.accept = accept;
            this.reason = reason;
        }

        static Expectation accept() {
            return new Expectation(true, null);
        }

        static Expectation reject(VGate.RejectReason reason) {
            return new Expectation(false, reason);
        }
    }

    private static final Map<String, Expectation> FIXTURE_MATRIX = new LinkedHashMap<>();

    static {
        FIXTURE_MATRIX.put("valid.jpg", Expectation.accept());
        FIXTURE_MATRIX.put("valid.png", Expectation.accept());
        FIXTURE_MATRIX.put("valid.gif", Expectation.accept());
        FIXTURE_MATRIX.put("valid.webp", Expectation.accept());
        FIXTURE_MATRIX.put("truncated.jpg", Expectation.reject(VGate.RejectReason.V2_TAIL));
        FIXTURE_MATRIX.put("truncated.png", Expectation.reject(VGate.RejectReason.V2_TAIL));
        FIXTURE_MATRIX.put("padded.png", Expectation.reject(VGate.RejectReason.V2_TAIL));
        FIXTURE_MATRIX.put("html_disguised.jpg", Expectation.reject(VGate.RejectReason.V1_MAGIC));
        FIXTURE_MATRIX.put("empty.bin", Expectation.reject(VGate.RejectReason.V1_MAGIC));
    }

    private static byte[] loadFixture(String name) throws IOException {
        InputStream in = VGateTest.class.getResourceAsStream("/integrity/" + name);
        if (in == null) {
            throw new AssertionError("classpath 缺少 fixture /integrity/" + name);
        }
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    /** MANIFEST.sha256（sha256sum 格式 {@code hash␠␠filename}）→ {文件名: 哈希}，口径权威。 */
    private static Map<String, String> loadManifest() throws IOException {
        String text = new String(loadFixture("MANIFEST.sha256"), StandardCharsets.UTF_8).trim();
        Map<String, String> manifest = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            assertTrue("bad manifest line: " + line, parts.length == 2 && parts[0].length() == 64);
            manifest.put(parts[1], parts[0]);
        }
        return manifest;
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static void assertVerdict(String label, Expectation expected, VGate.Result actual) {
        if (expected.accept) {
            assertTrue(label + ": 期望 accept，实际 " + actual, actual.accepted);
            assertNull(label + ": accept 不得携带拒因", actual.reason);
            assertNull(label + ": accept 不得携带格式", actual.format);
        } else {
            assertFalse(label + ": 期望 reject，实际 accept", actual.accepted);
            assertEquals(label + ": 拒因", expected.reason, actual.reason);
            if (expected.reason == VGate.RejectReason.V2_TAIL) {
                assertNotNull(label + ": V2 拒时应携带嗅探出的格式", actual.format);
            } else {
                assertNull(label + ": V1 拒时应无格式命中", actual.format);
            }
        }
    }

    // ── 主矩阵：bytes 版 ──────────────────────────────────────────────────────

    @Test
    public void bytesVersionFixtureMatrix() throws Exception {
        for (Map.Entry<String, Expectation> entry : FIXTURE_MATRIX.entrySet()) {
            assertVerdict(entry.getKey(), entry.getValue(), VGate.check(loadFixture(entry.getKey())));
        }
    }

    @Test
    public void allFixturesBytesMatchManifestSha256() throws Exception {
        // 覆盖全部 9 个 fixtures（含 accept 用例要求的「SHA-256 == MANIFEST 条目」），
        // 把 Wave 0 铺设的字节口径整体钉死。
        assertEquals(FIXTURE_MATRIX.keySet(), loadManifest().keySet());
        Map<String, String> manifest = loadManifest();
        for (String name : FIXTURE_MATRIX.keySet()) {
            assertEquals(name + ": 与 MANIFEST 口径不一致", manifest.get(name), sha256Hex(loadFixture(name)));
        }
    }

    // ── 主矩阵：流式版 ────────────────────────────────────────────────────────

    @Test
    public void streamVersionFixtureMatrix() throws Exception {
        for (Map.Entry<String, Expectation> entry : FIXTURE_MATRIX.entrySet()) {
            assertVerdict(entry.getKey(), entry.getValue(),
                    VGate.check(new ByteArrayInputStream(loadFixture(entry.getKey()))));
        }
    }

    @Test
    public void streamVersionVerdictIsChunkBoundaryIndependent() throws Exception {
        int[] chunks = {1, 3, 7, 8, 64};
        for (int chunk : chunks) {
            for (Map.Entry<String, Expectation> entry : FIXTURE_MATRIX.entrySet()) {
                assertVerdict(entry.getKey() + "(chunk=" + chunk + ")", entry.getValue(),
                        VGate.check(new ChunkedStream(loadFixture(entry.getKey()), chunk)));
            }
        }
    }

    @Test
    public void streamBiggerThanReadBufferUsesRollingTail() throws Exception {
        byte[] filler = new byte[64 * 1024];
        for (int i = 0; i < filler.length; i++) {
            filler[i] = (byte) (i & 0x7F);
        }
        byte[] jpeg = concat(VGateTest.JPEG_SOI, filler, JPEG_EOI);
        assertTrue("大流 JPEG 应 accept", VGate.check(new ByteArrayInputStream(jpeg)).accepted);
        // 掐掉 EOI 的最后 1 字节 → V2 拒
        byte[] truncated = new byte[jpeg.length - 1];
        System.arraycopy(jpeg, 0, truncated, 0, truncated.length);
        assertVerdict("大流 JPEG 截断", Expectation.reject(VGate.RejectReason.V2_TAIL),
                VGate.check(new ByteArrayInputStream(truncated)));
    }

    @Test
    public void streamVersionDrainsFullyButNeverClosesCallersStream() throws Exception {
        byte[] src = loadFixture("valid.webp");
        TrackingStream stream = new TrackingStream(src);
        assertTrue(VGate.check(stream).accepted);
        assertEquals("应读尽整条流", src.length, stream.bytesRead);
        assertFalse("不得关闭调用方的流", stream.closed);
    }

    // ── 合成边界：V1 魔数 ─────────────────────────────────────────────────────

    @Test
    public void v1RejectsIncompleteOrUnknownMagicRegardlessOfLength() throws Exception {
        Expectation v1 = Expectation.reject(VGate.RejectReason.V1_MAGIC);
        assertVerdict("empty", v1, VGate.check(new byte[0]));
        assertVerdict("html prefix", v1,
                VGate.check(ascii("<html><body>503</body></html>")));
        assertVerdict("gif magic truncated", v1, VGate.check(ascii("GIF8")));
        assertVerdict("png magic truncated", v1, VGate.check(Arrays.copyOf(PNG_SIGNATURE, 4)));
        assertVerdict("riff only 4b", v1, VGate.check(ascii("RIFF")));
        assertVerdict("riff+webp tag but 11b", v1,
                VGate.check(concat(ascii("RIFF"), new byte[3], ascii("WEBP"))));
    }

    @Test
    public void v1PassesAtMinimalHeaderButBelowV2WindowRejectsAsV2() throws Exception {
        assertVerdict("2b jpeg soi",
                Expectation.reject(VGate.RejectReason.V2_TAIL),
                VGate.check(new byte[]{(byte) 0xFF, (byte) 0xD8}));
        assertVerdict("3b jpeg",
                Expectation.reject(VGate.RejectReason.V2_TAIL),
                VGate.check(new byte[]{(byte) 0xFF, (byte) 0xD8, 0x12}));
        assertVerdict("6b gif no trailer",
                Expectation.reject(VGate.RejectReason.V2_TAIL),
                VGate.check(ascii("GIF87a")));
        assertVerdict("8b png signature only",
                Expectation.reject(VGate.RejectReason.V2_TAIL),
                VGate.check(PNG_SIGNATURE));
    }

    // ── 合成边界：V2 尾标记最小可接受尺寸 ─────────────────────────────────────

    @Test
    public void v2AcceptsExactlyAtMinimalSizes() throws Exception {
        assertTrue("4B 最小 JPEG",
                VGate.check(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9}).accepted);
        assertTrue("7B 最小 GIF", VGate.check(concat(ascii("GIF89a"), new byte[]{0x3B})).accepted);
        assertTrue("16B 最小 PNG", VGate.check(concat(PNG_SIGNATURE, IEND_CRC)).accepted);
        assertTrue("12B 最小自洽 WebP", VGate.check(riffWebp(4, 0)).accepted);
        assertTrue("112B 自洽 WebP", VGate.check(riffWebp(4 + 100, 100)).accepted);
    }

    @Test
    public void v2RejectsWebpWhenRiffLengthIsNotSelfConsistent() throws Exception {
        Expectation v2 = Expectation.reject(VGate.RejectReason.V2_TAIL);
        assertVerdict("declared 5 actual 4", v2, VGate.check(riffWebp(5, 0)));
        assertVerdict("declared 4 actual 5", v2, VGate.check(riffWebp(4, 1)));
        assertVerdict("declared 0", v2, VGate.check(riffWebp(0, 0)));
    }

    @Test
    public void trailingGarbageRejectsAsV2ForEveryFormat() throws Exception {
        byte[] garbage = ascii("TRAILING-GARBAGE");
        Map<String, VGate.Format> formatByName = new LinkedHashMap<>();
        formatByName.put("valid.jpg", VGate.Format.JPEG);
        formatByName.put("valid.png", VGate.Format.PNG);
        formatByName.put("valid.gif", VGate.Format.GIF);
        formatByName.put("valid.webp", VGate.Format.WEBP);
        for (Map.Entry<String, VGate.Format> entry : formatByName.entrySet()) {
            VGate.Result actual = VGate.check(concat(loadFixture(entry.getKey()), garbage));
            assertEquals(entry.getKey() + " + 尾部垃圾",
                    VGate.Result.reject(VGate.RejectReason.V2_TAIL, entry.getValue()), actual);
        }
    }

    // ── 测试用工具与流 ────────────────────────────────────────────────────────

    private static final byte[] JPEG_SOI = {(byte) 0xFF, (byte) 0xD8};
    private static final byte[] JPEG_EOI = {(byte) 0xFF, (byte) 0xD9};

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
    };

    /** {@code IEND} 类型 + 其固定 CRC {@code AE 42 60 82}。 */
    private static final byte[] IEND_CRC = {
            0x49, 0x45, 0x4E, 0x44,
            (byte) 0xAE, 0x42, 0x60, (byte) 0x82,
    };

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) {
            total += a.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }

    /** {@code RIFF} + 小端 u32 声明长度 + {@code WEBP} + payload；总长 = 12 + payloadBytes。 */
    private static byte[] riffWebp(int declaredLen, int payloadBytes) {
        return concat(ascii("RIFF"),
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(declaredLen).array(),
                ascii("WEBP"),
                new byte[payloadBytes]);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (true) {
            int n = in.read(buf);
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 每次最多吐 {@code maxChunk} 字节的流，用于压滚动尾窗口的分块边界。 */
    private static final class ChunkedStream extends InputStream {
        private final byte[] src;
        private final int maxChunk;
        private int pos;

        ChunkedStream(byte[] src, int maxChunk) {
            this.src = src;
            this.maxChunk = maxChunk;
        }

        @Override
        public int read() {
            if (pos >= src.length) {
                return -1;
            }
            return src[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (pos >= src.length) {
                return -1;
            }
            int n = Math.min(len, Math.min(maxChunk, src.length - pos));
            System.arraycopy(src, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    private static final class TrackingStream extends InputStream {
        final byte[] src;
        int bytesRead;
        boolean closed;
        private int pos;

        TrackingStream(byte[] src) {
            this.src = src;
        }

        @Override
        public int read() {
            if (pos >= src.length) {
                return -1;
            }
            bytesRead++;
            return src[pos++] & 0xFF;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
