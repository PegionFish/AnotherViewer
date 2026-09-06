package com.hippo.anotherviewer.web.config

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * 当前请求用户名的唯一取值点（A7-1 统一 stamping）。
 *
 * 约定（docs/plan-a7-design.md §3.2/§3.4/§4.1，新增写路径必须遵守）：
 *  - 所有 Web 本地写同步表的新行（History/Favorite/Download/QuickSearch/DownloadLabel）
 *    必须当场 stamp `username = currentUsername()`、`lastModified = now`——adoptNullOwnership
 *    已短路，NULL 行不再有后续认养扫描；
 *  - 已有行更新时 `username` 不覆盖（行上为 NULL 才落当前用户），但 `lastModified`
 *    必须 bump——它是 App 增量 pull（lastModified > since）看到 Web 侧变更的唯一信号；
 *  - 正常 /api 请求链上 authentication 必不为 null：require_auth=false 时
 *    [AuthTokenFilter] 每请求无条件置 "default" 主体，require_auth=true 时为校验后的
 *    登录名；"default" 兜底仅防御非请求线程（@Scheduled/worker 池）意外走到，与
 *    require_auth=false 的默认主体同名，不会制造新 NULL 行；
 *  - worker 线程（下载池）没有 SecurityContext：worker 只 bump lastModified，
 *    绝不写 username（见 DownloadService.updateEntity/persistProgress）。
 */
@Component
class CurrentUsernameProvider {

    /** 当前请求用户名；无认证上下文时回落 "default"。 */
    fun currentUsername(): String =
        SecurityContextHolder.getContext().authentication?.name ?: DEFAULT_USERNAME

    companion object {
        const val DEFAULT_USERNAME = "default"
    }
}
