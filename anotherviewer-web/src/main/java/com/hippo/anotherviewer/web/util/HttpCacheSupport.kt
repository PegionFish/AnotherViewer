package com.hippo.anotherviewer.web.util

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.security.MessageDigest

/**
 * P-S2：图片端点 HTTP 条件请求（RFC 7232）的纯函数工具——ETag 构造、
 * If-None-Match 解析（弱比较）与 304 响应构造。无状态、便于单测。
 *
 * == ETag 键策略终稿 ==
 * - 池页 serve（`GET /api/v1/image/{gid}/{page}` 命中 App-pushed 池文件）：
 *   有完整性基线（page_file_hash）→ 强 ETag `"sha256:<hash>"`（algo 字段推导，
 *   现役恒 sha256）；无基线 → 弱 `W/"<size>-<mtime>"`。**基线刷新（heal 换
 *   hash）后旧 ETag 自然失效**——这正是强 ETag 的意义：校验器跟着修复后的
 *   字节走，客户端旧缓存不再误命中。
 * - `/api/v1/image/proxy`：用 ImageCacheService 的缓存键派生弱 ETag
 *   `W/"<cacheKey>"`——原尺寸键 = `sha256(url)`（[sha256Hex] 镜像
 *   ImageCacheService.urlKey 的私有实现，两边必须同步改）；`w=` 变体键 =
 *   `thumb:w{n}:{url}`（[ThumbnailScaler.thumbnailCacheKey]），各变体各自
 *   ETag、互不命中。
 *
 * == 页号口径（换算说明） ==
 * 对外阅读端点 `/api/v1/image/{gid}/{page}` 的 page 为 **0-based**；
 * page_file_hash 基线表的 page 亦为 **0-based**（与 download_info /
 * processing_task 同口径）——调用方用 API 页号**直查基线表，无需换算**；
 * 磁盘池文件名的 1-based（`0001.jpg`）换算只发生在 DownloadDirIndex.findPage。
 *
 * == If-None-Match（RFC 7232 §2.3/§3.2） ==
 * 用**弱比较**：忽略 `W/` 前缀、只比 quoted opaque 部分；`*` 匹配任意当前
 * 表示；支持逗号列表（quoted 字符串内的逗号不打断解析），残缺片断跳过不抛。
 */
object HttpCacheSupport {

    /** sha256 hex（小写）。镜像 ImageCacheService.urlKey——改这里必须同步那边。 */
    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 强 ETag：`"<algo>:<hash>"`（带引号）。现役 algo=sha256 → `"sha256:<hash>"`。 */
    fun strongEtag(algo: String, hash: String): String = "\"$algo:$hash\""

    /** 弱 ETag（无基线兜底）：`W/"<size>-<mtime>"`。 */
    fun weakEtag(size: Long, mtimeMs: Long): String = "W/\"$size-$mtimeMs\""

    /** 缓存键派生的弱 ETag（/proxy）：`W/"<cacheKey>"`。 */
    fun weakEtagForKey(cacheKey: String): String = "W/\"$cacheKey\""

    /** /proxy 原尺寸响应的弱 ETag：`W/"<sha256(url)>"`（缓存键即校验器）。 */
    fun proxyEtag(url: String): String = weakEtagForKey(sha256Hex(url))

    /**
     * If-None-Match 命中判定（弱比较）。[ifNoneMatch] null/空白 → 不命中；
     * `*` → 命中（表示存在即命中，与 [currentEtag] 无关）；否则解析逗号列表，
     * 与 [currentEtag] 的 opaque 部分弱比较。
     */
    fun ifNoneMatchMatches(ifNoneMatch: String?, currentEtag: String?): Boolean {
        if (ifNoneMatch.isNullOrBlank()) return false
        val header = ifNoneMatch.trim()
        if (header == "*") return true
        if (currentEtag.isNullOrBlank()) return false
        val target = opaque(currentEtag.trim())
        return parseEntityTags(header).any { it == target }
    }

    /**
     * 304 空体响应：携带当前 ETag 与 Cache-Control——RFC 7232 §4.1 要求 304
     * 响应包含本会随 200 发出的缓存校验头。
     */
    fun notModified(etag: String, cacheControl: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_MODIFIED)
            .header(HttpHeaders.ETAG, etag)
            .header(HttpHeaders.CACHE_CONTROL, cacheControl)
            .build()

    /** 去掉弱前缀，取 quoted opaque 部分（弱比较忽略 W/）。 */
    private fun opaque(etag: String): String =
        if (etag.length > 2 && etag[0] == 'W' && etag[1] == '/') etag.substring(2) else etag

    /**
     * 解析 If-None-Match 的逗号分隔 entity-tag 列表，返回各 tag 的 quoted
     * opaque 部分（`"abc"` → `"abc"`，`W/"abc"` → `"abc"`）。容错：quoted 内
     * 的逗号不打断解析；残缺片断（缺右引号、不认识的 token）跳到下一个逗号。
     */
    private fun parseEntityTags(header: String): List<String> {
        val tags = mutableListOf<String>()
        var i = 0
        val n = header.length
        while (i < n) {
            val c = header[i]
            when {
                c == ' ' || c == '\t' || c == ',' -> i++
                c == '"' -> {
                    val quoted = readQuoted(header, i)
                    if (quoted == null) return tags
                    tags.add(quoted.first)
                    i = quoted.second
                }
                c == 'W' && i + 1 < n && header[i + 1] == '/' -> {
                    var j = i + 2
                    while (j < n && (header[j] == ' ' || header[j] == '\t')) j++
                    if (j < n && header[j] == '"') {
                        val quoted = readQuoted(header, j)
                        if (quoted == null) return tags
                        tags.add(quoted.first)
                        i = quoted.second
                    } else {
                        i = skipToComma(header, j)
                    }
                }
                else -> i = skipToComma(header, i)
            }
        }
        return tags
    }

    /** 读以 [start]（引号处）开始的带引号串，返回（含引号的串，下一位置）；缺右引号返回 null。 */
    private fun readQuoted(header: String, start: Int): Pair<String, Int>? {
        val close = header.indexOf('"', start + 1)
        if (close < 0) return null
        return header.substring(start, close + 1) to close + 1
    }

    private fun skipToComma(header: String, from: Int): Int {
        var i = from
        while (i < header.length && header[i] != ',') i++
        return i
    }
}
