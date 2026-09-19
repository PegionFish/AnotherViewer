package com.hippo.anotherviewer.web.dto

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hippo.anotherviewer.web.config.PrivacyMaskFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DownloadItem 新增 uploader/pages 字段（W1 缺陷修复，GalleryInfoBase 列透出）：
 * - 有值/空值两态的 JSON 形态稳定；
 * - privacy.mask_enabled 时 PrivacyMaskFilter（/api/v1/download 在
 *   SCOPED_PREFIXES 作用域内）对含数值 gid 的条目把 uploader 清成 ""，
 *   pages 非敏感字段不动 —— 清空发生在 Filter 的 JSON 树层，DTO 层不重复处理。
 */
class DownloadDtoTest {

    private val mapper = jacksonObjectMapper()

    private fun item(uploader: String?, pages: Int) = DownloadItem(
        id = 1L, gid = 42L, token = "tok", title = "T", titleJpn = null,
        thumb = null, category = 1, state = 3, total = 10, done = 10,
        label = 0, uploader = uploader, pages = pages
    )

    @Test
    fun `uploader and pages serialize with values`() {
        val root = mapper.readTree(mapper.writeValueAsString(item("someone", 42)))

        assertEquals("someone", root["uploader"].asText())
        assertEquals(42, root["pages"].asInt())
    }

    @Test
    fun `uploader and pages default to null and zero`() {
        val bare = DownloadItem(
            id = 1L, gid = 42L, token = "tok", title = null, titleJpn = null,
            thumb = null, category = 0, state = 0, total = 0, done = 0, label = 0
        )
        val root = mapper.readTree(mapper.writeValueAsString(bare))

        assertTrue(bare.uploader == null)
        assertEquals(0, bare.pages)
        assertTrue(root["uploader"].isNull)
        assertEquals(0, root["pages"].asInt())
    }

    @Test
    fun `privacy mask redact clears uploader but keeps pages on download items`() {
        val root = mapper.readTree(mapper.writeValueAsString(item("someone", 42)))

        assertTrue(PrivacyMaskFilter.redact(root), "mask-on path must change the tree")

        // gid 锚点规则：title → #gid，uploader → ""（脱敏后不出网）。
        assertEquals("#42", root["title"].asText())
        assertEquals("", root["uploader"].asText())
        // pages 非敏感字段，脱敏不动。
        assertEquals(42, root["pages"].asInt())
    }
}
