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
import com.hippo.anotherviewer.web.service.storage.DefaultSystemFiles
import com.hippo.anotherviewer.web.service.storage.PoolReadGate
import com.hippo.anotherviewer.web.service.storage.PoolReadPermit
import com.hippo.anotherviewer.web.service.storage.StorageProfileService
import com.hippo.anotherviewer.web.service.storage.StorageTuning
import com.hippo.anotherviewer.web.entity.PageFileHashEntity
import com.hippo.anotherviewer.web.repository.PageFileHashRepository
import com.hippo.anotherviewer.web.util.HttpCacheSupport
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
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
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
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
    private lateinit var galleryLookupService: GalleryLookupService
    private lateinit var sessionManager: SiteSessionManager
    private lateinit var mockMvc: MockMvc
    private lateinit var site: FakeSite
    private val config = SiteCoreConfigProperties()
    private lateinit var availability: EhAvailabilityService
    private lateinit var pageFileHashRepository: PageFileHashRepository

    private fun setUpClient(interceptor: FakeSite) {
        `when`(sessionManager.okHttpClient)
            .thenReturn(OkHttpClient.Builder().addInterceptor(interceptor).build())
    }

    private fun probeFalseService(): EhAvailabilityService =
        EhAvailabilityService(mock(com.hippo.anotherviewer.web.service.WebProxyManager::class.java), "https://e-hentai.org", 5000, probe = { false })

    /**
     * P10 required 注入：测试显式提供全部依赖。W3-P 起可替身池读闸门与
     * StorageTuning——默认「SSD 形态」直通闸门（不设闸、无预读副作用外溢，
     * 预读开关按未探测的保守 UNKNOWN 打开但默认 mock 的 DownloadDirIndex
     * 查不到池文件，不会真正触发）。
     */
    private fun buildMvc(
        gate: PoolReadGate = PoolReadGate({ Int.MAX_VALUE }, PoolReadGate.DEFAULT_FORCE_GRANT_TIMEOUT_MS),
        tuning: StorageTuning = StorageTuning(StorageProfileService(DefaultSystemFiles())),
        dirIndex: DownloadDirIndex = mock(DownloadDirIndex::class.java),
        downloadService: DownloadService = mock(DownloadService::class.java),
    ): MockMvc {
        val prefetchService = mock(PrefetchService::class.java)
        return MockMvcBuilders.standaloneSetup(
            ImageProxyController(
                imageCacheService, galleryLookupService, sessionManager, prefetchService,
                downloadService,
                config,
                JobService(InMemoryJobStore(), ApplicationEventPublisher {}),
                availability,
                dirIndex,
                pageFileHashRepository,
                tuning,
                gate,
            )
        )
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @BeforeEach
    fun setUp() {
        imageCacheService = mock(ImageCacheService::class.java)
        galleryLookupService = mock(GalleryLookupService::class.java)
        sessionManager = mock(SiteSessionManager::class.java)
        pageFileHashRepository = mock(PageFileHashRepository::class.java)
        availability = probeFalseService().apply { recordSuccess() }
        mockMvc = buildMvc()
    }

    /** W3-P: 记录 acquire (directory, page) 键的真实闸门（默认不限流）。 */
    private class RecordingGate(limit: Int) : PoolReadGate({ limit }, DEFAULT_FORCE_GRANT_TIMEOUT_MS) {
        val acquired = CopyOnWriteArrayList<Pair<String, Int>>()

        override fun acquire(directory: String, page: Int): PoolReadPermit {
            acquired.add(directory to page)
            return super.acquire(directory, page)
        }
    }

    /** W3-P: 建一个 App-pushed 池目录 `{root}/{gid}/0001.jpg / 0002.jpg`（文件 1-based）。 */
    private fun poolDirWithTwoPages(@TempDir root: Path, gid: Long): java.io.File {
        val dir = java.io.File(root.toFile(), gid.toString()).apply { mkdirs() }
        java.io.File(dir, "0001.jpg").writeBytes(byteArrayOf(1, 2, 3, 4))
        java.io.File(dir, "0002.jpg").writeBytes(byteArrayOf(9, 9, 9))
        return dir
    }

    /** P-S2: 建池目录 `{root}/{gid}/NNNN.jpg`（文件名 1-based = apiPage+1），返回目录。 */
    private fun poolDirWithPageBytes(@TempDir root: Path, gid: Long, apiPage: Int, bytes: ByteArray): java.io.File {
        val dir = java.io.File(root.toFile(), gid.toString()).apply { mkdirs() }
        java.io.File(dir, "%04d.jpg".format(apiPage + 1)).writeBytes(bytes)
        return dir
    }

    /** P-S2: 镜像 ImageCacheService.urlKey / HttpCacheSupport.sha256Hex（断言 /proxy ETag 用）。 */
    private fun sha256Hex(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** P-S2: 造一行 sha256 基线（page 0-based，与 API 页号同口径）。 */
    private fun baselineRow(gid: Long, apiPage: Int, hash: String): PageFileHashEntity {
        val row = PageFileHashEntity()
        row.gid = gid
        row.page = apiPage
        row.hash = hash
        row.algo = "sha256"
        return row
    }

    private fun ssdTuning(): StorageTuning {
        val service = StorageProfileService(DefaultSystemFiles(), profileOverride = "ssd")
        service.detect("/nonexistent-pool-path")
        return StorageTuning(service)
    }

    private fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met within ${timeoutMs}ms")
            Thread.sleep(10)
        }
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

    // ------------------------------------------------------------------
    // W3-P: 存储池读闸门 + 池页翻页预读 N+1
    // ------------------------------------------------------------------

    @Test
    fun `pushed pool page serve passes the pool gate and prefetches the next pool page`(@TempDir root: Path) {
        val gid = 555L
        poolDirWithTwoPages(root, gid)
        config.download.path = root.toString()
        val gate = RecordingGate(Int.MAX_VALUE)
        mockMvc = buildMvc(gate = gate, dirIndex = DownloadDirIndex(config))

        mockMvc.perform(get("/api/v1/image/555/0"))
            .andExpect(status().isOk)
            .andExpect(content().bytes(byteArrayOf(1, 2, 3, 4)))

        assertTrue(
            gate.acquired.contains("555" to 0),
            "pushed serve must acquire the pool gate with (gid, page), got ${gate.acquired}"
        )

        // 翻页预读 N+1：后台读下一页池文件 → serve 同款 cacheImageByKey 预热，且同样过闸。
        waitUntil { gate.acquired.contains("555" to 1) }
        verify(imageCacheService, timeout(2_000)).cacheImageByKey(
            eq(gid), eq(1),
            argThatK<ByteArray> { it.contentEquals(byteArrayOf(9, 9, 9)) },
            eq("jpg"),
        )
    }

    @Test
    fun `pool page prefetch stays off on the ssd profile`(@TempDir root: Path) {
        poolDirWithTwoPages(root, 556L)
        config.download.path = root.toString()
        val gate = RecordingGate(Int.MAX_VALUE)
        val tuning = ssdTuning()
        org.junit.jupiter.api.Assertions.assertFalse(tuning.pageTurnPrefetchEnabled, "SSD 必须关闭翻页预读")
        mockMvc = buildMvc(gate = gate, tuning = tuning, dirIndex = DownloadDirIndex(config))

        mockMvc.perform(get("/api/v1/image/556/0"))
            .andExpect(status().isOk)
            .andExpect(content().bytes(byteArrayOf(1, 2, 3, 4)))

        assertTrue(gate.acquired.contains("556" to 0), "serve itself still passes the gate")
        Thread.sleep(150) // 给任何错误的预读提交留出现形时间
        assertTrue(
            gate.acquired.none { it.first == "556" && it.second == 1 },
            "SSD profile must not prefetch the next pool page"
        )
        verify(imageCacheService, never()).cacheImageByKey(anyLong(), anyInt(), any(), anyString())
    }

    @Test
    fun `upstream page fetch holds a pool gate ticket for the gallery and page`() {
        val gate = RecordingGate(Int.MAX_VALUE)
        mockMvc = buildMvc(gate = gate)
        `when`(galleryLookupService.findToken(42L)).thenReturn("tok")
        `when`(galleryLookupService.fetchImageUrl(42L, "tok", 4)).thenReturn("https://s.exhentai.org/fullimg.jpg")
        site = FakeSite { canned(it, 200, "image/jpeg", "PAGEBYTES") }
        setUpClient(site)

        mockMvc.perform(get("/api/v1/image/42/3"))
            .andExpect(status().isOk)
            .andExpect(content().string("PAGEBYTES"))

        assertTrue(
            gate.acquired.contains("42" to 3),
            "upstream fetch serve must pass the gate with (gid, page), got ${gate.acquired}"
        )
        verify(imageCacheService).cacheImageByKey(
            eq(42L), eq(3),
            argThatK<ByteArray> { it.contentEquals("PAGEBYTES".toByteArray()) },
            eq("jpg"),
        )
    }

    @Test
    fun `proxy fetch-on-miss passes the pool gate while scaled cache hits stay ungated`() {
        val gate = RecordingGate(Int.MAX_VALUE)
        val url = "https://e-hentai.org/t/3001/cover.jpg"
        `when`(imageCacheService.getCachedImage(url)).thenReturn(null)
        site = FakeSite { canned(it, 200, "image/jpeg", "PNGBYTES") }
        setUpClient(site)
        mockMvc = buildMvc(gate = gate)

        mockMvc.perform(get("/api/v1/image/proxy").param("url", url))
            .andExpect(status().isOk)

        assertTrue(
            gate.acquired.any { it.first == url && it.second == 0 },
            "fetch-on-miss must pass the gate (url-keyed, page 0), got ${gate.acquired}"
        )

        // 缩放缓存命中路径不碰任何存储——不过闸。
        val hitGate = RecordingGate(Int.MAX_VALUE)
        `when`(imageCacheService.getCachedImage(ThumbnailScaler.thumbnailCacheKey(url, 80)))
            .thenReturn(solidImageBytes("jpeg", 80, 60))
        mockMvc = buildMvc(gate = hitGate)
        mockMvc.perform(get("/api/v1/image/proxy").param("url", url).param("w", "80"))
            .andExpect(status().isOk)
        assertTrue(hitGate.acquired.isEmpty(), "scaled cache hit must not touch the gate, got ${hitGate.acquired}")
    }

    @Test
    fun `pool gate with limit 3 caps concurrent proxy upstream fetches below the legacy semaphore`() {
        mockMvc = buildMvc(gate = PoolReadGate({ 3 }, PoolReadGate.DEFAULT_FORCE_GRANT_TIMEOUT_MS))
        `when`(imageCacheService.getCachedImage(anyString())).thenReturn(null)
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val maxObserved = java.util.concurrent.atomic.AtomicInteger(0)
        site = FakeSite { req ->
            val now = inFlight.incrementAndGet()
            maxObserved.updateAndGet { prev -> maxOf(prev, now) }
            Thread.sleep(120)
            inFlight.decrementAndGet()
            canned(req, 200, "image/jpeg", "X")
        }
        setUpClient(site)

        val threads = (1..9).map { i ->
            Thread {
                try {
                    mockMvc.perform(
                        get("/api/v1/image/proxy").param("url", "https://e-hentai.org/t/400$i/thumb$i.jpg")
                    ).andExpect(status().isOk)
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(20_000) }

        org.junit.jupiter.api.Assertions.assertEquals(9, site.callCount)
        assertTrue(
            maxObserved.get() in 1..3,
            "real gate limit 3 must cap upstream concurrency (legacy semaphore allows 6), observed ${maxObserved.get()}"
        )
    }

    // ------------------------------------------------------------------
    // P-S2: serveFile 流式化（Range 逐字节对拍）+ ETag / If-None-Match / 304
    // ------------------------------------------------------------------

    /**
     * 流式 serve 对拍（验收「先测现状」的格式基线，期望值取自旧 readBytes 实现）：
     * 200 全量、206 中段/1 字节/后缀 Range、416——头格式与字节逐项断言。
     */
    @Test
    fun `pool page streaming serves byte-exact range responses in the legacy header format`(@TempDir root: Path) {
        val gid = 700L
        val bytes = ByteArray(1000) { (it % 251).toByte() }
        poolDirWithPageBytes(root, gid, 0, bytes)
        config.download.path = root.toString()
        mockMvc = buildMvc(dirIndex = DownloadDirIndex(config))

        // 全量 200：字节与文件逐字节一致 + 既有头格式（Content-Length 由实现补齐）。
        mockMvc.perform(get("/api/v1/image/$gid/0"))
            .andExpect(status().isOk)
            .andExpect(content().bytes(bytes))
            .andExpect(header().string("Content-Type", "image/jpeg"))
            .andExpect(header().string("Accept-Ranges", "bytes"))
            .andExpect(header().string("Cache-Control", "max-age=86400"))
            .andExpect(header().string("Content-Length", "1000"))

        // 中段 Range：206 + 既有头格式，字节 = 文件[100..299]。
        mockMvc.perform(get("/api/v1/image/$gid/0").header("Range", "bytes=100-299"))
            .andExpect(status().isPartialContent)
            .andExpect(content().bytes(bytes.copyOfRange(100, 300)))
            .andExpect(header().string("Content-Range", "bytes 100-299/1000"))
            .andExpect(header().string("Content-Length", "200"))
            .andExpect(header().string("Content-Type", "image/jpeg"))
            .andExpect(header().string("Accept-Ranges", "bytes"))
            .andExpect(header().string("Cache-Control", "max-age=86400"))

        // 1 字节 Range：响应恰好 1 字节（流式实现必须流上定位，绝不整文件读后截取）。
        mockMvc.perform(get("/api/v1/image/$gid/0").header("Range", "bytes=42-42"))
            .andExpect(status().isPartialContent)
            .andExpect(content().bytes(byteArrayOf(bytes[42])))
            .andExpect(header().string("Content-Range", "bytes 42-42/1000"))
            .andExpect(header().string("Content-Length", "1"))

        // 后缀 Range：bytes=-3 → 末 3 字节。
        mockMvc.perform(get("/api/v1/image/$gid/0").header("Range", "bytes=-3"))
            .andExpect(status().isPartialContent)
            .andExpect(content().bytes(bytes.copyOfRange(997, 1000)))
            .andExpect(header().string("Content-Range", "bytes 997-999/1000"))

        // 越界 → 416（既有格式：只带 Content-Range）。
        mockMvc.perform(get("/api/v1/image/$gid/0").header("Range", "bytes=1000-2000"))
            .andExpect(status().isRequestedRangeNotSatisfiable)
            .andExpect(header().string("Content-Range", "bytes */1000"))
    }

    @Test
    fun `pool page serve carries weak etag and if-none-match hit returns 304 before the gate`(@TempDir root: Path) {
        val gid = 701L
        val bytes = byteArrayOf(1, 2, 3, 4)
        val dir = poolDirWithPageBytes(root, gid, 0, bytes)
        val file = java.io.File(dir, "0001.jpg")
        config.download.path = root.toString()
        val gate = RecordingGate(Int.MAX_VALUE)
        val probe = DownloadDirIndex(config)
        mockMvc = buildMvc(gate = gate, dirIndex = probe)

        // 首取：200 + ETag（无基线 → 弱 W/"<size>-<mtime>"）+ Cache-Control。
        val first = mockMvc.perform(get("/api/v1/image/$gid/0"))
            .andExpect(status().isOk)
            .andExpect(content().bytes(bytes))
            .andExpect(header().string("Cache-Control", "max-age=86400"))
            .andReturn()
        val etag = first.response.getHeader("ETag")
        org.junit.jupiter.api.Assertions.assertEquals(
            "W/\"${file.length()}-${file.lastModified()}\"",
            etag,
            "无基线 → 弱 ETag W/\"<size>-<mtime>\""
        )

        // 二次 If-None-Match → 304 空体（带 ETag/Cache-Control）。
        mockMvc.perform(get("/api/v1/image/$gid/0").header("If-None-Match", etag))
            .andExpect(status().isNotModified)
            .andExpect(content().string(""))
            .andExpect(header().string("ETag", etag))
            .andExpect(header().string("Cache-Control", "max-age=86400"))

        // 304 判定在闸门之前：只有首取 200 acquire 一次，304 请求不过闸。
        org.junit.jupiter.api.Assertions.assertEquals(
            1, gate.acquired.count { it.first == gid.toString() && it.second == 0 },
            "304 请求不得过池读闸门，got ${gate.acquired}"
        )
        // 304 不产生预读/缓存副作用。
        verify(imageCacheService, never()).cacheImageByKey(anyLong(), anyInt(), any(), anyString())
    }

    @Test
    fun `pool page serve prefers strong sha256 etag from baseline and heal refresh invalidates it`(@TempDir root: Path) {
        val gid = 702L
        val bytes = byteArrayOf(1, 2, 3, 4)
        poolDirWithPageBytes(root, gid, 0, bytes)
        config.download.path = root.toString()
        val gate = RecordingGate(Int.MAX_VALUE)
        mockMvc = buildMvc(gate = gate, dirIndex = DownloadDirIndex(config))

        // 页号口径：API 0-based == 基线表 0-based（gid, page 直查，无换算）。
        val baselineHash = "ab".repeat(32)
        `when`(pageFileHashRepository.findByGidAndPage(gid, 0)).thenReturn(baselineRow(gid, 0, baselineHash))

        val first = mockMvc.perform(get("/api/v1/image/$gid/0"))
            .andExpect(status().isOk)
            .andExpect(content().bytes(bytes))
            .andReturn()
        val etag = first.response.getHeader("ETag")
        org.junit.jupiter.api.Assertions.assertEquals(
            "\"sha256:$baselineHash\"", etag, "有基线 → 强 ETag \"sha256:<hash>\""
        )

        mockMvc.perform(get("/api/v1/image/$gid/0").header("If-None-Match", etag))
            .andExpect(status().isNotModified)
            .andExpect(content().string(""))

        // heal 换 hash（基线刷新）→ 旧 ETag 自然失效：拿到 200 新字节 + 新 ETag。
        val healedHash = "cd".repeat(32)
        `when`(pageFileHashRepository.findByGidAndPage(gid, 0)).thenReturn(baselineRow(gid, 0, healedHash))
        mockMvc.perform(get("/api/v1/image/$gid/0").header("If-None-Match", etag))
            .andExpect(status().isOk)
            .andExpect(content().bytes(bytes))
            .andExpect(header().string("ETag", "\"sha256:$healedHash\""))

        // gate：首取 + 失效后的 200 各一次；304 不占票。
        org.junit.jupiter.api.Assertions.assertEquals(2, gate.acquired.count { it.first == gid.toString() && it.second == 0 })
        verify(pageFileHashRepository, times(3)).findByGidAndPage(gid, 0)
    }

    @Test
    fun `if-none-match takes precedence over range and a miss falls through to 206`(@TempDir root: Path) {
        val gid = 703L
        val bytes = ByteArray(10) { it.toByte() }
        poolDirWithPageBytes(root, gid, 0, bytes)
        config.download.path = root.toString()
        mockMvc = buildMvc(dirIndex = DownloadDirIndex(config))

        val etag = mockMvc.perform(get("/api/v1/image/$gid/0"))
            .andExpect(status().isOk)
            .andReturn().response.getHeader("ETag")

        // 命中 + Range → 304 优先（RFC 7232：条件评估先于范围处理）。
        mockMvc.perform(get("/api/v1/image/$gid/0").header("If-None-Match", etag).header("Range", "bytes=0-1"))
            .andExpect(status().isNotModified)
            .andExpect(content().string(""))

        // 不命中 + Range → 206 照常。
        mockMvc.perform(get("/api/v1/image/$gid/0").header("If-None-Match", "\"nope\"").header("Range", "bytes=0-1"))
            .andExpect(status().isPartialContent)
            .andExpect(content().bytes(bytes.copyOfRange(0, 2)))
            .andExpect(header().string("Content-Range", "bytes 0-1/10"))
    }

    @Test
    fun `proxy adds public cache-control and cache-key etag with 304 on hit paths`() {
        val url = "https://e-hentai.org/t/5001/cover.jpg"
        `when`(imageCacheService.getCachedImage(url)).thenReturn(byteArrayOf(1, 2, 3))
        site = FakeSite { canned(it, 200, "image/jpeg", "") }
        setUpClient(site)

        // 原尺寸命中：Cache-Control public + ETag = W/"<sha256(url)>"。
        val etag = "W/\"${sha256Hex(url)}\""
        mockMvc.perform(get("/api/v1/image/proxy").param("url", url))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "public, max-age=86400"))
            .andExpect(header().string("ETag", etag))

        // 304：缓存命中路径判定，不触发上游请求。
        mockMvc.perform(get("/api/v1/image/proxy").param("url", url).header("If-None-Match", etag))
            .andExpect(status().isNotModified)
            .andExpect(content().string(""))

        // w= 变体各自 ETag（thumb 键派生，与原尺寸互不命中）。
        val scaledKey = ThumbnailScaler.thumbnailCacheKey(url, 80)
        `when`(imageCacheService.getCachedImage(scaledKey)).thenReturn(solidImageBytes("jpeg", 80, 60))
        val scaledEtag = "W/\"$scaledKey\""
        mockMvc.perform(get("/api/v1/image/proxy").param("url", url).param("w", "80"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "public, max-age=86400"))
            .andExpect(header().string("ETag", scaledEtag))
        mockMvc.perform(get("/api/v1/image/proxy").param("url", url).param("w", "80").header("If-None-Match", scaledEtag))
            .andExpect(status().isNotModified)
            .andExpect(content().string(""))
        mockMvc.perform(get("/api/v1/image/proxy").param("url", url).param("w", "80").header("If-None-Match", etag))
            .andExpect(status().isOk)

        org.junit.jupiter.api.Assertions.assertEquals(0, site.callCount, "头/304 断言全程不触发上游")
    }

    @Test
    fun `proxy fetch-on-miss response carries cache-control and etag for later revalidation`() {
        val url = "https://e-hentai.org/t/5002/cover.jpg"
        `when`(imageCacheService.getCachedImage(url)).thenReturn(null)
        site = FakeSite { canned(it, 200, "image/png", "PNGBYTES") }
        setUpClient(site)

        mockMvc.perform(get("/api/v1/image/proxy").param("url", url))
            .andExpect(status().isOk)
            .andExpect(content().string("PNGBYTES"))
            .andExpect(header().string("Cache-Control", "public, max-age=86400"))
            .andExpect(header().string("ETag", "W/\"${sha256Hex(url)}\""))
    }

    /** If-None-Match 解析（RFC 7232：`*` / 逗号列表 / W/ 弱比较 / 容错）纯单测。 */
    @Test
    fun `if-none-match parsing handles star lists weak prefixes and malformed input`() {
        val etag = "\"sha256:abc\""
        val weak = "W/\"555-123\""
        assertTrue(HttpCacheSupport.ifNoneMatchMatches("*", etag), "星号匹配任意当前表示")
        assertTrue(HttpCacheSupport.ifNoneMatchMatches("*", null))
        assertTrue(HttpCacheSupport.ifNoneMatchMatches(etag, etag))
        assertTrue(HttpCacheSupport.ifNoneMatchMatches("W/$etag", etag), "弱比较：W/ 前缀等价")
        assertTrue(HttpCacheSupport.ifNoneMatchMatches(etag, "W/$etag"), "弱比较双向")
        assertTrue(HttpCacheSupport.ifNoneMatchMatches(weak, weak))
        assertTrue(HttpCacheSupport.ifNoneMatchMatches("\"a\", W/\"b\" ,\"sha256:abc\"", etag), "逗号列表")
        assertTrue(HttpCacheSupport.ifNoneMatchMatches("\"x, y\"", "\"x, y\""), "quoted 内逗号不打断解析")
        assertFalse(HttpCacheSupport.ifNoneMatchMatches(null, etag))
        assertFalse(HttpCacheSupport.ifNoneMatchMatches("", etag))
        assertFalse(HttpCacheSupport.ifNoneMatchMatches("\"zzz\"", etag))
        assertFalse(HttpCacheSupport.ifNoneMatchMatches("\"sha256:abc", etag), "残缺片断不抛且不命中")
        assertTrue(HttpCacheSupport.ifNoneMatchMatches("\"zzz\",\"sha256:abc\"", etag), "残缺后继续解析后续 tag")

        org.junit.jupiter.api.Assertions.assertEquals("\"sha256:ff\"", HttpCacheSupport.strongEtag("sha256", "ff"))
        org.junit.jupiter.api.Assertions.assertEquals("W/\"12-34\"", HttpCacheSupport.weakEtag(12, 34))
        org.junit.jupiter.api.Assertions.assertEquals("W/\"k\"", HttpCacheSupport.weakEtagForKey("k"))
        org.junit.jupiter.api.Assertions.assertEquals(64, HttpCacheSupport.sha256Hex("abc").length)

        val notModified = HttpCacheSupport.notModified(etag, "max-age=60")
        org.junit.jupiter.api.Assertions.assertEquals(304, notModified.statusCode.value())
        org.junit.jupiter.api.Assertions.assertNull(notModified.body)
        org.junit.jupiter.api.Assertions.assertEquals(etag, notModified.headers.getFirst("ETag"))
        org.junit.jupiter.api.Assertions.assertEquals("max-age=60", notModified.headers.getFirst("Cache-Control"))
    }
}
