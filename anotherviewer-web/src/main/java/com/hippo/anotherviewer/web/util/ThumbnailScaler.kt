package com.hippo.anotherviewer.web.util

import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * V4 W1: `GET /api/v1/image/proxy` 缩略图（`w=`）契约的纯函数实现——参数解析、
 * 按宽等比缩放、以及把 `w` 并入 URL 缓存键。
 *
 * 契约（与前端约定，2026-09）：
 * - `w` 可选整型像素；服务端按比例缩放高度。
 * - 非法值（非数字、≤0、> [MAX_THUMBNAIL_WIDTH]）→ [parseThumbnailWidth] 返回
 *   null，调用方回退原尺寸，**绝不 4xx**——缩略图是体验优化，不能因为它让
 *   封面图挂掉。
 * - 只缩不放：`w ≥ 原宽` 视为不需要缩放（放大只费字节还糊），同样回退原尺寸。
 * - ImageIO 读不了的格式（webp/svg…）与动图 GIF → [scaleToWidth] 返回 null，
 *   调用方回退原尺寸（重编码 GIF 会丢动画帧）。
 *
 * 阅读端点 `/{galleryId}/{page}` 的 `w` 是「声明但忽略」（见 contracts/openapi.yaml
 * streamGalleryImage），本类不改变其行为，仅服务 /proxy。
 */
object ThumbnailScaler {
    private val logger = LoggerFactory.getLogger(ThumbnailScaler::class.java)

    /** 契约上限：超过即视为非法值回退原尺寸。 */
    const val MAX_THUMBNAIL_WIDTH = 4096

    /**
     * 缩放结果的独立缓存键前缀。ImageCacheService 的 url-keyed 层对任意字符串
     * 做 sha256，因此键只需与真实 http(s) url 不相撞：真实 url 不可能以
     * `thumb:w{n}:` 开头（http/https scheme），不同宽度天然隔离。
     */
    private const val CACHE_KEY_PREFIX = "thumb:w"

    init {
        // 服务端无盘 ImageIO 流缓存（默认会在用户临时目录落小文件）。
        ImageIO.setUseCache(false)
    }

    /**
     * 解析 `w` 参数。null/空白/非数字/≤0/> [MAX_THUMBNAIL_WIDTH] 一律返回 null
     * （= 回退原尺寸）。边界值 [MAX_THUMBNAIL_WIDTH] 本身合法。
     */
    fun parseThumbnailWidth(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim().toIntOrNull() ?: return null
        if (value <= 0 || value > MAX_THUMBNAIL_WIDTH) return null
        return value
    }

    /** `w` 并入缓存键：`thumb:w{width}:{url}`（见 [CACHE_KEY_PREFIX]）。 */
    fun thumbnailCacheKey(url: String, width: Int): String = "$CACHE_KEY_PREFIX$width:$url"

    /**
     * 把 [source] 等比缩放到 [targetWidth] 像素宽（高度按比例，最小 1px）。
     *
     * 返回 null（调用方回退原尺寸字节）当：
     * - [targetWidth] ≥ 原宽（只缩不放）；
     * - 格式不在可安全重编码的白名单内（JPEG/PNG 之外——GIF 是动图会丢帧，
     *   webp/svg 等无 ImageIO 读写器）；
     * - 解码/编码失败。
     *
     * 成功时 [ScaledThumbnail.mimeType] 与实际字节一致（按解码出的真实格式，
     * 而非调用方声称的 Content-Type——缓存命中的字节本就无类型信息）。
     */
    fun scaleToWidth(source: ByteArray, targetWidth: Int): ScaledThumbnail? {
        if (targetWidth <= 0 || source.isEmpty()) return null
        val format = sniffFormat(source) ?: return null
        // 只重编码 JPEG/PNG：GIF 动图丢帧、webp/TIFF/BMP/WBMP 要么不保真要么
        // 场景外，全部回退原字节（见类 KDoc 契约）。
        val (writerFormat, mime) = when (format.lowercase()) {
            "jpeg", "jpg" -> "JPEG" to "image/jpeg"
            "png" -> "png" to "image/png"
            else -> return null
        }
        val image = try {
            ImageIO.read(ByteArrayInputStream(source))
        } catch (e: Exception) {
            logger.debug("Thumbnail decode failed, falling back to original bytes", e)
            null
        } ?: return null

        val originalWidth = image.width
        val originalHeight = image.height
        if (originalWidth <= 0 || originalHeight <= 0 || targetWidth >= originalWidth) return null

        // 等比：按 Double 比例四舍五入并钳到 ≥1（先整数除会丢精度），极端
        // 细长图也不产出 0 高。
        val targetHeight = max(1, (originalHeight.toDouble() * targetWidth / originalWidth).roundToInt())
        // 有透明通道的源（PNG）保留 alpha（ARGB），JPEG 等不透明源用 RGB，
        // 避免 JPEG 编码器遇到 alpha 通道报 Bogus colorspace。
        val type = if (image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val scaled = BufferedImage(targetWidth, targetHeight, type)
        val graphics = scaled.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null)
        } finally {
            graphics.dispose()
        }
        val out = ByteArrayOutputStream()
        val wrote = try {
            ImageIO.write(scaled, writerFormat, out)
        } catch (e: Exception) {
            logger.debug("Thumbnail encode failed, falling back to original bytes", e)
            false
        }
        if (!wrote) return null
        return ScaledThumbnail(out.toByteArray(), mime)
    }

    /**
     * 从字节头嗅探图片格式名（ImageIO reader 声称的，如 "JPEG"/"png"）。
     * 无法识别时返回 null。只读文件头，开销可忽略。
     */
    fun sniffFormat(source: ByteArray): String? {
        if (source.isEmpty()) return null
        return try {
            val input: ImageInputStream = ImageIO.createImageInputStream(ByteArrayInputStream(source)) ?: return null
            input.use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) return null
                val reader = readers.next()
                try {
                    reader.formatName
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 缩放结果字节的 MIME（image/jpeg / image/png；未知回退 null → 调用方默认 jpeg）。 */
    fun sniffMime(source: ByteArray): String? = when (sniffFormat(source)?.lowercase()) {
        "jpeg", "jpg" -> "image/jpeg"
        "png" -> "image/png"
        else -> null
    }
}

/** 缩放产物：重编码字节 + 与字节一致的 MIME。 */
data class ScaledThumbnail(
    val bytes: ByteArray,
    val mimeType: String,
)
