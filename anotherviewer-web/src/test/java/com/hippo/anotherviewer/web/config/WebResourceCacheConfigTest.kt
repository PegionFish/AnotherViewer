package com.hippo.anotherviewer.web.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.support.StaticApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler
import java.util.concurrent.TimeUnit

/**
 * P1-3：静态资源缓存分级配置的轻量单测（不起 Spring Boot 上下文）——直接驱动
 * [WebResourceCacheConfig.addResourceHandlers]，从注册结果（SimpleUrlHandlerMapping
 * 的 urlMap）里取各 pattern 的 [ResourceHttpRequestHandler] 断言其 Cache-Control
 * 与 **location**。
 *
 * 断言口径：
 * - assets/icons 两个前缀（内容寻址产物 + 静态图标）365d public immutable，
 *   且 location 指到各自 static 子目录**本身**——Framework 6.1+ 对带前缀 pattern
 *   是「剥前缀」解析（请求相对名 = location + 去前缀路径），期初误配 static 根
 *   曾让 jar 内全量产物 404（生产事故级），由本测试与 StaticAssetsServingE2ETest
 *   双层钉死；
 * - 根通配映射（index.html、sw.js、manifest.json 所在）no-cache，location 保持
 *   Boot 默认 4 个 classpath 根（无前缀可剥）。
 *
 * 注意：registry 需要一个**真** ApplicationContext（StaticApplicationContext 即可）
 * 才能把 location 字符串解析成 [ClassPathResource]——期初用 Mockito mock 时
 * getResource 返回 null，location 断言无从谈起。
 */
class WebResourceCacheConfigTest {

    /** 暴露 protected getHandlerMapping 的测试缝；registry.getHandlerMapping() 内部会构建并初始化各 handler。 */
    private fun buildHandlers(): Map<String, Any> {
        val registry = object : ResourceHandlerRegistry(StaticApplicationContext(), null) {
            public override fun getHandlerMapping(): SimpleUrlHandlerMapping =
                super.getHandlerMapping() as SimpleUrlHandlerMapping
        }
        WebResourceCacheConfig().addResourceHandlers(registry)
        val mapping = registry.getHandlerMapping()
            ?: error("no resource handler mapping registered")
        @Suppress("UNCHECKED_CAST")
        return mapping.urlMap as Map<String, Any>
    }

    private fun handlerOf(pattern: String): ResourceHttpRequestHandler =
        buildHandlers()[pattern] as? ResourceHttpRequestHandler
            ?: error("pattern $pattern 未注册资源处理器: ${buildHandlers().keys}")

    /** 取 handler 实际解析出的 classpath location 路径（剥掉 "classpath:" 前缀后的形式）。 */
    private fun classPathLocations(handler: ResourceHttpRequestHandler): List<String> =
        handler.locations.map { (it as ClassPathResource).path }

    @Test
    fun `hashed assets and icons get 365d public immutable caching`() {
        val expected = CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable()
        for (pattern in listOf("/assets/**", "/icons/**")) {
            val handler = handlerOf(pattern)
            assertNotNull(handler.cacheControl, "$pattern 必须显式配置 Cache-Control")
            assertEquals(
                expected.headerValue, handler.cacheControl!!.headerValue,
                "$pattern 应为 365d public immutable",
            )
        }
    }

    @Test
    fun `prefixed handlers point at their own static subdirectories because of prefix stripping`() {
        // 剥前缀语义：location 指到 static 根时请求相对名会落错目录（全量 404）。
        // 钉死 location 必须是子目录本身，且仅此一个。
        assertEquals(
            listOf("static/assets/"),
            classPathLocations(handlerOf("/assets/**")),
            "/assets 前缀的 location 必须是 static/assets/ 子目录本身（剥前缀语义）",
        )
        assertEquals(
            listOf("static/icons/"),
            classPathLocations(handlerOf("/icons/**")),
            "/icons 前缀的 location 必须是 static/icons/ 子目录本身（剥前缀语义）",
        )
    }

    @Test
    fun `entry resources under the catch-all mapping are revalidated with no-cache`() {
        assertTrue(buildHandlers().containsKey("/**"), "根通配映射必须由本配置接管（覆盖 Boot 默认）")
        val handler = handlerOf("/**")
        assertNotNull(handler.cacheControl, "根通配必须显式配置 Cache-Control")
        assertEquals("no-cache", handler.cacheControl!!.headerValue)
    }

    @Test
    fun `catch-all mapping keeps the four boot-default classpath static roots`() {
        // 无前缀可剥：location + 请求相对名恰为静态根下同名文件，保持 Boot 默认。
        assertEquals(
            listOf("META-INF/resources/", "resources/", "static/", "public/"),
            classPathLocations(handlerOf("/**")),
            "根通配应保持与 Boot 默认一致的 4 个 classpath 静态根",
        )
    }
}
