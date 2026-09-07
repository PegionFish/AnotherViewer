package com.hippo.anotherviewer.web.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

/** GET /api/v1/preferences 响应 */
data class PreferenceResponse(
    val general: GeneralPreferences = GeneralPreferences(),
    val reader: ReaderPreferences = ReaderPreferences(),
    val privacy: PrivacyPreferences = PrivacyPreferences(),
)

data class GeneralPreferences(
    @field:Size(max = 64, message = "theme must be at most 64 characters")
    val theme: String = "light",
    val themeAutoSwitch: Boolean = false,
    @field:Size(max = 64, message = "launchPage must be at most 64 characters")
    val launchPage: String = "homepage",
    // grid|list —— Wave-1 B-1: 共享 GalleryList 消费的默认布局（默认 grid）
    @field:Size(max = 64, message = "listMode must be at most 64 characters")
    val listMode: String = "grid",
    val showReadProgress: Boolean = true,
    @field:Size(max = 64, message = "detailSize must be at most 64 characters")
    val detailSize: String = "long",
    @field:Size(max = 64, message = "thumbSize must be at most 64 characters")
    val thumbSize: String = "middle",
    // App 共享键（PreferenceSyncHelper 的 JSON_ 表）：只加下限不加紧上限，
    // 防 App push 同一条偏好被 400 打断。
    @field:Min(1, message = "historyInfoSize must be at least 1")
    val historyInfoSize: Int = 100,
    val showJpnTitle: Boolean = false,
    val showGalleryPages: Boolean = false,
    val showTagTranslations: Boolean = true,
    val showGalleryComment: Boolean = true,
    val showGalleryRating: Boolean = true,
    val showEhEvents: Boolean = true,
    val showEhLimits: Boolean = true,
    // ---- Wave-1 B 组（1b 浏览一致性） ----
    val showUploader: Boolean = false,
    val showPostedTime: Boolean = false,
    // clamp -2..9（WebUI 输入侧做 clamp，此处为服务端守卫）
    @field:Min(-2, message = "defaultFavoriteSlot must be between -2 and 9")
    @field:Max(9, message = "defaultFavoriteSlot must be between -2 and 9")
    val defaultFavoriteSlot: Int = 0,
    // `|` 分隔的 10 个收藏槽名，空项回退默认（回退逻辑在消费侧）
    @field:Size(max = 255, message = "favoriteSlotNames must be at most 255 characters")
    val favoriteSlotNames: String = "",
    // 最近搜索保留条数，0 = 关闭
    @field:Min(0, message = "recentSearchMax must be non-negative")
    val recentSearchMax: Int = 10,
)

data class ReaderPreferences(
    @field:Size(max = 64, message = "readingDirection must be at most 64 characters")
    val readingDirection: String = "rtl",
    @field:Size(max = 64, message = "pageMode must be at most 64 characters")
    // 'auto'（非 'dual'）：竖屏单页全屏适应、横屏并排双页，与前端
    // DEFAULT_READER_PREFERENCES 保持一致（固定 dual 在竖屏手机上每页
    // 只有半屏宽，用户被迫逐页手动缩放）。
    val pageMode: String = "auto",
    val firstPageCover: Boolean = true,
    @field:Size(max = 64, message = "pageScaling must be at most 64 characters")
    val pageScaling: String = "fit",
    @field:Size(max = 64, message = "startPosition must be at most 64 characters")
    val startPosition: String = "top_right",
    // App 共享键：只加下限（同 historyInfoSize）。
    @field:Min(1, message = "autoPlayIntervalSec must be at least 1")
    val autoPlayIntervalSec: Int = 2,
    val showProgress: Boolean = true,
    val showPageInterval: Boolean = true,
    val fullscreen: Boolean = true,
    // App 共享键：0 = 跟随系统亮度。注意 App 端滑条技术上到 200（max=200），
    // 但 App 同步走 sync/push 原样存储不经本校验；本端点仅 WebUI 消费，收在 100。
    @field:Min(0, message = "brightness must be between 0 and 100")
    @field:Max(100, message = "brightness must be between 0 and 100")
    val brightness: Int = 0,
    // ---- Wave-1 A 组（1c 阅读器深化，入 reader 节可同步） ----
    // black|gray|white
    @field:Size(max = 64, message = "backgroundColor must be at most 64 characters")
    val backgroundColor: String = "black",
    // threeZone|edgeOnly|disabled
    @field:Size(max = 64, message = "tapZoneScheme must be at most 64 characters")
    val tapZoneScheme: String = "threeZone",
    val keyboardPaging: Boolean = true,
    // Web 本地键（不在 App PreferenceSyncHelper 的 JSON_ 表内）：语义为加法
    // 缩放步进，默认 0.25；旧语义（乘法倍率 >1.0）已废弃，上下限一并收紧。
    @field:DecimalMin(value = "0.05", message = "zoomStep must be between 0.05 and 1.0")
    @field:DecimalMax(value = "1.0", message = "zoomStep must be between 0.05 and 1.0")
    val zoomStep: Double = 0.25,
    // Web 本地键：默认 3，钳制 1..5 防脏值撑爆内存渲染。
    @field:DecimalMin(value = "1.0", message = "maxZoom must be between 1.0 and 5.0")
    @field:DecimalMax(value = "5.0", message = "maxZoom must be between 1.0 and 5.0")
    val maxZoom: Double = 3.0,
    // Web 本地键：上限对齐前端滑条范围。
    @field:Min(0, message = "dualPageGap must be between 0 and 100")
    @field:Max(100, message = "dualPageGap must be between 0 and 100")
    val dualPageGap: Int = 8,
    val splitWidePages: Boolean = false,
    // Web 本地键：上限防预取洪泛（与前端预加载 UI 的 20 封顶一致）。
    @field:Min(0, message = "preloadCount must be between 0 and 20")
    @field:Max(20, message = "preloadCount must be between 0 and 20")
    val preloadCount: Int = 2,
    // slide|fade|none
    @field:Size(max = 64, message = "pageTransition must be at most 64 characters")
    val pageTransition: String = "slide",
)

data class PrivacyPreferences(
    val enableAnalytics: Boolean = true,
)

/** PUT /api/v1/preferences 请求 — 所有字段可选，深度合并 */
data class PreferenceUpdateRequest(
    @field:Valid val general: GeneralPreferences? = null,
    @field:Valid val reader: ReaderPreferences? = null,
    @field:Valid val privacy: PrivacyPreferences? = null,
)
