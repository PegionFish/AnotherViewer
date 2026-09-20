package com.hippo.anotherviewer.web.api

import com.hippo.anotherviewer.client.SiteRequestBuilder
import com.hippo.anotherviewer.client.SiteUrl
import com.hippo.anotherviewer.client.exception.SiteException
import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.dto.JobSubmitResponse
import com.hippo.anotherviewer.web.dto.JobType
import com.hippo.anotherviewer.web.util.HttpCacheSupport
import com.hippo.anotherviewer.web.util.ResponseTooLargeException
import com.hippo.anotherviewer.web.util.ThumbnailScaler
import com.hippo.anotherviewer.web.util.bytesBounded
import com.hippo.anotherviewer.web.repository.PageFileHashRepository
import com.hippo.anotherviewer.web.service.DownloadDirIndex
import com.hippo.anotherviewer.web.service.DownloadService
import com.hippo.anotherviewer.web.service.EhAvailabilityService
import com.hippo.anotherviewer.web.service.EhUnavailableException
import com.hippo.anotherviewer.web.service.Job
import com.hippo.anotherviewer.web.service.JobService
import com.hippo.anotherviewer.web.service.SiteSessionManager
import com.hippo.anotherviewer.web.service.GalleryLookupService
import com.hippo.anotherviewer.web.service.ImageCacheService
import com.hippo.anotherviewer.web.service.PrefetchService
import com.hippo.anotherviewer.web.service.storage.PoolReadGate
import com.hippo.anotherviewer.web.service.storage.PoolReadPermit
import com.hippo.anotherviewer.web.service.storage.StorageTuning
import com.hippo.network.StatusCodeException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.slf4j.LoggerFactory
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Image delivery endpoints.
 *
 * - `GET /api/v1/image/{galleryId}/{page}` — stream a gallery page image
 *   (0-based page, on-demand fetch + cache, Range/206, optional enhanced
 *   variant). See contracts/openapi.yaml `streamGalleryImage`.
 * - `GET /api/v1/image/proxy` — URL-keyed cache lookup; W3 R4-13 added
 *   fetch-on-miss for whitelisted Gallery Site URLs (backfill + content-type
 *   pass-through, unreachable site keeps the 404 envelope). V4 W1 adds an
 *   optional `w` thumbnail-width parameter: scaled variants are cached under
 *   a `w`-derived key and any invalid/unscaleable case falls back to the
 *   original bytes (never 4xx). See [ThumbnailScaler] for the contract.
 * - `GET /api/v1/image/cache/status`, `POST /api/v1/image/cache/clear`.
 *
 * W3-P 存储形态自适应（设计定稿 §二）：所有页 serve（阅读端点含上游拉取、
 * /proxy 的 fetch-on-miss）全程持「存储池读闸门」票（[PoolReadGate]，SSD 直通、
 * HDD 3 / ZFS 8|16 且同目录页序放行）；缓存命中 / `w=` 缩放命中 / 在途合并路径
 * 只碰 SSD 侧缓存层，不过闸。成功 serve 池页后按 `pageTurnPrefetchEnabled`
 * （SSD 关、HDD/ZFS 开）后台预热同画廊下一页池文件进页缓存（不递归 N+2）。
 *
 * P-S2（二期 Wave 1）：池页 serve 流式化 + HTTP 条件请求。
 * - 池页 serve 体改为区间流 Resource（转换器在响应写出窗口从流上定位拷贝，
 *   任何时刻不整文件进堆；对外字节与头格式与旧 readBytes 实现逐字节一致）；
 *   池读闸门票由响应体的流 close() 归还（[CountedResource]），持票窗口
 *   与物理读窗口仍重合（W3-P「读全过程持票」不变）。
 * - 池页 serve 带 ETag：有完整性基线 → 强 `"sha256:<hash>"`，无基线 → 弱
 *   `W/"<size>-<mtime>"`；`If-None-Match` 命中 → 304 空体（在池读闸门**之前**
 *   判定：不读盘、不过闸、不产生预读副作用）。基线刷新（heal 换 hash）后旧
 *   ETag 自然失效——这正是强 ETag 的意义。见 [HttpCacheSupport] 键策略。
 * - `/proxy` 补 `Cache-Control: public, max-age=86400` + 缓存键派生弱 ETag
 *   + 缓存命中路径上的 304 分支（不触发上游请求）。
 */
@RestController
@RequestMapping("/api/v1/image")
class ImageProxyController(
    private val imageCacheService: ImageCacheService,
    private val galleryLookupService: GalleryLookupService,
    private val sessionManager: SiteSessionManager,
    private val prefetchService: PrefetchService,
    private val downloadService: DownloadService,
    // MASTER-2026-08-22 P10：required 注入——构造器默认参数会绕过 Spring 装配
    // 静默产生孤儿 JobStore/配置实例，测试需要替身时显式传入。
    private val config: SiteCoreConfigProperties,
    private val jobService: JobService,
    private val availability: EhAvailabilityService,
    private val downloadDirIndex: DownloadDirIndex,
    // P-S2：页文件完整性基线（强 ETag 来源）。只读（findByGidAndPage），
    // 页号口径见 [poolPageEtag]——API 0-based == 基线表 0-based，直查无需换算。
    private val pageFileHashRepository: PageFileHashRepository,
    // W3-P：存储形态自适应参数（翻页预读开关）与存储池读并发闸门——
    // 上限/开关跟随 StorageTuning.current() 快照，profile 重探后由 gate 惰性重建。
    private val storageTuning: StorageTuning,
    private val poolReadGate: PoolReadGate,
) {
    private val logger = LoggerFactory.getLogger(ImageProxyController::class.java)

    companion object {
        private const val MAX_CONCURRENT_PAGE_FETCHES = 4
        private const val CACHE_MAX_AGE = "max-age=86400"
        /** P-S2: /proxy 公共缓存头（含 public——缩略图按 url+w 派生稳定，可共享缓存）。 */
        private const val PROXY_CACHE_CONTROL = "public, max-age=86400"
        /** P3: /proxy 全局并发 curl 上限（25 卡首开 → 6 并发批次排队）。 */
        private const val MAX_CONCURRENT_PROXY_FETCHES = 6
        /** P4: 缩略图 per-request curl --max-time（秒）；上游挂着时快速失败，不占满默认 60s。 */
        private const val PROXY_FETCH_TIMEOUT_SEC = 10
        /** W3-P: /proxy 无页语义（URL 键），过池读闸门时统一按 0 号页——只借闸门的并发上限。 */
        private const val PROXY_GATE_PAGE = 0
        /** W3-P: 池页预读后台队列容量；满时丢弃最旧任务（预读是尽力而为的热身）。 */
        private const val POOL_PREFETCH_QUEUE_CAPACITY = 32
        private val IMAGE_MIME_BY_EXT = mapOf(
            "jpg" to MediaType.IMAGE_JPEG_VALUE,
            "jpeg" to MediaType.IMAGE_JPEG_VALUE,
            "png" to MediaType.IMAGE_PNG_VALUE,
            "webp" to "image/webp",
            "gif" to MediaType.IMAGE_GIF_VALUE,
        )
    }

    private val okHttpClient get() = sessionManager.okHttpClient

    /**
     * In-flight page fetches keyed by (galleryId, page): concurrent requests
     * for the SAME page share one upstream fetch instead of each tripping the
     * gallery concurrency limit (a reader's srcset 1x/2x + dual-page mode used
     * to race 4+ requests against a Semaphore(2) → spurious 429 storms).
     */
    private val pageFetchers = ConcurrentHashMap<String, CompletableFuture<ResponseEntity<*>>>()
    /** Per-gallery concurrency cap so one reader cannot saturate EH. */
    private val gallerySemaphores = ConcurrentHashMap<Long, java.util.concurrent.Semaphore>()

    /**
     * P3: in-flight thumbnail fetches keyed by URL — concurrent `/proxy` misses
     * for the SAME url share one upstream fetch (first page of 25 cards used to
     * trip 25 concurrent curl processes + 75 temp files). Distinct urls are
     * capped by the global [proxyFetchSemaphore]; excess requests queue on the
     * semaphore instead of erroring (thumbnails have no client retry logic).
     */
    private val proxyFetchers = ConcurrentHashMap<String, CompletableFuture<ResponseEntity<*>>>()
    private val proxyFetchSemaphore = Semaphore(MAX_CONCURRENT_PROXY_FETCHES)

    // ── W3-P: 存储池读闸门 + 池页翻页预读 ────────────────────────

    /**
     * 池页预读专用单线程池：daemon、有界队列（[POOL_PREFETCH_QUEUE_CAPACITY]），
     * 满时丢弃最旧任务（[DiscardOldestPrefetchPolicy]）——预读永远不与用户请求
     * 抢响应线程，也绝不无限积压。
     */
    private val poolPrefetchExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(POOL_PREFETCH_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "pool-page-prefetch").apply { isDaemon = true } },
    ).apply { rejectedExecutionHandler = DiscardOldestPrefetchPolicy() }

    /** 预读在途去重（「gid:page」键）：同页在途绝不重复提交；任务结束/被丢弃时归还键。 */
    private val poolPrefetchInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // ── legacy endpoints (kept intact) ───────────────────────────

    @GetMapping("/proxy")
    fun proxyImage(
        @RequestParam url: String,
        // V4 W1: 缩略图目标宽度（可选整型像素）。绑定用 String? 手动解析——
        // 与阅读端点不同，/proxy 的契约要求非法值（非数字/≤0/超大）一律回退
        // 原尺寸，绝不能因参数绑定失败 4xx；Int? 绑定会把非数字直接打成 400。
        @RequestParam(required = false) w: String?,
        // P-S2: 条件请求头。304 只在缓存命中路径上判定（不触发任何上游请求）；
        // fetch-on-miss 必须先取回字节（响应仍带 ETag/Cache-Control 供后续再验证）。
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<*> {
        // 解析失败 → null → 全程走原尺寸路径（与不带 w 的既有行为逐字节一致）。
        val thumbWidth = ThumbnailScaler.parseThumbnailWidth(w)

        // 缩放结果缓存命中（w 并入缓存键，不同宽度互不污染，原尺寸条目不动）。
        if (thumbWidth != null) {
            val scaledKey = ThumbnailScaler.thumbnailCacheKey(url, thumbWidth)
            imageCacheService.getCachedImage(scaledKey)?.let { scaled ->
                // P-S2: 缩放缓存命中路径的 304 判定（各 w= 变体各自 ETag）。
                val etag = HttpCacheSupport.weakEtagForKey(scaledKey)
                if (HttpCacheSupport.ifNoneMatchMatches(ifNoneMatch, etag)) {
                    return HttpCacheSupport.notModified(etag, PROXY_CACHE_CONTROL)
                }
                return ResponseEntity.ok()
                    .proxyCacheHeaders(etag)
                    .header(HttpHeaders.CONTENT_TYPE, ThumbnailScaler.sniffMime(scaled) ?: MediaType.IMAGE_JPEG_VALUE)
                    .body(scaled)
            }
        }

        val cached = imageCacheService.getCachedImage(url)
        if (cached != null) {
            if (thumbWidth == null) {
                // P-S2: 原尺寸缓存命中路径的 304 判定（ETag = sha256(url) 派生）。
                val etag = HttpCacheSupport.proxyEtag(url)
                if (HttpCacheSupport.ifNoneMatchMatches(ifNoneMatch, etag)) {
                    return HttpCacheSupport.notModified(etag, PROXY_CACHE_CONTROL)
                }
                return ResponseEntity.ok()
                    .proxyCacheHeaders(etag)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                    .body(cached)
            }
            return serveScaledOrOriginal(cached, MediaType.IMAGE_JPEG_VALUE, url, thumbWidth, ifNoneMatch)
        }

        // W3 R4-13 fetch-on-miss (acceptance addition for the Tier-2 thumbnail
        // rewrite, merged in a4 #13): a cache miss for a Gallery Site URL is
        // fetched through the shared session client (cookies/proxy inherited),
        // backfilled into the cache and served with the site's content-type.
        // Non-whitelisted hosts are never fetched (legacy 404 stands), and an
        // unreachable/erroring site surfaces the legacy 404 envelope (E2E-6:
        // errors are passed through, never papered over with fake content).
        val target = url.toHttpUrlOrNull()
        if (target == null || !SiteProxyController.isGallerySiteHost(target.host)) {
            return errorEnvelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "Image not found in cache")
        }

        // EH DOWN 熔断：cache miss 直接秒回 404 EH_UNAVAILABLE，不发起任何上游请求。
        if (availability.isBlocked()) {
            logger.debug("Image proxy miss skipped for url={}: EH unavailable", url)
            return errorEnvelope(HttpStatus.NOT_FOUND, EhUnavailableException.CODE, EhUnavailableException.USER_MESSAGE)
        }

        // P3: 同 URL 并发请求共享一次上游 fetch。已在途 → 直接等它的结果
        // （失败以 completeExceptionally 收场，等的人统一回落 404 envelope）。
        // future 恒为全尺寸响应，各 join 方（按各自的 w）join 后再自行缩放，
        // 所以不同 w 的请求合并同一次上游 fetch 也不会串尺寸。
        val existing = proxyFetchers[url]
        if (existing != null) {
            return try {
                maybeScaleJoined(existing.join(), url, thumbWidth, ifNoneMatch)
            } catch (e: Exception) {
                logger.warn("In-flight image proxy fetch failed for url={}", url, e)
                proxyUnavailableEnvelope()
            }
        }

        // P3: 全局并发上限——acquire 阻塞在请求线程上（排队成 6 并发批次），
        // 成功注册的 future 独占本次 fetch；竞态败者释放多占的额度改等胜者。
        proxyFetchSemaphore.acquire()
        val future = CompletableFuture.supplyAsync {
            // W3-P: fetch-on-miss 过同一存储池读闸门（缓存命中/缩放命中/在途合并
            // 不占票）。/proxy 只碰网络与 SSD 缓存层、不读池文件——持票是设计定稿
            // 「页服务读闸门」的全局并发保护（SSD 配置下为零开销直通）。
            poolReadGate.acquire(url, PROXY_GATE_PAGE).use { fetchProxyImage(target, url) }
        }
        val raced = proxyFetchers.putIfAbsent(url, future)
        if (raced != null) {
            proxyFetchSemaphore.release()
            return try {
                maybeScaleJoined(raced.join(), url, thumbWidth, ifNoneMatch)
            } catch (e: Exception) {
                logger.warn("In-flight image proxy fetch failed for url={}", url, e)
                proxyUnavailableEnvelope()
            }
        }
        try {
            return maybeScaleJoined(future.join(), url, thumbWidth, ifNoneMatch)
        } catch (e: Exception) {
            logger.warn("Image proxy fetch failed for url={}", url, e)
            return proxyUnavailableEnvelope()
        } finally {
            proxyFetchers.remove(url, future)
            proxyFetchSemaphore.release()
        }
    }

    /** P3: /proxy 上游失败时等价于旧 catch 路径的 404 envelope。 */
    private fun proxyUnavailableEnvelope(): ResponseEntity<*> =
        errorEnvelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "gallery site unreachable")

    /**
     * V4 W1: 拿到全尺寸响应（缓存命中或 fetch-on-miss join）后按 [width] 缩放。
     * `width == null`（不带 w，或非法 w 回退）时原样透传——无 w 的既有行为
     * 零改动。缩放失败（见 [ThumbnailScaler.scaleToWidth]）回退原尺寸字节，
     * 绝不 4xx（缩略图是体验优化）。非 2xx envelope（404/502…）也不缩放，
     * 错误按原样透传（E2E-6 原则：不拿假内容盖错误）。
     */
    private fun maybeScaleJoined(
        joined: ResponseEntity<*>,
        url: String,
        width: Int?,
        ifNoneMatch: String?,
    ): ResponseEntity<*> {
        if (width == null) return joined
        val body = joined.body
        if (!joined.statusCode.is2xxSuccessful || body !is ByteArray) return joined
        val mime = joined.headers.contentType?.toString() ?: MediaType.IMAGE_JPEG_VALUE
        return serveScaledOrOriginal(body, mime, url, width, ifNoneMatch)
    }

    /**
     * V4 W1: 缩放 [source] 并把产物写入 `w` 派生缓存键（原尺寸条目保持不动）。
     * 缩放不可能时（非 JPEG/PNG、动图 GIF、只放不缩、解码失败）原样返回，
     * 且不写缩放缓存——后续同 w 请求再试一次，代价只是缓存字节的一次重解码。
     *
     * P-S2: 成功响应补 `Cache-Control` + ETag——缩放成功用 `thumb:` 变体键派生
     * （各变体各自 ETag），回退原尺寸字节则用原尺寸键派生（响应字节即原字节）。
     * [ifNoneMatch] 命中对应变体 → 304 空体：此时仍是缓存命中路径的延伸
     * （字节已在手上，不触发上游；缩放产物照常入缓存，预热不变）。
     */
    private fun serveScaledOrOriginal(
        source: ByteArray,
        sourceMime: String,
        url: String,
        width: Int,
        ifNoneMatch: String?,
    ): ResponseEntity<*> {
        val scaled = ThumbnailScaler.scaleToWidth(source, width)
            ?: run {
                val etag = HttpCacheSupport.proxyEtag(url)
                if (HttpCacheSupport.ifNoneMatchMatches(ifNoneMatch, etag)) {
                    return HttpCacheSupport.notModified(etag, PROXY_CACHE_CONTROL)
                }
                return ResponseEntity.ok()
                    .proxyCacheHeaders(etag)
                    .header(HttpHeaders.CONTENT_TYPE, sourceMime)
                    .body(source)
            }
        imageCacheService.cacheImage(ThumbnailScaler.thumbnailCacheKey(url, width), scaled.bytes)
        val etag = HttpCacheSupport.weakEtagForKey(ThumbnailScaler.thumbnailCacheKey(url, width))
        if (HttpCacheSupport.ifNoneMatchMatches(ifNoneMatch, etag)) {
            return HttpCacheSupport.notModified(etag, PROXY_CACHE_CONTROL)
        }
        return ResponseEntity.ok()
            .proxyCacheHeaders(etag)
            .header(HttpHeaders.CONTENT_TYPE, scaled.mimeType)
            .body(scaled.bytes)
    }

    /** P-S2: /proxy 成功响应的公共缓存头（`public, max-age=86400` + 弱 ETag）。 */
    private fun ResponseEntity.BodyBuilder.proxyCacheHeaders(etag: String): ResponseEntity.BodyBuilder =
        this.header(HttpHeaders.CACHE_CONTROL, PROXY_CACHE_CONTROL)
            .header(HttpHeaders.ETAG, etag)

    /**
     * P3: /proxy 的单次上游抓取（自原 proxyImage miss 路径抽出，语义不变）。
     * 由 [proxyFetchers] 注册方经 CompletableFuture 执行；网络层异常向上抛出
     * （future completeExceptionally，等价旧 catch 路径，join 方统一记日志），
     * HTTP 层错误仍以 envelope 返回。
     */
    private fun fetchProxyImage(target: HttpUrl, url: String): ResponseEntity<*> {
        // EH validates the Referer of thumbnail-origin requests strictly:
        // an origin without the trailing slash (SiteUrl.REFERER_*) is
        // rejected with 403 on s.exhentai.org. Send the slash form.
        val request = SiteRequestBuilder(target.toString(), siteReferer()).build()
        // P4: 缩略图打 10s per-request 超时 tag（curl --max-time）；tag 不进
        // 线路（CurlSiteExecutor 只读不剥离），阅读器页图路径维持默认 60s。
        // tag 类型用 Integer（javaObjectType）——原始类型 int.class 会 CCE。
        val tagged = request.newBuilder().tag(Int::class.javaObjectType, PROXY_FETCH_TIMEOUT_SEC).build()
        okHttpClient.newCall(tagged).execute().use { response ->
            if (!response.isSuccessful) {
                return errorEnvelope(
                    HttpStatus.NOT_FOUND,
                    "NOT_FOUND",
                    "site did not serve the image (HTTP ${response.code})"
                )
            }
            val contentType = response.header(HttpHeaders.CONTENT_TYPE) ?: MediaType.IMAGE_JPEG_VALUE
            // MASTER-2026-08-22 S2：上游响应有界读取，超限 502（防恶意/异常大图打爆堆）。
            val bytes = try {
                response.body?.bytesBounded(config.proxy.maxResponseBytes)
            } catch (e: ResponseTooLargeException) {
                logger.warn("Image proxy fetch-on-miss aborted: upstream body exceeds {} bytes for url={}", e.maxBytes, url)
                return errorEnvelope(HttpStatus.BAD_GATEWAY, "UPSTREAM_TOO_LARGE", "Upstream image exceeds size limit")
            }
            if (bytes == null || bytes.isEmpty()) {
                return errorEnvelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "site returned an empty image body")
            }
            imageCacheService.cacheImage(url, bytes)
            // P-S2: fetch-on-miss 也带缓存头（后续请求可再验证；304 不在本路径
            // 判定——本路径已经发起过上游请求，再回 304 只省字节不省请求）。
            return ResponseEntity.ok()
                .proxyCacheHeaders(HttpCacheSupport.proxyEtag(url))
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .body(bytes)
        }
    }

    @GetMapping("/cache/status")
    fun cacheStatus(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "cacheSize" to imageCacheService.getCacheSize()
        ))
    }

    @PostMapping("/cache/clear")
    fun clearCache(): ResponseEntity<*> {
        lateinit var job: Job
        try {
            job = jobService.submit(JobType.CACHE_CLEAR, {}) { handle ->
                val outcome = imageCacheService.clearCache(handle)
                job.result = mapOf("removed" to outcome.removed, "total" to outcome.total)
            }
        } catch (e: IllegalStateException) {
            return errorEnvelope(HttpStatus.CONFLICT, "CONFLICT", e.message ?: "已有清缓存任务进行中")
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobSubmitResponse(job.jobId, job.state))
    }

    // ── streamGalleryImage ───────────────────────────────────────

    @GetMapping("/{galleryId}/{page}")
    fun streamGalleryImage(
        @PathVariable galleryId: Long,
        @PathVariable page: Int,
        @RequestParam(required = false) w: Int?,
        @RequestParam(name = "enhanced", required = false, defaultValue = "false") enhanced: Boolean,
        @RequestHeader(name = HttpHeaders.RANGE, required = false) range: String?,
        // P-S2: 条件请求头（RFC 7232，弱比较）。仅在池页 serve 路径上判定，
        // 且优先于 Range（304 优先）；其余 serve 路径暂不发布 ETag。
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<*> {
        // Page numbers are 0-based per contract; reject negatives explicitly.
        if (page < 0) return notFound(galleryId, page)

        // Surface an expired Gallery Site login as 401 (see AuthExceptionHandler).
        sessionManager.requireValidSession()

        // Enhanced variant: serve from the processing output dir when present.
        if (enhanced) {
            val file = imageCacheService.getEnhancedImage(galleryId, page)
                ?: return notFound(galleryId, page)
            return serveFile(file, range)
        }

        // 1. Serve from cache (memory/disk) when available.
        val cachedFile = imageCacheService.findCachedPageFile(galleryId, page)
        if (cachedFile != null) {
            return serveFile(cachedFile, range)
        }
        val cached = imageCacheService.getCachedImageByKey(galleryId, page)
        if (cached != null) {
            return serveBytes(cached, MediaType.IMAGE_JPEG_VALUE, range)
        }

        // 1.5. App-pushed download fallback: downloads/<gid>/%04d.<ext> (1-based).
        //      Checked after the cache and before any upstream EH fetch, so
        //      pushed content is served locally even when EH is unreachable.
        val pushedFile = findPushedPageFile(galleryId, page)
        if (pushedFile != null) {
            // 需求 1（2026-08-30）：阅读命中存储池文件 → 若磁盘校验通过即置
            // 「已完成」——导入时所有行视为未下载完成，阅读匹配后升级。
            downloadService.completeIfVerified(galleryId)

            // P-S2 ETag/304：命中判定在池读闸门**之前**——命中即 304 空体，
            // 不读池文件、不占闸门票、不触发翻页预读（304 是 3xx，后面的
            // is2xxSuccessful 预读分支天然不进）。强 ETag 只查基线表（DB 读，
            // 非盘读）；无基线才碰文件元数据（length/mtime，非内容读）。
            // If-None-Match 优先于 Range（RFC 7232 §6：条件评估先于范围处理）。
            val etag = poolPageEtag(galleryId, page, pushedFile)
            if (HttpCacheSupport.ifNoneMatchMatches(ifNoneMatch, etag)) {
                return HttpCacheSupport.notModified(etag, CACHE_MAX_AGE)
            }

            // W3-P 存储池读闸门：池文件「读全过程」持票（HDD/ZFS 限并发 + 同目录
            // 页序放行；gate 内 5s 排队超时强制放行，响应线程绝不无限等待）。
            // 上面的缓存/enhanced 命中路径只碰 SSD 侧缓存层，不过闸。
            // P-S2 流式 serve 后，物理读发生在响应写出窗口（消息转换器内）——
            // 票不在此处 close，而是移交响应体的流 close() 归还
            // （见 [serveFile] / [CountedResource]），持票窗口与物理读窗口仍
            // 重合；serveFile 错误早退路径自行归还。
            val permit = poolReadGate.acquire(poolGateDirectory(galleryId), page)
            val response = try {
                serveFile(pushedFile, range, permit, etag)
            } catch (e: Throwable) {
                permit.close()
                throw e
            }
            if (response.statusCode.is2xxSuccessful) {
                // 翻页预读 N+1：成功 serve 池页后，后台把同画廊下一页池文件
                // 预热进缓存（SSD 关、HDD/ZFS 开；fire-and-forget）。
                schedulePoolPagePrefetch(galleryId, page)
            }
            return response
        }

        // EH DOWN 熔断：cache miss + pushed miss 后、进入 fetch 前秒回
        // 404 EH_UNAVAILABLE（不占用 semaphore、不发起上游、不触发 prefetch）。
        if (availability.isBlocked()) {
            logger.debug("Page stream fetch skipped for gid={} page={}: EH unavailable", galleryId, page)
            return errorEnvelope(HttpStatus.NOT_FOUND, EhUnavailableException.CODE, EhUnavailableException.USER_MESSAGE)
        }

        // 2. Cache miss — fetch from Gallery Site via the shared session client.
        //    Concurrent requests for the same (galleryId, page) share ONE upstream
        //    fetch (srcset 1x/2x + dual-page mode issue several identical requests);
        //    distinct pages are additionally capped per-gallery so one reader
        //    cannot saturate EH. A busy page waits for the in-flight fetch instead
        //    of immediately 429ing (old behavior caused retry storms).
        val key = "$galleryId:$page"
        val existing = pageFetchers[key]
        if (existing != null) {
            return try {
                existing.join()
            } catch (e: Exception) {
                logger.warn("In-flight page fetch failed for gid={} page={}", galleryId, page, e)
                notFound(galleryId, page)
            }
        }
        val fetcher = gallerySemaphores.computeIfAbsent(galleryId) { java.util.concurrent.Semaphore(MAX_CONCURRENT_PAGE_FETCHES) }
        if (!fetcher.tryAcquire()) {
            // Distinct-page concurrency cap reached — degrade to 429; the client
            // retries with backoff (see PageMode). Never queues unboundedly.
            return rateLimited(galleryId, page)
        }
        val future = CompletableFuture.supplyAsync {
            try {
                // W3-P: 页 serve（含上游拉取）全过程持票，过存储池读闸门。
                poolReadGate.acquire(poolGateDirectory(galleryId), page).use { fetchAndServe(galleryId, page, range) }
            } finally {
                fetcher.release()
            }
        }
        val raced = pageFetchers.putIfAbsent(key, future)
        if (raced != null) {
            // Another thread registered the same page first — wait on theirs.
            fetcher.release()
            return raced.join()
        }
        try {
            val response = future.join()
            // Reader prefetch: warm the next pages in the background, only when
            // this page was actually served (fire-and-forget, never blocks).
            if (response.statusCode.is2xxSuccessful) {
                prefetchService.prefetchAround(galleryId, page)
            }
            return response
        } finally {
            pageFetchers.remove(key, future)
        }
    }

    // ── fetch / serve helpers ────────────────────────────────────

    /**
     * Probes the App-pushed download layout `downloads/<gid>/%04d.<ext>` for a
     * 0-based [page] (files are 1-based, hence page+1), via the in-memory
     * [DownloadDirIndex] — no disk scan per request. The indexed entry is
     * still validated (`isFile && length > 0`) against the actual file system
     * before it is served. Returns null so the caller falls through to the
     * upstream EH fetch (or, while DOWN, the EH_UNAVAILABLE 404).
     */
    private fun findPushedPageFile(galleryId: Long, page: Int): File? {
        val ref = downloadDirIndex.findPage(galleryId, page) ?: return null
        // 目录可为 `{gid}`（旧）或 `{gid}-{title}`（新/Android）；经索引的
        // PageRef.fileName 是磁盘真实文件名（4 位/8 位均可），子目录由索引
        // findDir 语义解析——此处借 downloadDirIndex 定位目录，避免按纯 gid 拼路径。
        val dir = downloadDirIndex.dirFor(galleryId)
        if (dir == null) return null
        val file = File(dir, ref.fileName)
        return if (file.isFile && file.length() > 0) file else null
    }

    // ── W3-P: 池页翻页预读 N+1 ───────────────────────────────────

    /** 池读闸门的目录键：同一画廊恒同键（磁盘目录名为 {gid} 或 {gid}-{title}）。 */
    private fun poolGateDirectory(galleryId: Long): String = galleryId.toString()

    /**
     * 翻页预读 N+1（设计定稿 §二）：成功 serve 池页后，若 pageTurnPrefetchEnabled
     * （SSD 关、HDD/ZFS 开）且下一页池文件已存在，提交后台任务把该页读出并走与
     * serve 相同的缓存填充路径（[ImageCacheService.cacheImageByKey]）预热进缓存。
     *
     * 约束：只预热本地池文件（绝不发起上游请求）；绝不递归预读 N+2（预读体不回
     * 调本方法）；同页在途去重（[poolPrefetchInFlight]）；已入页缓存则跳过（幂等）；
     * 任何失败静默（debug 日志），绝不影响响应线程。预读本身也是池读，同样过
     * 池读闸门（受 HDD/ZFS 并发上限与页序约束）。
     */
    private fun schedulePoolPagePrefetch(galleryId: Long, servedPage: Int) {
        if (!storageTuning.pageTurnPrefetchEnabled) return
        val next = servedPage + 1
        if (imageCacheService.findCachedPageFile(galleryId, next) != null) return
        val ref = downloadDirIndex.findPage(galleryId, next) ?: return
        val dir = downloadDirIndex.dirFor(galleryId) ?: return
        val file = File(dir, ref.fileName)
        if (!file.isFile || file.length() <= 0) return

        val key = "$galleryId:$next"
        if (!poolPrefetchInFlight.add(key)) return
        try {
            poolPrefetchExecutor.execute(
                KeyedPrefetchTask(key, poolPrefetchInFlight) {
                    runPoolPagePrefetch(galleryId, next, file, ref.ext)
                }
            )
        } catch (e: RejectedExecutionException) {
            poolPrefetchInFlight.remove(key)
        }
    }

    /** 预读任务体：双检缓存 → 过池读闸门读池文件 → 走 serve 同款缓存填充路径。失败静默。 */
    private fun runPoolPagePrefetch(galleryId: Long, next: Int, file: File, ext: String) {
        try {
            // 提交→执行之间可能已被其它请求/预读填充：双检后再读。
            if (imageCacheService.findCachedPageFile(galleryId, next) != null) return
            val bytes = poolReadGate.acquire(poolGateDirectory(galleryId), next).use { file.readBytes() }
            imageCacheService.cacheImageByKey(galleryId, next, bytes, ext)
        } catch (e: Exception) {
            logger.debug("Pool page prefetch failed for gid={} page={}", galleryId, next, e)
        }
    }

    /** 带「gid:page」去重键的预读任务：结束（含异常）时归还键，供后续翻页重试。 */
    private class KeyedPrefetchTask(
        val key: String,
        internal val inFlight: MutableSet<String>,
        private val body: () -> Unit,
    ) : Runnable {
        override fun run() {
            try {
                body()
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /**
     * 队列满时丢弃最旧的在途预读任务（「有界队列丢弃旧任务」），并归还被丢弃
     * 任务的去重键——否则该页会被永久视为在途而无法再次预热。极小概率的二次
     * 拒绝（并发生产者抢位）直接放弃新任务并归还其键，与 Discard 同语义。
     */
    private class DiscardOldestPrefetchPolicy : RejectedExecutionHandler {
        override fun rejectedExecution(r: Runnable, executor: ThreadPoolExecutor) {
            if (executor.isShutdown) return
            (executor.queue.poll() as? KeyedPrefetchTask)?.let { dropped -> dropped.inFlight.remove(dropped.key) }
            try {
                executor.execute(r)
            } catch (e: RejectedExecutionException) {
                (r as? KeyedPrefetchTask)?.let { dropped -> dropped.inFlight.remove(dropped.key) }
            }
        }
    }

    private fun fetchAndServe(galleryId: Long, page: Int, range: String?): ResponseEntity<*> {
        val token = galleryLookupService.findToken(galleryId)
            ?: return notFound(galleryId, page)
        val imageUrl = try {
            // EH page numbers are 1-based; the endpoint is 0-based.
            galleryLookupService.fetchImageUrl(galleryId, token, page + 1)
        } catch (e: StatusCodeException) {
            if (e.code == 509) return rateLimited(galleryId, page)
            logger.warn("Failed to resolve page URL for gid={} page={}: {}", galleryId, page, e.message)
            return notFound(galleryId, page)
        } catch (e: SiteException) {
            // "Invalid page." is what EH serves when the (anonymous) session
            // cannot read the page — the gallery needs a signed-in EH session.
            logger.warn("Page URL rejected by site for gid={} page={} (likely session-gated): {}", galleryId, page, e.message)
            return sessionRequired(galleryId, page)
        } catch (e: Exception) {
            logger.warn("Failed to resolve page URL for gid={} page={}", galleryId, page, e)
            return notFound(galleryId, page)
        }

        return try {
            val builder = SiteRequestBuilder(imageUrl, siteReferer())
            if (range != null) {
                builder.header(HttpHeaders.RANGE, range)
            }
            val response = okHttpClient.newCall(builder.build()).execute()
            if (response.code == 509) {
                response.close()
                return rateLimited(galleryId, page)
            }
            if (!response.isSuccessful) {
                response.close()
                return notFound(galleryId, page)
            }

            val contentType = response.header(HttpHeaders.CONTENT_TYPE) ?: MediaType.IMAGE_JPEG_VALUE

            // A Range request is streamed straight through (never buffered,
            // never cached) so the client receives a true 206 when EH honors it.
            if (range != null) {
                val body = response.body
                if (body == null) {
                    response.close()
                    return notFound(galleryId, page)
                }
                val streaming = StreamingResponseBody { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
                return ResponseEntity
                    .status(response.code)
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_MAX_AGE)
                    .apply {
                        response.header(HttpHeaders.CONTENT_RANGE)?.let { header(HttpHeaders.CONTENT_RANGE, it) }
                        response.header(HttpHeaders.CONTENT_LENGTH)?.let { header(HttpHeaders.CONTENT_LENGTH, it) }
                    }
                    .body(streaming)
            }

            // Full fetch: materialise once so the page is cached for later
            // (and future Range requests are served from the cache).
            // MASTER-2026-08-22 S2：有界读取，超限 502。
            val bytes = try {
                response.body?.bytesBounded(config.proxy.maxResponseBytes)
            } catch (e: ResponseTooLargeException) {
                response.close()
                logger.warn("Page fetch aborted: upstream body exceeds {} bytes for gid={} page={}", e.maxBytes, galleryId, page)
                return errorEnvelope(HttpStatus.BAD_GATEWAY, "UPSTREAM_TOO_LARGE", "Upstream image exceeds size limit")
            }
            response.close()
            if (bytes == null || bytes.isEmpty()) {
                return notFound(galleryId, page)
            }
            val ext = extensionFromMime(contentType)
            imageCacheService.cacheImageByKey(galleryId, page, bytes, ext)
            MetricsController.imagesServed.incrementAndGet()
            serveBytes(bytes, contentType, null)
        } catch (e: Exception) {
            logger.warn("Failed to download page image for gid={} page={}", galleryId, page, e)
            notFound(galleryId, page)
        }
    }

    /**
     * P-S2: 池页响应的 ETag（键策略与容错见 [HttpCacheSupport]）。
     *
     * **页号口径（换算说明）**：对外阅读端点 `/api/v1/image/{gid}/{page}` 的
     * page 是 **0-based**（contracts/openapi.yaml streamGalleryImage）；
     * page_file_hash 基线表的 page 同为 **0-based**（与 download_info /
     * processing_task 同口径，见 PageFileHashEntity）——因此这里用 API 页号
     * **直查基线表，无需换算**；磁盘池文件名的 1-based（`0001.jpg`）换算只
     * 发生在 [DownloadDirIndex.findPage]。
     *
     * 有基线 → 强 ETag `"sha256:<hash>"`（algo 字段推导，现役恒 sha256）；
     * 无基线 → 弱 `W/"<size>-<mtime>"`（文件元数据，非内容读）。
     *
     * **基线刷新后旧 ETag 自然失效**：heal/reverify 换 hash ⇒ ETag 变化 ⇒
     * 客户端旧 If-None-Match 不再命中、拿到修复后的新字节。这是选强 ETag 的
     * 根本原因（弱 size-mtime ETag 在内容被替换而 mtime 碰巧不变时会误命中；
     * 反向风险——文件被改而基线未刷——由完整性巡检发现并刷新）。
     */
    private fun poolPageEtag(galleryId: Long, page: Int, file: File): String {
        val baseline = pageFileHashRepository.findByGidAndPage(galleryId, page)
        return if (baseline != null) {
            HttpCacheSupport.strongEtag(algo = baseline.algo, hash = baseline.hash)
        } else {
            HttpCacheSupport.weakEtag(file.length(), file.lastModified())
        }
    }

    /**
     * P-S2 流式 serve 一个本地文件：体为 [CountedResource]（Resource 契约的
     * 区间流实现）——由 Spring 的 ResourceHttpMessageConverter 在**响应写出
     * 窗口**打开输入流（定位到区间起点、恰好读出区间长度即 EOF）、拷贝、
     * finally close——任何时刻都不整文件进堆，1 字节 Range 只从流上读 1 字节。
     *
     * 为何不用 `ResourceRegion`：转换器选择走**声明泛型**（canWrite(type)），
     * 本端点返回 `ResponseEntity<*>`（体类型混合：字节/Resource/JSON envelope），
     * 声明处是通配符 → ResourceRegionHttpMessageConverter 永远不被选中（生产
     * 同样会 500）。区间流 Resource 则由按**值类型**选中的 ResourceHttpMessage
     * Converter 写出（完整 200 用例同通道），且 206/Content-Range 等头完全由
     * 本方法掌控——对外字节语义与头格式与旧 readBytes 实现逐字节一致（416 只
     * 带不可满足区间的 Content-Range 头；206 带 Content-Type/Content-Range/
     * Accept-Ranges/Cache-Control/Content-Length；200 隐式 Content-Length 由
     * 转换器按区间长度补齐）。MIME 维持扩展名映射（未知回退 image/jpeg）——
     * 不新增内容嗅探，避免改变既有 Content-Type 输出。
     *
     * [permit]（池读闸门票，可空）：非空时票的**所有权移交响应体**——转换器
     * finally 关闭输入流时归还闸门票（[CountedResource]，经幂等 AtomicBoolean
     * 恰好归还一次），保证 W3-P「读全过程持票」在流式 serve 下仍成立（持票
     * 窗口 = 响应写出窗口 = 物理读窗口）。[serveFile] 自身的错误早退路径
     * （416/404 envelope）在此处自行归还票。残余风险：票移交后若在转换器打开
     * 流之前出现异常（响应头写出失败等极窄窗口）票会滞留到 profile 重建（gate
     * 换核）自然排空——不影响正确性，只临时少一个并发名额。
     *
     * [etag] 非空时附到 200/206 响应（304 由调用方在进本方法前返回）。
     */
    private fun serveFile(file: File, range: String?, permit: PoolReadPermit? = null, etag: String? = null): ResponseEntity<*> {
        return try {
            if (!file.isFile) throw FileNotFoundException(file.absolutePath)
            val mime = IMAGE_MIME_BY_EXT[file.extension.lowercase()] ?: MediaType.IMAGE_JPEG_VALUE
            val size = file.length()
            MetricsController.imagesServed.incrementAndGet()
            if (range != null) {
                val parsed = parseRange(range, size)
                if (parsed == null) {
                    permit?.close()
                    return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */$size")
                        .build<Any>()
                }
                val (start, end) = parsed
                val length = end - start + 1
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_TYPE, mime)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes $start-$end/$size")
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_MAX_AGE)
                    .etagHeader(etag)
                    .header(HttpHeaders.CONTENT_LENGTH, length.toString())
                    .body(CountedResource(file, start, length, permit))
            }
            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mime)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, CACHE_MAX_AGE)
                .etagHeader(etag)
                .body(CountedResource(file, 0L, size, permit))
        } catch (e: Exception) {
            permit?.close()
            logger.warn("Failed to read cached image {}", file, e)
            errorEnvelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "Image not found")
        }
    }

    private fun ResponseEntity.BodyBuilder.etagHeader(etag: String?): ResponseEntity.BodyBuilder =
        if (etag != null) header(HttpHeaders.ETAG, etag) else this

    /**
     * P-S2: 区间流响应体（Resource 契约委托实现）。getInputStream 定位到
     * [start]、读出恰好 [length] 字节即 EOF；close 时幂等归还池读闸门票
     * （[permit] 可空 = 不涉及池读的缓存/enhanced 路径）。
     */
    private class CountedResource(
        file: File,
        private val start: Long,
        private val length: Long,
        private val permit: PoolReadPermit?,
    ) : Resource by FileSystemResource(file) {
        private val released = AtomicBoolean(false)

        /** 覆写为**响应体**长度（区间长度），供转换器的默认头逻辑使用。 */
        override fun contentLength(): Long = length

        override fun getInputStream(): InputStream {
            val raw = FileInputStream(file)
            var toSkip = start
            while (toSkip > 0) {
                val skipped = raw.skip(toSkip)
                if (skipped > 0) {
                    toSkip -= skipped
                } else if (raw.read() < 0) {
                    break // 文件比预期短（并发截断）：直接流式 EOF，不再读
                } else {
                    toSkip -= 1
                }
            }
            return BoundedReleasingStream(raw, length, permit, released)
        }
    }

    /** 恰好读 [remaining] 字节即 EOF 的有界流；close 时幂等归还闸门票。 */
    private class BoundedReleasingStream(
        private val source: InputStream,
        initialRemaining: Long,
        private val permit: PoolReadPermit?,
        private val released: AtomicBoolean,
    ) : FilterInputStream(source) {
        private var remaining = initialRemaining

        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = super.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun close() {
            try {
                source.close()
            } finally {
                if (released.compareAndSet(false, true)) permit?.close()
            }
        }
    }

    private fun serveBytes(data: ByteArray, contentType: String, range: String?): ResponseEntity<*> {
        MetricsController.imagesServed.incrementAndGet()
        if (range != null) {
            // P-S2: parseRange 统一 Long 化（与 serveFile 的文件长度口径共用）；
            // 字节数组路径 start/end 恒 ≤ size-1 ≤ Int.MAX，回 Int 安全。
            val parsed = parseRange(range, data.size.toLong())
            if (parsed == null) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */${data.size}")
                    .build<Any>()
            }
            val (start, end) = parsed
            val length = (end - start + 1).toInt()
            val body = if (start == 0L && end == data.size - 1L) data else data.copyOfRange(start.toInt(), end.toInt() + 1)
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_RANGE, "bytes $start-$end/${data.size}")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, CACHE_MAX_AGE)
                .header(HttpHeaders.CONTENT_LENGTH, length.toString())
                .body(body)
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, contentType)
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header(HttpHeaders.CACHE_CONTROL, CACHE_MAX_AGE)
            .body(data)
    }

    /**
     * Parse a single `bytes=start-end` (or `bytes=start-` / `bytes=-suffix`)
     * range. Returns null when malformed or unsatisfiable. [size] is Long so
     * the same parser serves both in-memory byte arrays and streamed files.
     */
    private fun parseRange(header: String, size: Long): Pair<Long, Long>? {
        if (size <= 0) return null
        if (!header.startsWith("bytes=")) return null
        val spec = header.substring(6).trim().split(",").firstOrNull() ?: return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startStr = spec.substring(0, dash).trim()
        val endStr = spec.substring(dash + 1).trim()
        val start: Long
        val end: Long
        if (startStr.isEmpty()) {
            // Suffix range: last N bytes.
            val suffix = endStr.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            start = (size - suffix).coerceAtLeast(0)
            end = size - 1
        } else {
            start = startStr.toLongOrNull() ?: return null
            if (start >= size) return null
            end = if (endStr.isEmpty()) size - 1 else (endStr.toLongOrNull() ?: return null).coerceAtMost(size - 1)
        }
        if (start > end) return null
        return start to end
    }

    private fun extensionFromMime(contentType: String): String {
        val mime = contentType.substringBefore(';').trim().lowercase()
        return when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
    }

    private fun notFound(galleryId: Long, page: Int): ResponseEntity<*> {
        return errorEnvelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "gallery or page not found")
    }

    private fun rateLimited(galleryId: Long, page: Int): ResponseEntity<*> {
        return errorEnvelope(
            HttpStatus.TOO_MANY_REQUESTS,
            "RATE_LIMITED",
            "rate limited: too many concurrent fetches for this gallery"
        )
    }

    /** EH gated the page behind a signed-in session ("Invalid page." / similar). */
    private fun sessionRequired(galleryId: Long, page: Int): ResponseEntity<*> {
        return errorEnvelope(
            HttpStatus.UNAUTHORIZED,
            "SESSION_REQUIRED",
            "该画廊需要登录 EH 会话才能阅读"
        )
    }

    /**
     * Gallery Site origin as an HTTP Referer. EH strictly validates Referer
     * shape on thumbnail-origin hosts (s.exhentai.org): the bare
     * `https://exhentai.org` from [SiteUrl.getReferer] is rejected with 403,
     * the slash-terminated origin form is accepted.
     */
    private fun siteReferer(): String = SiteUrl.getReferer() + "/"
}
