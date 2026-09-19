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
 * 实现说明：本类接管了 Boot 默认的根通配静态映射（注册同样式且覆盖其
 * Cache-Control；ResourceHandlerRegistry 对同 pattern 的后注册者生效），因此
 * application.yml 的 `spring.web.resources.cache.period` 已移除——全局 period
 * 会把 index.html/sw.js 一并钉上 365d，正是本配置要修复的行为。标准 classpath
 * 静态位置与 Boot 默认一致；webjars 未使用（应用无 webjars 依赖）。
 *
 * 注意（Kotlin 语法）：本文件注释文本刻意避免「斜杠紧跟星号」的字面序列——
 * Kotlin 块注释可嵌套，注释内出现该序列会打开嵌套注释导致 Unclosed comment。
 */
@Configuration
class WebResourceCacheConfig : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // 带内容 hash 的产物与静态图标目录：一年强缓存 + immutable。
        registry.addResourceHandler("/assets/**", "/icons/**")
            .addResourceLocations(*CLASSPATH_STATIC_LOCATIONS)
            .setCacheControl(
                CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable()
            )
        // 其余静态资源（入口 HTML / Service Worker / manifest）：可缓存但必须
        // 每次回源验证——发版即时生效。
        registry.addResourceHandler("/**")
            .addResourceLocations(*CLASSPATH_STATIC_LOCATIONS)
            .setCacheControl(CacheControl.noCache())
    }

    private companion object {
        /** 与 Boot 默认（WebProperties.Resources.STATIC_LOCATIONS）一致的 classpath 静态位置。 */
        val CLASSPATH_STATIC_LOCATIONS = arrayOf(
            "classpath:/META-INF/resources/",
            "classpath:/resources/",
            "classpath:/static/",
            "classpath:/public/",
        )
    }
}
