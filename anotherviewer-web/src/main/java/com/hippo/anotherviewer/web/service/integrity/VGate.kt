package com.hippo.anotherviewer.web.service.integrity

import java.io.InputStream

/**
 * 内容嗅探命中的图片格式（与扩展名无关）。
 *
 * [minHeaderLen]：V1 命中该格式所需最小长度（contracts/integrity-vgate.md §2 表）。
 * [minTotalLen]：V2 尾窗口不与头重叠所需最小总长（§3 表）——总长不足即 V2 拒。
 */
enum class VGateFormat(val minHeaderLen: Int, val minTotalLen: Int) {
    JPEG(minHeaderLen = 2, minTotalLen = 4),
    PNG(minHeaderLen = 8, minTotalLen = 16),
    GIF(minHeaderLen = 6, minTotalLen = 7),
    WEBP(minHeaderLen = 12, minTotalLen = 12),
}

/** 拒因（contracts/integrity-vgate.md §2/§3）。 */
enum class VGateRejectReason {
    /** 头部不命中任何格式魔数：空文件、HTML 错误页伪装（`.jpg` 扩展名无关）、头部截断。 */
    V1_MAGIC,

    /** 尾标记不过：截断、合法图 + 尾部追加垃圾、WebP RIFF 长度不自洽、总长短于尾窗口下限。 */
    V2_TAIL,
}

/**
 * V 门判定结果。accept ⇔ V1 ∧ V2；accept 后由调用方对落盘字节计算 SHA-256 基线
 * （V 门本身不算哈希，规格 §4）。
 */
sealed class VGateResult {
    data object Accept : VGateResult()

    /**
     * @param reason 拒因（V1/V2 细分）。
     * @param format V1 嗅探出的格式；V1 拒（无格式命中）时为 null，V2 拒时必非 null。
     */
    data class Reject(
        val reason: VGateRejectReason,
        val format: VGateFormat?,
    ) : VGateResult()
}

/**
 * V 门：图片页文件**落盘前**的字节级校验（规格：contracts/integrity-vgate.md，自包含）。
 *
 * 只做廉价字节级启发式（V1 魔数 + V2 尾标记），不做完整解码、不做 IO、**不算哈希**。
 *
 * - V1（按内容嗅探魔数，四种互斥，全不命中即拒）先于 V2（精确到流末尾的尾标记），
 *   V1 拒则不看 V2。
 * - V2 按格式：JPEG 末 2 字节 `FF D9`；PNG 末 8 字节 `IEND+CRC`（`49 45 4E 44 AE 42 60 82`）；
 *   GIF 末字节 `3B`；WebP 字节 4–7 的小端 u32 == 总长 − 8（RIFF 容器长度自洽，
 *   不区分 VP8/VP8L/VP8X chunk）。
 * - 已知盲区（规格 §3，有意接受）：垃圾恰好使流末尾仍满足尾规则时放行——V 门是廉价闸门，
 *   不是解码器，此类残留由 SHA-256 基线巡检兜底。
 *
 * [check] 两版共用同一条判定路径：字节版一次喂入；流式版逐块喂入，边读边定头（≤12B）、
 * 滚动保留末尾 ≤8B 判尾，内存占用 O(12B 头 + 8B 尾 + 8KB 读缓冲)，不要求流可回溯，
 * 不关闭调用方的流——Wave 2 下载钩子可用它校验下载流而无需全量驻内存。
 */
object VGate {

    /** 字节版：对完整字节数组做 V 门校验。 */
    fun check(bytes: ByteArray): VGateResult {
        val engine = Engine()
        engine.update(bytes, 0, bytes.size)
        return engine.finish()
    }

    /**
     * 流式版：把 [input] 读到 EOF 并判定（消费整条流，但**不关闭**它）。
     * 判定语义与字节版完全一致。
     */
    fun check(input: InputStream): VGateResult {
        val engine = Engine()
        val buf = ByteArray(STREAM_BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) engine.update(buf, 0, n)
        }
        return engine.finish()
    }

    // ── 内部：字节级引擎（bytes 版与流式版共用，保证两版判定恒一致） ───────────

    /** V1 嗅探窗口 = 四种魔数最长者（WebP 12B）；V2 滚动尾窗口 = PNG 尾标记 8B。 */
    private const val HEAD_LEN = 12
    private const val TAIL_LEN = 8
    private const val STREAM_BUFFER = 8 * 1024

    private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val JPEG_EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
    )

    /** `IEND` 类型 + 其固定 CRC `AE 42 60 82`（PNG 尾 8 字节）。 */
    private val PNG_IEND_TAIL = byteArrayOf(
        0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(),
        0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte(),
    )

    private val GIF87A = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val GIF89A = "GIF89a".toByteArray(Charsets.US_ASCII)
    private val GIF_TRAILER = byteArrayOf(0x3B.toByte())

    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP_TAG = "WEBP".toByteArray(Charsets.US_ASCII)

    /**
     * 滚动判定引擎：头 12B 定 V1，尾 8B + 总长定 V2。喂完全部字节后调 [finish]。
     */
    private class Engine {
        private val head = ByteArray(HEAD_LEN)
        private var headLen = 0
        private val tail = ByteArray(TAIL_LEN) // 最近读到的 ≤8 字节
        private var tailLen = 0
        private var total = 0L

        fun update(chunk: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val headTake = minOf(len, HEAD_LEN - headLen)
            if (headTake > 0) {
                System.arraycopy(chunk, off, head, headLen, headTake)
                headLen += headTake
            }
            if (len >= TAIL_LEN) {
                System.arraycopy(chunk, off + len - TAIL_LEN, tail, 0, TAIL_LEN)
                tailLen = TAIL_LEN
            } else {
                // 保留旧尾的后 (TAIL_LEN - len) 字节，再接上新块
                val shift = minOf(tailLen, TAIL_LEN - len)
                if (shift > 0) System.arraycopy(tail, tailLen - shift, tail, 0, shift)
                System.arraycopy(chunk, off, tail, shift, len)
                tailLen = minOf(tailLen + len, TAIL_LEN)
            }
            total += len
        }

        fun finish(): VGateResult {
            val format = sniff(head, headLen)
                ?: return VGateResult.Reject(VGateRejectReason.V1_MAGIC, null)
            if (total < format.minTotalLen) {
                return VGateResult.Reject(VGateRejectReason.V2_TAIL, format)
            }
            val tailOk = when (format) {
                VGateFormat.JPEG -> tailEndsWith(JPEG_EOI)
                VGateFormat.PNG -> tailEndsWith(PNG_IEND_TAIL)
                VGateFormat.GIF -> tailEndsWith(GIF_TRAILER)
                // 只做 RIFF 容器长度自洽：声明长度（字节 4–7 小端 u32）== 总长 − 8
                VGateFormat.WEBP -> riffDeclaredLength(head) == total - 8
            }
            return if (tailOk) VGateResult.Accept
            else VGateResult.Reject(VGateRejectReason.V2_TAIL, format)
        }

        /** [magic] 是否恰为当前已见字节（[tail] 的末 [tailLen] 字节）的结尾。 */
        private fun tailEndsWith(magic: ByteArray): Boolean {
            if (tailLen < magic.size) return false
            for (i in magic.indices) {
                if (tail[tailLen - magic.size + i] != magic[i]) return false
            }
            return true
        }
    }

    /** V1：头部魔数嗅探。四种魔数首字节互异，天然互斥，最多命中一种。 */
    private fun sniff(head: ByteArray, headLen: Int): VGateFormat? = when {
        matchesAt(head, headLen, 0, JPEG_SOI) -> VGateFormat.JPEG
        matchesAt(head, headLen, 0, PNG_SIGNATURE) -> VGateFormat.PNG
        matchesAt(head, headLen, 0, GIF87A) || matchesAt(head, headLen, 0, GIF89A) -> VGateFormat.GIF
        matchesAt(head, headLen, 0, RIFF) && matchesAt(head, headLen, 8, WEBP_TAG) -> VGateFormat.WEBP
        else -> null
    }

    /** [head] 的前 [available] 字节中，[offset] 起是否完整出现 [magic]（不足长即不命中）。 */
    private fun matchesAt(head: ByteArray, available: Int, offset: Int, magic: ByteArray): Boolean {
        if (available < offset + magic.size) return false
        for (i in magic.indices) {
            if (head[offset + i] != magic[i]) return false
        }
        return true
    }

    /** WebP 字节 4–7 的小端 u32（RIFF 声明的容器长度）。 */
    private fun riffDeclaredLength(head: ByteArray): Long =
        (head[4].toLong() and 0xFF) or
            ((head[5].toLong() and 0xFF) shl 8) or
            ((head[6].toLong() and 0xFF) shl 16) or
            ((head[7].toLong() and 0xFF) shl 24)
}
