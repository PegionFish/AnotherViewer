package com.hippo.anotherviewer.web.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

/**
 * Wave1 V1：静态资产缓存 + 压缩的配置键回归。不起 Spring 上下文——静态资
 * 产属性绑定失败只会静默回退默认值（no-store、无压缩），运行期很难发现，
 * 这里直接解析 application.yml 断言键名与值，拦住手滑写错。
 */
class StaticResourceCacheConfigTest {

    private val props by lazy {
        YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")) }.getObject()!!
    }

    @Test
    fun `static assets get a 365d cache period`() {
        assertEquals("365d", props.getProperty("spring.web.resources.cache.period"))
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
