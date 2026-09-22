package com.hippo.anotherviewer.web.config

import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.concurrent.TimeUnit

/**
 * 静态资源缓存分级（P1-3，修复入口 HTML 被全局 365d 长缓存的问题）：
 *
 * - assets 与 icons 两个 handler（Vite 产物目录与静态图标目录）：内容寻址
 *   （文件名带 hash）或一成不变 → 365d `public, immutable` 强缓存，发版即时
 *   生效（换名即失效）。
 * - 根通配 handler（index.html、sw.js、manifest.json 等入口/元数据资源）：
 *   `no-cache`——浏览器可以缓存但每次必须回源验证，发版后老用户拿到新入口，
 *   不再需要强刷或等一年缓存过期。
 *
 * location 语义（期初误配引发生产事故级回归，此段为修正依据，务必与
 * StaticAssetsServingE2ETest 同读）：Framework 6.1+ 对**带前缀**的资源 pattern
 * 按「剥前缀」解析——pattern 只声明哪些请求走进本 handler，请求相对名由
 * location + 去掉前缀后的路径拼出（请求 assets/foo.js → location + "foo.js"，
 * 前缀本身不参与资源定位）。因此 assets 与 icons 的 location 必须指到**子目录
 * 本身**（ASSETS_LOCATION / ICONS_LOCATION）；期初误配为 4 个 static 根，剥前缀
 * 后全部落回 static 根，jar 内全部带 hash 产物与图标 404（前端白屏）。根通配
 * pattern 没有前缀可剥，location + "foo.js" 恰为 static 根下的同名文件，保持
 * Boot 默认 4 个 classpath 根即可。
 *
 * 实现说明：本类接管了 Boot 默认的根通配静态映射（注册同样式且覆盖其
 * Cache-Control；ResourceHandlerRegistry 对同 pattern 的后注册者生效），因此
 * application.yml 的 `spring.web.resources.cache.period` 已移除——全局 period
 * 会把 index.html/sw.js 一并钉上 365d，正是本配置要修复的行为。webjars 未使用
 * （应用无 webjars 依赖）。
 *
 * 注意（Kotlin 语法）：本文件注释文本刻意避免「斜杠紧跟星号」的字面序列——
 * Kotlin 块注释可嵌套，注释内出现该序列会打开嵌套注释导致 Unclosed comment
 * （上文提及 pattern 时只写前缀名，location 只写 classpath 值本身）。
 */
@Configuration
class WebResourceCacheConfig : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // 带内容 hash 的产物：一年强缓存 + immutable。location 剥前缀语义必须
        // 指到子目录本身（类 KDoc「location 语义」段）。
        registry.addResourceHandler("/assets/**")
            .addResourceLocations(ASSETS_LOCATION)
            .setCacheControl(HASHED_ASSET_CACHE_CONTROL)
        // 静态图标目录：同上。
        registry.addResourceHandler("/icons/**")
            .addResourceLocations(ICONS_LOCATION)
            .setCacheControl(HASHED_ASSET_CACHE_CONTROL)
        // 其余静态资源（入口 HTML / Service Worker / manifest）：可缓存但必须
        // 每次回源验证——发版即时生效。无前缀可剥，静态根即正确 location。
        registry.addResourceHandler("/**")
            .addResourceLocations(*CLASSPATH_STATIC_LOCATIONS)
            .setCacheControl(CacheControl.noCache())
    }

    private companion object {
        /** assets 与 icons 共用的强缓存策略（构建后不再变更，可安全共享实例）。 */
        val HASHED_ASSET_CACHE_CONTROL =
            CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable()

        /** Vite 产物目录：剥前缀语义下 location 指向子目录本身。 */
        const val ASSETS_LOCATION = "classpath:/static/assets/"

        /** 静态图标目录：同上。 */
        const val ICONS_LOCATION = "classpath:/static/icons/"

        /** 与 Boot 默认（WebProperties.Resources.STATIC_LOCATIONS）一致的 classpath 静态位置。 */
        val CLASSPATH_STATIC_LOCATIONS = arrayOf(
            "classpath:/META-INF/resources/",
            "classpath:/resources/",
            "classpath:/static/",
            "classpath:/public/",
        )
    }
}
