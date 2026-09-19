package com.hippo.anotherviewer.web.api

import com.hippo.anotherviewer.web.any
import com.hippo.anotherviewer.web.argThatK
import com.hippo.anotherviewer.web.captureK
import com.hippo.anotherviewer.web.config.GlobalExceptionHandler
import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.eq
import com.hippo.anotherviewer.web.service.DownloadDirIndex
import com.hippo.anotherviewer.web.service.DownloadService
import com.hippo.anotherviewer.web.service.EhAvailabilityService
import com.hippo.anotherviewer.web.service.GalleryLookupService
import com.hippo.anotherviewer.web.service.ImageCacheService
import com.hippo.anotherviewer.web.service.InMemoryJobStore
import com.hippo.anotherviewer.web.service.JobService
import com.hippo.anotherviewer.web.service.PrefetchService
import com.hippo.anotherviewer.web.service.SiteSessionManager
import com.hippo.anotherviewer.web.util.ThumbnailScaler
import org.springframework.context.ApplicationEventPublisher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

class ImageProxyControllerTest {

    companion object {
        /** 与 ImageProxyController 的 MAX_CONCURRENT_PROXY_FETCHES 对齐（源侧 private，此处显式复述上限）。 */
        private const val MAX_CONCURRENT_PROXY_FETCHES_FOR_TEST = 6
    }

    /** Records the upstream request; answers with a canned response or throws. */
    private class FakeSite(private val responder: (Request) -> Response) : Interceptor {
        val lastRequest = AtomicReference<Request?>(null)
        var callCount = 0
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            callCount++
            lastRequest.set(chain.request())
            return responder(chain.request())
        }
    }

    private fun canned(request: Request, code: Int, contentType: String, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .header("Content-Type", contentType)
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()

    /** V4 W1: 二进制上游响应（真实图片字节）。 */
    private fun cannedBytes(request: Request, code: Int, contentType: String, body: ByteArray): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .header("Content-Type", contentType)
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()

    /** V4 W1: 生成纯色测试图并按格式编码（AWT headless 可用）。 */
    private fun solidImageBytes(format: String, width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics: Graphics2D = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, format, out)
        return out.toByteArray()
    }

    private lateinit var imageCacheService: ImageCacheService
    private lateinit var sessionManager: SiteSessionManager
    private lateinit var mockMvc: MockMvc
    private lateinit var site: FakeSite
    private val config = SiteCoreConfigProperties()
    private lateinit var availability: EhAvailabilityService

    private fun setUpClient(interceptor: FakeSite) {
        `when`(sessionManager.okHttpClient)
            .thenReturn(OkHttpClient.Builder().addInterceptor(interceptor).build())
    }

    private fun probeFalseService(): EhAvailabilityService =
        EhAvailabilityService(mock(com.hippo.anotherviewer.web.service.WebProxyManager::class.java), "https://e-hentai.org", 5000, probe = { false })

    @BeforeEach
    fun setUp() {
        imageCacheService = mock(ImageCacheService::class.java)
        val galleryLookupService = mock(GalleryLookupService::class.java)
        sessionManager = mock(SiteSessionManager::class.java)
        val prefetchService = mock(PrefetchService::class.java)
        availability = probeFalseService().apply { recordSuccess() }
        // P10 required 注入：测试显式提供真实 config 与轻量 JobService 替身。
        mockMvc = MockMvcBuilders.standaloneSetup(
            ImageProxyController(
                imageCacheService, galleryLookupService, sessionManager, prefetchService,
                mock(DownloadService::class.java),
                config,
                JobService(InMemoryJobStore(), ApplicationEventPublisher {}),
                availability,
                mock(DownloadDirIndex::class.java),
            )
        )
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    // ------------------------------------------------------------------
    // Cache-hit path (legacy behavior kept intact)
    // ------------------------------------------------------------------

    @Test
    fun `proxyImage serves a cache hit without fetching`() {
        val url = "https://e-hentai.org/t/1001/cover.jpg"
        `when`(imageCacheService.getCachedImage(url)).thenReturn(byteArrayOf(1, 2, 3))
        site = FakeSite { canned(it, 200, "image/jpeg", "") }
        setUpClient(site)

        mockMvc.perform(get("/api/v1/image/proxy").param("url", url))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "image/jpeg"))

        verify(imageCacheService, never()).cacheImage(any(), any())
        org.junit.jupiter.api.Assertions.assertEquals(0, site.callCount)
    }

    // ------------------------------------------------------------------
    // W3 R4-13 fetch-on-miss
    // ------------------------------------------------------------------

    @Test
    fun `proxyImage fetch-on-miss backfills the cache and passes the content-type through`() {
        val url = "https://e-hentai.org/t/1001/cover.jpg"
        `when`(imageCacheService.getCachedImage(url)).thenReturn(null)
        site = FakeSite { canned(it, 200, "image/png", "PNGBYTES") }
        setUpClient(site)

        mockMvc.perform(get("/api/v1/image/proxy").param("url", url))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(content().string("PNGBYTES"))

        org.junit.jupiter.api.Assertions.assertEquals(url, site.lastRequest.get()!!.url.toString())
        verify(imageCacheService).cacheImage(
            eq(url),
            argThatK<ByteArray> { it.contentEquals("PNGBYTES".toByteArray()) }
        )
    }

    @Test
    // MASTER-2026-08-22 S2：上游响应超限 → 502 UPSTREAM_TOO_LARGE，不落缓存。
    fun `proxyImage fetch-on-miss aborts with 502 envelope when upstream exceeds size cap`() {
        val url = "https://e-hentai.org/t/1002/huge.jpg"
        config.proxy.maxResponseBytes = 4
        `when`(imageCacheService.getCachedImage(url)).thenReturn(null)
        site = FakeSite { canned(it, 200, "image/jpeg", "TOO-LONG-FOR-CAP") }
        setUpClient(site)

        mockMvc.perform(get("/api/v1/image/proxy").param("url", url))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.error.code").value("UPSTREAM_TOO_LARGE"))
            .andExpect(jsonPath("$.error.traceId").exists())
        verify(imageCacheService, never()).cacheImage(eq(url), argThatK<ByteArray> { true })
    }

    @Test
    fun `proxyImage never fetches non-whitelisted urls and keeps the legacy 404`() {        val url = "https://evil.example.com/img.jpg"
        `when`(imageCacheService.getCachedImage(url)).thenReturn(null)
        site = FakeSite { canned(it, 200, "image/jpeg", "stolen") }
        setUpClient(site)

        mockMvc.perform(get("/api/v1/image/proxy").param("url", url))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.error.traceId").exists())

        org.junit.jupiter.api.Assertions.assertEquals(0, site.callCount, "non-whitelisted urls must never be fetched")
        verify(imageCacheService, never()).cacheImage(any(), any())
    }

    @Test
    fun `proxyImage returns the 404 envelope when the site is unreachable`() {
        val url = "https://e-hentai.org/t/1001/cover.jpg"
        `when`(imageCacheService.getCachedImage(url)).thenReturn(null)
        site = FakeSite { throw IOException("connect timed out") }
        setUpClient(site)

        mockMvc.perform(get("/api/v1/image/proxy").param("url", url))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.status").value(404))
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.error.traceId").exists())

        verify(imageCacheService, never()).cacheImage(any(), any())
    }

    @Test
    fun `proxyImage returns the 404 envelope when the site answers with an error status`() {
        val url = "https://e-hentai.org/t/9999/cover.jpg"
        `when`(imageCacheService.getCachedImage(url)).thenReturn(null)
        site = FakeSite { canned(it, 404, "text/html", "not here") }
        setUpClient(site)

        mockMvc.perform(get("/api/v1/image/proxy").param("url", url))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))

        verify(imageCacheService, never()).cacheImage(any(), any())
    }

    // ------------------------------------------------------------------

    @Test
    fun `streamGalleryImage rejects a negative page with 404 uniform envelope`() {
        mockMvc.perform(get("/api/v1/image/123/-1"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.status").value(404))
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.error.traceId").exists())
    }

    // ------------------------------------------------------------------
    // EH DOWN 熔断：cache/pushed 命中之后、上游 fetch 之前秒回 EH_UNAVAILABLE
    // ------------------------------------------------------------------

    @Test
    fun `proxyImage miss returns 404 EH_UNAVAILABLE without any upstream request when blocked`() {
        val url = "https://e-hentai.org/t/1001/cover.jpg"
        `when`(imageCacheService.getCachedImage(url)).thenReturn(null)
        site = FakeSite { canned(it, 200, "image/jpeg", "") }
        setUpClient(site)
        availability.recordFailure("connect timed out")

        mockMvc.perform(get("/api/v1/image/proxy").param("url", url))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("EH_UNAVAILABLE"))
            .andExpect(jsonPath("$.error.message").value("EH 平台当前不可达，仅显示本地内容"))
            .andExpect(jsonPath("$.error.traceId").exists())

        org.junit.jupiter.api.Assertions.assertEquals(0, site.callCount, "no upstream fetch while DOWN")
        verify(imageCacheService, never()).cacheImage(any(), any())
    }

    @Test
    fun `proxyImage cache hit is still served while DOWN`() {
        val url = "https://e-hentai.org/t/1001/cover.jpg"
        `when`(imageCacheService.getCachedImage(url)).thenReturn(byteArrayOf(1, 2, 3))
        availability.recordFailure("connect timed out")

        mockMvc.perform(get("/api/v1/image/proxy").param("url", url))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "image/jpeg"))
    }

    @Test
    fun `streamGalleryImage returns 404 EH_UNAVAILABLE on cache miss when blocked`() {
        `when`(imageCacheService.findCachedPageFile(anyLong(), anyInt())).thenReturn(null)
        `when`(imageCacheService.getCachedImageByKey(anyLong(), anyInt())).thenReturn(null)
        availability.recordFailure("connect timed out")

        mockMvc.perform(get("/api/v1/image/555/0"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("EH_UNAVAILABLE"))
            .andExpect(jsonPath("$.error.message").value("EH 平台当前不可达，仅显示本地内容"))
    }

    // ------------------------------------------------------------------
    // P3: /proxy in-flight 合并 + 全局并发上限 + P4 10s 超时 tag
    // ------------------------------------------------------------------

    @Test
    fun `proxyImage concurrent misses for the same url share one upstream fetch`() {
        val url = "https://e-hentai.org/t/1001/cover.jpg"
        `when`(imageCacheService.getCachedImage(url)).thenReturn(null)
        // 第一个请求到达上游后挂住，直到其余请求全部进入合并等待。
        val upstreamEntered = java.util.concurrent.CountDownLatch(1)
        val releaseUpstream = java.util.concurrent.CountDownLatch(1)
        site = FakeSite { req ->
            upstreamEntered.countDown()
            releaseUpstream.await(5, java.util.concurrent.TimeUnit.SECONDS)
            canned(req, 200, "image/png", "PNGBYTES")
        }
        setUpClient(site)

        fun perform() = Thread {
            try {
                mockMvc.perform(get("/api/v1/image/proxy").param("url", url))
                    .andExpect(status().isOk)
                    .andExpect(header().string("Content-Type", "image/png"))
                    .andExpect(content().string("PNGBYTES"))
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }

        val first = perform().apply { start() }
        // upstreamEntered 触发 ⇒ 首个 future 已注册在途，此后到达的请求必然命中合并。
        org.junit.jupiter.api.Assertions.assertTrue(upstreamEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val others = (1..4).map { perform().apply { start() } }
        Thread.sleep(200) // 等其余请求进入 join 等待
        releaseUpstream.countDown()
        first.join(10_000)
        others.forEach { it.join(10_000) }

        org.junit.jupiter.api.Assertions.assertEquals(1, site.callCount, "same url must share one upstream fetch")
        verify(imageCacheService).cacheImage(
            eq(url),
            argThatK<ByteArray> { it.contentEquals("PNGBYTES".toByteArray()) }
        )
    }

    @Test
    fun `proxyImage caps concurrent upstream fetches at the global semaphore`() {
        `when`(imageCacheService.getCachedImage(org.mockito.ArgumentMatchers.anyString())).thenReturn(null)
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val maxObserved = java.util.concurrent.atomic.AtomicInteger(0)
        site = FakeSite { req ->
            val now = inFlight.incrementAndGet()
            maxObserved.updateAndGet { prev -> maxOf(prev, now) }
            Thread.sleep(150)
            inFlight.decrementAndGet()
            canned(req, 200, "image/jpeg", "X")
        }
        setUpClient(site)

        val threads = (1..12).map { i ->
            Thread {
                try {
                    mockMvc.perform(
                        get("/api/v1/image/proxy").param("url", "https://e-hentai.org/t/200$i/thumb$i.jpg")
                    ).andExpect(status().isOk)
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(20_000) }

        org.junit.jupiter.api.Assertions.assertEquals(12, site.callCount)
        // P3: 全局 Semaphore(6) —— 25 卡首开按 6 并发批次排队，绝不超限。
        org.junit.jupiter.api.Assertions.assertTrue(
            maxObserved.get() in 1..MAX_CONCURRENT_PROXY_FETCHES_FOR_TEST,
            "observed concurrency ${maxObserved.get()} must be within the semaphore cap"
        )
    }

    @Test
    fun `proxyImage tags the upstream request with the 10s per-request timeout (P4)`() {
        val url = "https://e-hentai.org/t/1001/cover.jpg"
        `when`(imageCacheService.getCachedImage(url)).thenReturn(null)
        site = FakeSite { canned(it, 200, "image/jpeg", "BYTES") }
        setUpClient(site)

        mockMvc.perform(get("/api/v1/image/proxy").param("url", url))
            .andExpect(status().isOk)

        val seen = site.lastRequest.get()!!
        org.junit.jupiter.api.Assertions.assertEquals(
            10, seen.tag(Int::class.javaObjectType),
            "thumbnail fetch must carry the 10s per-request max-time tag"
        )
    }

    // ------------------------------------------------------------------
    // V4 W1: `w` 缩略图宽度（契约见 ThumbnailScaler；纯函数级断言在
    // ThumbnailScalerTest，此处覆盖控制器级行为：缓存键、回退、上游共享）。
    // ------------------------------------------------------------------

    @Test
    fun `proxyImage w scales the fetched cover down and caches it under the w-derived key`() {
        val url = "https://e-hentai.org/t/1001/cover.jpg"
        val full = solidImageBytes("jpeg", 400, 200)
        `when`(imageCacheService.getCachedImage(url)).thenReturn(null)
        site = FakeSite { cannedBytes(it, 200, "image/jpeg", full) }
        setUpClient(site)

        val result = mockMvc.perform(get("/api/v1/image/proxy").param("url", url).param("w", "100"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "image/jpeg"))
            .andReturn()

        val decoded = ImageIO.read(ByteArrayInputStream(result.response.contentAsByteArray))
        org.junit.jupiter.api.Assertions.assertEquals(100, decoded.width)
        org.junit.jupiter.api.Assertions.assertEquals(50, decoded.height, "高度按比例缩放")

        // 全尺寸回填行为不变 + 缩放产物写入独立 w 键（不同宽度互不污染）。
        verify(imageCacheService).cacheImage(eq(url), argThatK<ByteArray> { it.contentEquals(full) })
        verify(imageCacheService).cacheImage(
            eq(ThumbnailScaler.thumbnailCacheKey(url, 100)),
            argThatK<ByteArray> {
                val img = ImageIO.read(ByteArrayInputStream(it))
                img.width == 100 && img.height == 50
            }
        )
    }

    @Test
    fun `proxyImage w serves a scaled cache hit without consulting the upstream or the full entry`() {
        val url = "https://e-hentai.org/t/1001/cover.jpg"
        val scaled = solidImageBytes("jpeg", 80, 60)
        `when`(imageCacheService.getCachedImage(ThumbnailScaler.thumbnailCacheKey(url, 80))).thenReturn(scaled)
        site = FakeSite { cannedBytes(it, 200, "image/jpeg", ByteArray(0)) }
        setUpClient(site)

        val result = mockMvc.perform(get("/api/v1/image/proxy").param("url", url).param("w", "80"))
            .andExpect(status().isOk)
            .andReturn()

        val decoded = ImageIO.read(ByteArrayInputStream(result.response.contentAsByteArray))
        org.junit.jupiter.api.Assertions.assertEquals(80, decoded.width)
        org.junit.jupiter.api.Assertions.assertEquals(60, decoded.height)
        org.junit.jupiter.api.Assertions.assertEquals(0, site.callCount, "缩放缓存命中绝不触发上游")
        verify(imageCacheService, never()).getCachedImage(url)
        verify(imageCacheService, never()).cacheImage(any(), any())
    }

    @Test
    fun `proxyImage invalid w values fall back to the original size and never touch the scaled cache`() {
        val url = "https://e-hentai.org/t/1001/cover.jpg"
        val full = solidImageBytes("jpeg", 400, 200)
        `when`(imageCacheService.getCachedImage(url)).thenReturn(null)
        site = FakeSite { cannedBytes(it, 200, "image/jpeg", full) }
        setUpClient(site)

        // 非数字 / 0 / 负数 / 超大 —— 一律 200 原尺寸，绝不 4xx。
        for (bad in listOf("abc", "0", "-3", "5000")) {
            val result = mockMvc.perform(get("/api/v1/image/proxy").param("url", url).param("w", bad))
                .andExpect(status().isOk)
                .andReturn()
            val decoded = ImageIO.read(ByteArrayInputStream(result.response.contentAsByteArray))
            org.junit.jupiter.api.Assertions.assertEquals(400, decoded.width, "w=$bad 必须回退原尺寸")
            org.junit.jupiter.api.Assertions.assertEquals(200, decoded.height, "w=$bad 必须回退原尺寸")
        }

        val keyCaptor = ArgumentCaptor.forClass(String::class.java)
        val bytesCaptor = ArgumentCaptor.forClass(ByteArray::class.java)
        verify(imageCacheService, times(4)).cacheImage(captureK<String>(keyCaptor), captureK<ByteArray>(bytesCaptor))
        org.junit.jupiter.api.Assertions.assertTrue(
            keyCaptor.allValues.all { it == url },
            "非法 w 绝不写缩放缓存键，全尺寸键保持不变"
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            bytesCaptor.allValues.all { it.contentEquals(full) },
            "回退路径写入缓存的仍是全尺寸字节"
        )
    }

    @Test
    fun `proxyImage w falls back to the original bytes when the upstream body is not decodable`() {
        val url = "https://e-hentai.org/t/1001/cover.jpg"
        `when`(imageCacheService.getCachedImage(url)).thenReturn(null)
        site = FakeSite { cannedBytes(it, 200, "image/jpeg", "NOT-AN-IMAGE".toByteArray()) }
        setUpClient(site)

        val result = mockMvc.perform(get("/api/v1/image/proxy").param("url", url).param("w", "100"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "image/jpeg"))
            .andReturn()

        org.junit.jupiter.api.Assertions.assertTrue(
            result.response.contentAsByteArray.contentEquals("NOT-AN-IMAGE".toByteArray()),
            "缩放失败回退原字节，绝不 4xx/5xx"
        )
        verify(imageCacheService).cacheImage(eq(url), any())
        verify(imageCacheService, never()).cacheImage(
            eq(ThumbnailScaler.thumbnailCacheKey(url, 100)), argThatK<ByteArray> { true }
        )
    }

    @Test
    fun `proxyImage w does not bypass the site whitelist`() {
        val url = "https://evil.example.com/img.jpg"
        `when`(imageCacheService.getCachedImage(url)).thenReturn(null)
        site = FakeSite { cannedBytes(it, 200, "image/jpeg", solidImageBytes("jpeg", 400, 200)) }
        setUpClient(site)

        mockMvc.perform(get("/api/v1/image/proxy").param("url", url).param("w", "100"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))

        org.junit.jupiter.api.Assertions.assertEquals(0, site.callCount, "w 不得绕过白名单")
    }
}
