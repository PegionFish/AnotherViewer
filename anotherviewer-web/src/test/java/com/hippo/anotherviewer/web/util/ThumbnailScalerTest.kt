package com.hippo.anotherviewer.web.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * V4 W1: /proxy 缩略图契约纯函数（参数解析 / 等比缩放 / 缓存键）的单元测试。
 * 控制器级行为（回退、缓存交互、上游共享）见 ImageProxyControllerTest。
 */
class ThumbnailScalerTest {

    companion object {
        /** 生成纯色测试图并按给定格式编码。 */
        private fun encode(format: String, width: Int, height: Int, type: Int = BufferedImage.TYPE_INT_RGB): ByteArray {
            val image = BufferedImage(width, height, type)
            val graphics: Graphics2D = image.createGraphics()
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.dispose()
            val out = ByteArrayOutputStream()
            ImageIO.write(image, format, out)
            return out.toByteArray()
        }

        private fun decode(bytes: ByteArray): BufferedImage =
            ImageIO.read(ByteArrayInputStream(bytes)) ?: throw AssertionError("test bytes must decode")
    }

    // ── parseThumbnailWidth：契约「非法值一律回退（null），绝不 4xx」──

    @Test
    fun `parseThumbnailWidth accepts in-range integers`() {
        assertEquals(200, ThumbnailScaler.parseThumbnailWidth("200"))
        assertEquals(1, ThumbnailScaler.parseThumbnailWidth("1"))
        assertEquals(4096, ThumbnailScaler.parseThumbnailWidth("4096"), "上限 4096 本身合法")
        assertEquals(300, ThumbnailScaler.parseThumbnailWidth(" 300 "), "两端空白容忍")
    }

    @Test
    fun `parseThumbnailWidth falls back on non-numeric, non-positive and oversized values`() {
        assertNull(ThumbnailScaler.parseThumbnailWidth(null), "不带 w → 原尺寸")
        assertNull(ThumbnailScaler.parseThumbnailWidth(""), "空串 → 原尺寸")
        assertNull(ThumbnailScaler.parseThumbnailWidth("   "))
        assertNull(ThumbnailScaler.parseThumbnailWidth("abc"), "非数字 → 回退（绝不 4xx）")
        assertNull(ThumbnailScaler.parseThumbnailWidth("12.5"))
        assertNull(ThumbnailScaler.parseThumbnailWidth("0"), "≤0 → 回退")
        assertNull(ThumbnailScaler.parseThumbnailWidth("-5"))
        assertNull(ThumbnailScaler.parseThumbnailWidth("4097"), ">4096 → 回退")
        assertNull(ThumbnailScaler.parseThumbnailWidth("99999999999"), "溢出 int → 回退")
    }

    // ── scaleToWidth：等比缩放与回退边界 ──

    @Test
    fun `scaleToWidth downscales jpeg proportionally`() {
        val source = encode("jpeg", 400, 200)
        val scaled = ThumbnailScaler.scaleToWidth(source, 100)
        assertNotNull(scaled)
        scaled!!
        assertEquals("image/jpeg", scaled.mimeType)
        val image = decode(scaled.bytes)
        assertEquals(100, image.width)
        assertEquals(50, image.height, "高度按 400→100 比例缩放")
    }

    @Test
    fun `scaleToWidth rounds height and keeps it at least 1`() {
        // 3 * 100 / 307 = 0.98 → 四舍五入钳到 1
        val source = encode("jpeg", 307, 3)
        val scaled = ThumbnailScaler.scaleToWidth(source, 100)
        assertNotNull(scaled)
        val image = decode(scaled!!.bytes)
        assertEquals(100, image.width)
        assertEquals(1, image.height, "极端细长图高度钳到 ≥1")
    }

    @Test
    fun `scaleToWidth preserves alpha for png sources`() {
        val source = encode("png", 200, 100, BufferedImage.TYPE_INT_ARGB)
        val scaled = ThumbnailScaler.scaleToWidth(source, 80)
        assertNotNull(scaled)
        scaled!!
        assertEquals("image/png", scaled.mimeType, "PNG 缩放产物保持 PNG（不透明化）")
        val image = decode(scaled.bytes)
        assertEquals(80, image.width)
        assertEquals(40, image.height)
        assertTrue(image.colorModel.hasAlpha(), "透明通道保留")
    }

    @Test
    fun `scaleToWidth refuses to upscale`() {
        val source = encode("jpeg", 100, 50)
        assertNull(ThumbnailScaler.scaleToWidth(source, 200), "w ≥ 原宽 → 回退原尺寸")
        assertNull(ThumbnailScaler.scaleToWidth(source, 100), "w == 原宽 → 原尺寸（零收益）")
    }

    @Test
    fun `scaleToWidth falls back for animated gif, undecodable and empty bytes`() {
        assertNull(
            ThumbnailScaler.scaleToWidth(encode("gif", 120, 90), 60),
            "GIF 动图重编码丢帧 → 回退原尺寸"
        )
        assertNull(ThumbnailScaler.scaleToWidth("NOT-AN-IMAGE".toByteArray(), 100), "解码失败 → 回退")
        assertNull(ThumbnailScaler.scaleToWidth(ByteArray(0), 100), "空字节 → 回退")
        assertNull(ThumbnailScaler.scaleToWidth(encode("jpeg", 400, 200), 0), "非法宽度 → 回退")
    }

    // ── 缓存键：w 并入且不同宽度互不污染 ──

    @Test
    fun `thumbnailCacheKey is stable and distinct per width and never collides with raw urls`() {
        val url = "https://e-hentai.org/t/1001/cover.jpg"
        assertEquals(ThumbnailScaler.thumbnailCacheKey(url, 80), ThumbnailScaler.thumbnailCacheKey(url, 80))
        assertTrue(ThumbnailScaler.thumbnailCacheKey(url, 80) != ThumbnailScaler.thumbnailCacheKey(url, 160))
        assertFalse(ThumbnailScaler.thumbnailCacheKey(url, 80) == url, "缩放键 ≠ 原尺寸键")
        assertTrue(ThumbnailScaler.thumbnailCacheKey(url, 80).startsWith("thumb:w80:"))
    }

    // ── 嗅探：缩放缓存命中的字节自报 MIME ──

    @Test
    fun `sniffFormat and sniffMime report the real byte format`() {
        assertEquals("JPEG", ThumbnailScaler.sniffFormat(encode("jpeg", 10, 10)))
        assertEquals("png", ThumbnailScaler.sniffFormat(encode("png", 10, 10, BufferedImage.TYPE_INT_ARGB)))
        assertNull(ThumbnailScaler.sniffFormat("garbage".toByteArray()))
        assertNull(ThumbnailScaler.sniffFormat(ByteArray(0)))
        assertEquals("image/jpeg", ThumbnailScaler.sniffMime(encode("jpeg", 10, 10)))
        assertEquals("image/png", ThumbnailScaler.sniffMime(encode("png", 10, 10)))
        assertNull(ThumbnailScaler.sniffMime("garbage".toByteArray()))
    }
}
