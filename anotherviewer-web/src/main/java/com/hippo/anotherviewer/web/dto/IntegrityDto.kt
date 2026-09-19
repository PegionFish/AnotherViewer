package com.hippo.anotherviewer.web.dto

/**
 * 下载文件完整性端点请求/响应体（文件完整性 Wave 2 / S5），
 * 字段对齐 contracts/openapi.yaml「Integrity」段的 IntegrityHashEntry /
 * IntegrityHashAcceptResponse。Wave 3 S6 的 report/reverify DTO 亦归本文件。
 */

/**
 * POST /api/v1/integrity/hashes/{gid} 的一条页哈希上送（App 以 peer 证据身份
 * 上报；required = page/ext/size/hash）。
 *
 * @param page 1-based 页号（契约口径；落库 peer_hash 前由控制器转 0-based，
 *   对齐 page_file_hash 基线与 App 侧 PageHashStore 的 0-based 页号）
 * @param ext  页文件扩展名（不带点）；peer_hash 表不落此列，仅随上送校验
 * @param size 上送端哈希时文件字节数；peer_hash 表不落此列，仅随上送校验
 * @param hash SHA-256 hex 摘要（64 个十六进制字符，大小写均可，落库归一小写）
 * @param algo 哈希算法枚举，缺省即 SHA-256；显式给出非 SHA-256 值 → 400
 */
data class IntegrityHashEntry(
    val page: Int,
    val ext: String,
    val size: Long,
    val hash: String,
    val algo: String? = null,
)

/** POST /api/v1/integrity/hashes/{gid} 响应；accepted=0 表示空清单 no-op。 */
data class IntegrityHashAcceptResponse(
    val accepted: Int,
)
