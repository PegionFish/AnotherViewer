package com.hippo.anotherviewer.web.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.context.ApplicationContext
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler
import java.util.concurrent.TimeUnit

/**
 * P1-3：静态资源缓存分级配置的轻量单测（不起 Spring 上下文）——直接驱动
 * [WebResourceCacheConfig.addResourceHandlers]，从注册结果（SimpleUrlHandlerMapping
 * 的 urlMap）里取各 pattern 的 [ResourceHttpRequestHandler] 断言其 Cache-Control。
 *
 * 断言口径：assets/icons 两个前缀（内容寻址产物 + 静态图标）365d public
 * immutable；根通配映射（index.html、sw.js、manifest.json 所在）no-cache——
 * 入口资源可缓存但每次回源验证，发版即时生效。
 */
class WebResourceCacheConfigTest {

    /** 暴露 protected getHandlerMapping 的测试缝；未过 afterPropertiesSet，读 urlMap 原始注册表。 */
    private fun buildHandlers(): Map<String, Any> {
        val registry = object : ResourceHandlerRegistry(mock(ApplicationContext::class.java), null) {
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
    fun `entry resources under the catch-all mapping are revalidated with no-cache`() {
        assertTrue(buildHandlers().containsKey("/**"), "根通配映射必须由本配置接管（覆盖 Boot 默认）")
        val handler = handlerOf("/**")
        assertNotNull(handler.cacheControl, "根通配必须显式配置 Cache-Control")
        assertEquals("no-cache", handler.cacheControl!!.headerValue)
    }
}
