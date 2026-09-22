package com.hippo.anotherviewer.web.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files

/**
 * 静态资源服务的端到端回归钉子（WebResourceCacheConfig 的生产事故级回归反演）：
 * **@SpringBootTest 真实端口**（RANDOM_PORT + TestRestTemplate），用真实 HTTP
 * 请求验证「jar/classpath 里真实存在的前端产物被正确的 handler 以正确的缓存
 * 策略命中」。
 *
 * 背景：Framework 6.1+ 对带前缀的资源 pattern 是「剥前缀」解析（请求相对名 =
 * location + 去前缀路径）。期初把 assets 与 icons 两个前缀的 location 误配为
 * static 根，配置级单测只看 CacheControl 看不出来，导致 jar 部署后全量带 hash
 * 产物与图标 404（前端白屏）。本测试**从 classpath 枚举真实构建产物文件名**
 * （非测试自造），任何让前缀 handler 解析错位的回归（location 指错、pattern 被
 * 根通配吞掉等）都会在此处以 404/错缓存头现形；另钉入口资源 no-cache 与 SPA
 * 深链回退（[SpaWebConfig] 的 forward 到 index.html）。
 *
 * 为何用真实端口而非 MockMvc：MockMvc 对视图控制器的 forward 只**记录**
 * forwardedUrl、不真正执行转发目标，SPA 深链的响应正文拿不到；真实 Tomcat
 * 端口才能断言「深链回退后的正文确为入口 HTML」，也与事故实测口径（真实
 * HTTP）一致。
 *
 * 基建手法与 IntegrityE2ETest / Phase2E2ETest 同口径：@DynamicPropertySource
 * 注入临时 data 目录（**必须预先建目录**，应用不代建）+ 临时 SQLite，一切在
 * 本地嵌入环境跑，不连任何远程服务器。纯静态资源链路，无需任何 mock。
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class StaticAssetsServingE2ETest {

    companion object {
        private val dbDir = Files.createTempDirectory("av-e2e-static-db")
        private val dataRoot = Files.createTempDirectory("av-e2e-static-data")

        /** 下载根 + 日志目录必须预先 mkdir：应用只往里写，不代建目录（既有 E2E 同款口径）。 */
        private val downloadsDir = Files.createDirectories(dataRoot.resolve("downloads"))
        private val logsDir = Files.createDirectories(dataRoot.resolve("logs"))

        @JvmStatic
        @DynamicPropertySource
        fun e2eProperties(registry: DynamicPropertyRegistry) {
            // 临时数据目录 + 临时 SQLite（先例：IntegrityE2ETest / BackfillServiceTest）。
            registry.add("anotherviewer.data-dir") { dataRoot.toAbsolutePath().toString() }
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${dbDir.resolve("e2e-static.db")}?journal_mode=WAL&busy_timeout=30000"
            }
            registry.add("spring.datasource.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.community.dialect.SQLiteDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
            // DownloadDirIndex 根指纹检查不节流（与既有 E2E 基建一致）。
            registry.add("anotherviewer.download.dir-index-refresh-ms") { "0" }
        }

        /** 从 classpath 枚举前端构建产物里的真实文件名（排序取首，保证确定性）。 */
        private fun realStaticFile(directory: String, extension: String): String =
            PathMatchingResourcePatternResolver()
                .getResources("classpath:/static/$directory/*.$extension")
                .mapNotNull { it.filename }
                .sorted()
                .firstOrNull()
                ?: error("classpath:/static/$directory/ 下没有 .$extension 文件——前端构建产物缺失，本测试断言失效")

        private fun classpathBytes(path: String): ByteArray =
            StaticAssetsServingE2ETest::class.java.getResourceAsStream(path)?.readBytes()
                ?: error("classpath 缺少 $path")
    }

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    private fun get(path: String): ResponseEntity<ByteArray> =
        restTemplate.getForEntity(path, ByteArray::class.java)

    private fun cacheControlOf(response: ResponseEntity<ByteArray>): String =
        requireNotNull(response.headers.getFirst("Cache-Control")) {
            "GET ${response.headers.location} 缺少 Cache-Control 头"
        }

    @Test
    fun `real vite js asset is served through the assets handler with 365d immutable caching`() {
        val name = realStaticFile("assets", "js")
        val response = get("/assets/$name")
        assertTrue(response.statusCode.is2xxSuccessful, "/assets/$name 应为 2xx，实际 ${response.statusCode}")
        val cacheControl = cacheControlOf(response)
        assertTrue("max-age=31536000" in cacheControl, "/assets/$name Cache-Control 应含 max-age=31536000: $cacheControl")
        assertTrue("immutable" in cacheControl, "/assets/$name Cache-Control 应含 immutable: $cacheControl")
        // 内容逐字节等于 classpath 上的同名产物：证明剥前缀后落点正确。
        assertTrue(
            response.body!!.contentEquals(classpathBytes("/static/assets/$name")),
            "/assets/$name 响应体应与 classpath 同名产物逐字节一致",
        )
    }

    @Test
    fun `real vite css asset is served through the assets handler with 365d immutable caching`() {
        val name = realStaticFile("assets", "css")
        val response = get("/assets/$name")
        assertTrue(response.statusCode.is2xxSuccessful, "/assets/$name 应为 2xx，实际 ${response.statusCode}")
        val cacheControl = cacheControlOf(response)
        assertTrue("max-age=31536000" in cacheControl, "/assets/$name Cache-Control 应含 max-age=31536000: $cacheControl")
        assertTrue("immutable" in cacheControl, "/assets/$name Cache-Control 应含 immutable: $cacheControl")
        assertTrue(
            response.body!!.contentEquals(classpathBytes("/static/assets/$name")),
            "/assets/$name 响应体应与 classpath 同名产物逐字节一致",
        )
    }

    @Test
    fun `app icon is served through the icons handler with 365d immutable caching`() {
        val name = realStaticFile("icons", "png")
        val response = get("/icons/$name")
        assertTrue(response.statusCode.is2xxSuccessful, "/icons/$name 应为 2xx，实际 ${response.statusCode}")
        val cacheControl = cacheControlOf(response)
        assertTrue("max-age=31536000" in cacheControl, "/icons/$name Cache-Control 应含 max-age=31536000: $cacheControl")
        assertTrue("immutable" in cacheControl, "/icons/$name Cache-Control 应含 immutable: $cacheControl")
        assertTrue(
            response.body!!.contentEquals(classpathBytes("/static/icons/$name")),
            "/icons/$name 响应体应与 classpath 同名产物逐字节一致",
        )
    }

    @Test
    fun `entry html is served with no-cache and never immutable`() {
        val response = get("/index.html")
        assertTrue(response.statusCode.is2xxSuccessful, "/index.html 应为 2xx，实际 ${response.statusCode}")
        val cacheControl = cacheControlOf(response)
        assertTrue("no-cache" in cacheControl, "/index.html Cache-Control 应含 no-cache: $cacheControl")
        assertFalse("immutable" in cacheControl, "/index.html 不得被钉上 immutable: $cacheControl")
        val body = String(response.body!!, Charsets.UTF_8)
        assertTrue(body.lowercase().contains("<!doctype html"), "index.html 正文应为 SPA 入口 HTML")
    }

    @Test
    fun `manifest is served with no-cache and never immutable`() {
        val response = get("/manifest.json")
        assertTrue(response.statusCode.is2xxSuccessful, "/manifest.json 应为 2xx，实际 ${response.statusCode}")
        val cacheControl = cacheControlOf(response)
        assertTrue("no-cache" in cacheControl, "/manifest.json Cache-Control 应含 no-cache: $cacheControl")
        assertFalse("immutable" in cacheControl, "/manifest.json 不得被钉上 immutable: $cacheControl")
    }

    @Test
    fun `spa deep link falls back to the entry html`() {
        // SpaWebConfig 的视图控制器把非 API 深链 forward 到 index.html（真实端口下
        // 转发真正执行；MockMvc 只记录 forwardedUrl 拿不到正文，见类 KDoc）。
        val response = get("/gallery/123")
        assertTrue(response.statusCode.is2xxSuccessful, "/gallery/123 应为 2xx，实际 ${response.statusCode}")
        val body = String(response.body!!, Charsets.UTF_8).lowercase()
        assertTrue(
            body.contains("<!doctype html") || body.contains("<html"),
            "SPA 深链应回退到入口 HTML，实际正文开头: ${body.take(120)}",
        )
    }
}
