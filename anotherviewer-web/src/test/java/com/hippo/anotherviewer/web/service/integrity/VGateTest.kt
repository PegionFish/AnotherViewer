package com.hippo.anotherviewer.web.service.integrity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * V 门断言矩阵（contracts/integrity-vgate.md §5/§6）。
 *
 * fixtures 由 Wave 0 铺设于 src/test/resources/integrity/（9 个样本 + MANIFEST.sha256），
 * 从 classpath `/integrity/` 加载，口径以 MANIFEST.sha256 为准。bytes 版与流式版跑
 * 同一矩阵；流式版另以多种分块大小压滚动尾窗口的分块边界，合成样本压 V1/V2 最小长度边界。
 */
class VGateTest {

    // ── fixtures 口径 ─────────────────────────────────────────────────────────

    private class FixtureExpectation(val accept: Boolean, val reason: VGateRejectReason? = null)

    private val fixtureMatrix: Map<String, FixtureExpectation> = linkedMapOf(
        "valid.jpg" to FixtureExpectation(true),
        "valid.png" to FixtureExpectation(true),
        "valid.gif" to FixtureExpectation(true),
        "valid.webp" to FixtureExpectation(true),
        "truncated.jpg" to FixtureExpectation(false, VGateRejectReason.V2_TAIL),
        "truncated.png" to FixtureExpectation(false, VGateRejectReason.V2_TAIL),
        "padded.png" to FixtureExpectation(false, VGateRejectReason.V2_TAIL),
        "html_disguised.jpg" to FixtureExpectation(false, VGateRejectReason.V1_MAGIC),
        "empty.bin" to FixtureExpectation(false, VGateRejectReason.V1_MAGIC),
    )

    private fun loadFixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/integrity/$name")?.readBytes()
            ?: error("classpath 缺少 fixture /integrity/$name")

    /** MANIFEST.sha256（sha256sum 格式 `hash␠␠filename`）→ {文件名: 哈希}，口径权威。 */
    private val manifest: Map<String, String> by lazy {
        loadFixture("MANIFEST.sha256").decodeToString().trim().lines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                require(parts.size == 2 && parts[0].length == 64) { "bad manifest line: $line" }
                parts[1] to parts[0]
            }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun assertVerdict(label: String, expected: FixtureExpectation, actual: VGateResult) {
        if (expected.accept) {
            assertEquals(VGateResult.Accept, actual, "$label: 期望 accept，实际 $actual")
        } else {
            assertTrue(actual is VGateResult.Reject, "$label: 期望 reject，实际 $actual")
            // junit-jupiter 5.11 的 assertNotNull 返回 void，不可捕获；用早退换智能转换
            if (actual !is VGateResult.Reject) return
            assertEquals(expected.reason, actual.reason, "$label: 拒因")
            if (actual.reason == VGateRejectReason.V2_TAIL) {
                assertNotNull(actual.format, "$label: V2 拒时应携带嗅探出的格式")
            } else {
                assertNull(actual.format, "$label: V1 拒时应无格式命中")
            }
        }
    }

    // ── 主矩阵：bytes 版 ──────────────────────────────────────────────────────

    @Test
    fun `bytes version fixture matrix`() {
        fixtureMatrix.forEach { (name, expected) ->
            assertVerdict(name, expected, VGate.check(loadFixture(name)))
        }
    }

    @Test
    fun `all fixtures bytes match manifest sha256`() {
        // 覆盖全部 9 个 fixtures（含 accept 用例要求的「SHA-256 == MANIFEST 条目」），
        // 把 Wave 0 铺设的字节口径整体钉死。
        assertEquals(fixtureMatrix.keys, manifest.keys)
        fixtureMatrix.keys.forEach { name ->
            assertEquals(manifest[name], sha256Hex(loadFixture(name)), "$name: 与 MANIFEST 口径不一致")
        }
    }

    // ── 主矩阵：流式版 ────────────────────────────────────────────────────────

    @Test
    fun `stream version fixture matrix`() {
        fixtureMatrix.forEach { (name, expected) ->
            assertVerdict(name, expected, VGate.check(ByteArrayInputStream(loadFixture(name))))
        }
    }

    @Test
    fun `stream version verdict is chunk-boundary independent`() {
        for (chunk in intArrayOf(1, 3, 7, 8, 64)) {
            fixtureMatrix.forEach { (name, expected) ->
                assertVerdict("$name(chunk=$chunk)", expected, VGate.check(ChunkedStream(loadFixture(name), chunk)))
            }
        }
    }

    @Test
    fun `stream bigger than read buffer uses rolling tail`() {
        val filler = ByteArray(64 * 1024) { (it and 0x7F).toByte() }
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + filler + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertTrue(VGate.check(ByteArrayInputStream(jpeg)) is VGateResult.Accept, "大流 JPEG 应 accept")
        // 掐掉 EOI 的最后 1 字节 → V2 拒
        val truncated = jpeg.copyOf(jpeg.size - 1)
        assertEquals(
            VGateResult.Reject(VGateRejectReason.V2_TAIL, VGateFormat.JPEG),
            VGate.check(ByteArrayInputStream(truncated)),
        )
    }

    @Test
    fun `stream version drains fully but never closes the caller's stream`() {
        val src = loadFixture("valid.webp")
        val stream = TrackingStream(src)
        assertTrue(VGate.check(stream) is VGateResult.Accept)
        assertEquals(src.size, stream.bytesRead, "应读尽整条流")
        assertFalse(stream.closed, "不得关闭调用方的流")
    }

    // ── 合成边界：V1 魔数 ─────────────────────────────────────────────────────

    @Test
    fun `v1 rejects incomplete or unknown magic regardless of length`() {
        val v1 = FixtureExpectation(false, VGateRejectReason.V1_MAGIC)
        assertVerdict("empty", v1, VGate.check(ByteArray(0)))
        assertVerdict("html prefix", v1, VGate.check("<html><body>503</body></html>".toByteArray()))
        assertVerdict("gif magic truncated", v1, VGate.check("GIF8".toByteArray()))
        assertVerdict("png magic truncated", v1, VGate.check(PNG_SIGNATURE.copyOf(4)))
        assertVerdict("riff only 4b", v1, VGate.check("RIFF".toByteArray()))
        assertVerdict("riff+webp tag but 11b", v1, VGate.check("RIFF".toByteArray() + ByteArray(3) + "WEBP".toByteArray()))
    }

    @Test
    fun `v1 passes at minimal header but below v2 window rejects as v2`() {
        assertVerdict(
            "2b jpeg soi",
            FixtureExpectation(false, VGateRejectReason.V2_TAIL),
            VGate.check(byteArrayOf(0xFF.toByte(), 0xD8.toByte())),
        )
        assertVerdict(
            "3b jpeg",
            FixtureExpectation(false, VGateRejectReason.V2_TAIL),
            VGate.check(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x12)),
        )
        assertVerdict(
            "6b gif no trailer",
            FixtureExpectation(false, VGateRejectReason.V2_TAIL),
            VGate.check("GIF87a".toByteArray()),
        )
        assertVerdict(
            "8b png signature only",
            FixtureExpectation(false, VGateRejectReason.V2_TAIL),
            VGate.check(PNG_SIGNATURE),
        )
    }

    // ── 合成边界：V2 尾标记最小可接受尺寸 ─────────────────────────────────────

    @Test
    fun `v2 accepts exactly at minimal sizes`() {
        assertTrue(VGate.check(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())) is VGateResult.Accept, "4B 最小 JPEG")
        assertTrue(VGate.check("GIF89a\u003B".toByteArray()) is VGateResult.Accept, "7B 最小 GIF")
        assertTrue(VGate.check(PNG_SIGNATURE + IEND_CRC) is VGateResult.Accept, "16B 最小 PNG")
        assertTrue(VGate.check(riffWebp(declaredLen = 4, payloadBytes = 0)) is VGateResult.Accept, "12B 最小自洽 WebP")
        assertTrue(VGate.check(riffWebp(declaredLen = 4 + 100, payloadBytes = 100)) is VGateResult.Accept, "112B 自洽 WebP")
    }

    @Test
    fun `v2 rejects webp when riff length is not self-consistent`() {
        val v2 = FixtureExpectation(false, VGateRejectReason.V2_TAIL)
        assertVerdict("declared 5 actual 4", v2, VGate.check(riffWebp(declaredLen = 5, payloadBytes = 0)))
        assertVerdict("declared 4 actual 5", v2, VGate.check(riffWebp(declaredLen = 4, payloadBytes = 1)))
        assertVerdict("declared 0", v2, VGate.check(riffWebp(declaredLen = 0, payloadBytes = 0)))
    }

    @Test
    fun `trailing garbage rejects as v2 for every format`() {
        val garbage = "TRAILING-GARBAGE".toByteArray()
        val formatByName = mapOf(
            "valid.jpg" to VGateFormat.JPEG,
            "valid.png" to VGateFormat.PNG,
            "valid.gif" to VGateFormat.GIF,
            "valid.webp" to VGateFormat.WEBP,
        )
        formatByName.forEach { (name, format) ->
            assertEquals(
                VGateResult.Reject(VGateRejectReason.V2_TAIL, format),
                VGate.check(loadFixture(name) + garbage),
                "$name + 尾部垃圾",
            )
        }
    }

    // ── 测试用流 ──────────────────────────────────────────────────────────────

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
    )

    private val IEND_CRC = byteArrayOf(
        0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(),
        0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte(),
    )

    /** `RIFF` + 小端 u32 声明长度 + `WEBP` + payload；总长 = 12 + payloadBytes。 */
    private fun riffWebp(declaredLen: Int, payloadBytes: Int): ByteArray =
        "RIFF".toByteArray() +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(declaredLen).array() +
            "WEBP".toByteArray() +
            ByteArray(payloadBytes)

    /** 每次最多吐 [maxChunk] 字节的流，用于压滚动尾窗口的分块边界。 */
    private class ChunkedStream(private val src: ByteArray, private val maxChunk: Int) : InputStream() {
        private var pos = 0

        override fun read(): Int = if (pos >= src.size) -1 else src[pos++].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= src.size) return -1
            val n = minOf(len, maxChunk, src.size - pos)
            System.arraycopy(src, pos, b, off, n)
            pos += n
            return n
        }
    }

    private class TrackingStream(private val src: ByteArray) : InputStream() {
        var bytesRead = 0
        var closed = false
        private var pos = 0

        override fun read(): Int {
            if (pos >= src.size) return -1
            bytesRead++
            return src[pos++].toInt() and 0xFF
        }

        override fun close() {
            closed = true
        }
    }
}
