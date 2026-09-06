package com.hippo.anotherviewer.web.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

data class FavoriteListResponse(
    val favorites: List<FavoriteItem>,
    val totalPages: Int,
    val currentPage: Int,
    // P2/W2-B2: 新增信封字段（向后兼容——既有字段类型不变，旧客户端忽略不受
    // 影响）：page = 回显的 1 起页码，pageSize = 实效页大小（<1 夹到 1），
    // total = 过滤后总行数。带默认值以保持既有 3 参构造调用兼容；
    // 前端消费切换在 W3-F4 落地。
    val page: Int = currentPage,
    val pageSize: Int = 20,
    val total: Int = favorites.size,
)

data class FavoriteItem(
    val gid: Long,
    val token: String,
    val title: String,
    val titleJpn: String,
    val thumb: String,
    val category: Int,
    val rating: Float,
    val uploader: String?,
    val posted: String?,
    // F-UX5: 条目真实收藏槽位（Android 契约：-2 未收藏 / -1 默认夹 / 0-9 自定义夹）。
    // 列表响应附加字段（openapi FavoriteItem 未限制 additionalProperties），
    // 供收藏页 ♥ 徽章渲染真值；旧客户端忽略该字段不受影响。
    val favoriteSlot: Int = -2,
    // 阅读进度（0 起页索引，来自同 gid 历史行；无历史行为 null），
    // 收藏页卡片角标用；缺省/0 时前端不显示角标。
    val readProgress: Int? = null
)

data class FavoriteAddRequest(
    @field:Min(1, message = "gid must be a positive number")
    val gid: Long,
    // token may legitimately be empty for locally-created favorites, so it is
    // only length-bounded, never required.
    @field:Size(max = 64, message = "token must be at most 64 characters")
    val token: String,
    // category is a site bitmask (up to 512), intentionally unconstrained.
    val category: Int,
    // Android favoriteSlot semantics: -1 = default folder, 0-9 = custom slots.
    // Optional: clients that don't target a folder omit it and get the default.
    val slot: Int = -1
)

data class FavoriteRemoveRequest(
    @field:Min(1, message = "gid must be a positive number")
    val gid: Long,
    val token: String,
    val category: Int
)
