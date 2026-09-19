package com.hippo.anotherviewer.web.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

/**
 * Wave1 V1：静态资产缓存 + 压缩的配置键回归。不起 Spring 上下文——静态资
 * 产属性绑定失败只会静默回退默认值（no-store、无压缩），运行期很难发现，
 * 这里直接解析 application.yml 断言键名与值，拦住手滑写错。
 *
 * P1-3：全局 `spring.web.resources.cache.period` 已移除（它把入口 index.html /
 * sw.js 一并钉上 365d）；分级缓存在 [WebResourceCacheConfig]，见其专属测试。
 */
class StaticResourceCacheConfigTest {

    private val props by lazy {
        YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")) }.getObject()!!
    }

    @Test
    fun `no global cache period is configured anymore (entry html must not be long-cached)`() {
        assertNull(props.getProperty("spring.web.resources.cache.period"))
    }

    @Test
    fun `compression is enabled for the text mime types above 2KB`() {
        assertEquals("true", props.getProperty("server.compression.enabled"))
        assertEquals(
            "text/html, text/css, text/javascript, application/javascript, application/json, image/svg+xml",
            props.getProperty("server.compression.mime-types"),
        )
        assertEquals("2048", props.getProperty("server.compression.min-response-size"))
    }
}
