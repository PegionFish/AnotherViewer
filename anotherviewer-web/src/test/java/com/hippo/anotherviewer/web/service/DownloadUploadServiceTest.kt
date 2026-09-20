package com.hippo.anotherviewer.web.service

import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.dto.UploadCompleteRequest
import com.hippo.anotherviewer.web.dto.UploadInitRequest
import com.hippo.anotherviewer.web.entity.DownloadInfoEntity
import com.hippo.anotherviewer.web.entity.PageFileHashEntity
import com.hippo.anotherviewer.web.entity.PageFileHashId
import com.hippo.anotherviewer.web.repository.DownloadInfoRepository
import com.hippo.anotherviewer.web.repository.PageFileHashRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 契约测试 for [DownloadUploadService]（App 推送下载落库与落盘）。
 *
 * - 仓库为内存 fake（与 EhImportServiceTest.RepoFixtures 同款 mock 风格），
 *   下载根目录指向 @TempDir，验证 downloads/<gid>/%08d.<ext> 真实落盘布局
 *   （读取端 existingPages 兼容历史 %04d——U2）。
 * - initUpload：新建行（state=2 + 元数据 + downloadDir 派生）与 existingPages
 *   扫描；非 force 冲突 → success=false 且不写库；force → upsert 保留 id，
 *   覆盖墓碑行复位 deleted（U4）。
 * - storePage：V 门校验（Wave 0 classpath fixtures）→ 覆盖写、扩展名白名单、
 *   page>=1、SHA-256 基线 upsert（origin=upload，S3）。
 * - completeUpload：state=3 + total/done；无行 → false。
 */
class DownloadUploadServiceTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var repo: DownloadInfoRepository
    private lateinit var hashRepo: PageFileHashRepository
    private lateinit var store: ConcurrentHashMap<Long, DownloadInfoEntity>
    private lateinit var hashStore: ConcurrentHashMap<PageFileHashId, PageFileHashEntity>
    private lateinit var service: DownloadUploadService

    @BeforeEach
    fun setUp() {
        setUp(uploadEnabled = true)
    }

    private fun setUp(uploadEnabled: Boolean) {
        store = ConcurrentHashMap()
        repo = mock(DownloadInfoRepository::class.java).apply {
            `when`(save(any(DownloadInfoEntity::class.java))).thenAnswer { inv ->
                val e = inv.getArgument<DownloadInfoEntity>(0)
                store[e.gid] = e
                e
            }
            // U5 后服务端走 findAllByGid（List 化）；映射同一内存 store。
            `when`(findAllByGid(anyLong())).thenAnswer { inv ->
                listOfNotNull(store[inv.getArgument<Long>(0)])
            }
        }
        hashStore = ConcurrentHashMap()
        hashRepo = mock(PageFileHashRepository::class.java).apply {
            `when`(findByGidAndPage(anyLong(), anyInt())).thenAnswer { inv ->
                hashStore[PageFileHashId(inv.getArgument<Long>(0), inv.getArgument<Int>(1))]
            }
            `when`(save(any(PageFileHashEntity::class.java))).thenAnswer { inv ->
                val e = inv.getArgument<PageFileHashEntity>(0)
                hashStore[PageFileHashId(e.gid, e.page)] = e
                e
            }
        }
        val config = SiteCoreConfigProperties().apply {
            download.path = tempDir.absolutePath
        }
        val serverConfig = mock(ServerConfigService::class.java).apply {
            `when`(getBoolean(anyString(), anyBoolean())).thenAnswer { inv ->
                inv.getArgument<String>(0) == ServerConfigService.KEY_UPLOAD_ENABLED && uploadEnabled
            }
        }
        service = DownloadUploadService(repo, hashRepo, config, serverConfig)
    }

    /** Wave 0 classpath fixture（src/test/resources/integrity/，口径见 MANIFEST.sha256）。 */
    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/integrity/$name")!!.readBytes()

    private fun downloadDir(gid: Long): File = File(tempDir, "$gid")

    private fun req(
        token: String = "tok123",
        title: String = "Alpha",
        force: Boolean = false,
    ): UploadInitRequest = UploadInitRequest(
        token = token,
        title = title,
        titleJpn = "アルファ",
        thumb = "http://img.ehgt.org/t123.jpg",
        category = 4,
        uploader = "bob",
        rating = 4.5f,
        simpleTags = "female:shion, language:chinese",
        pages = 20,
        label = 0,
        force = force,
    )

    // ── initUpload ───────────────────────────────────────────────

    @Test
    fun `initUpload creates a row with state 2 and derived downloadDir`() {
        val response = service.initUpload(123L, req(), "alice")

        assertTrue(response.success)
        assertTrue(response.existingPages.isEmpty())

        val entity = store.getValue(123L)
        assertEquals("tok123", entity.token)
        assertEquals("Alpha", entity.title)
        assertEquals("アルファ", entity.titleJpn)
        assertEquals("http://img.ehgt.org/t123.jpg", entity.thumb)
        assertEquals(4, entity.category)
        assertEquals("bob", entity.uploader)
        assertEquals(4.5f, entity.rating)
        assertEquals("female:shion, language:chinese", entity.simpleTags)
        assertEquals(20, entity.pages)
        assertEquals(2, entity.state)
        assertEquals("alice", entity.username)
        // 2026-08-30：目录命名对齐 Android——`{gid}-{title}`。
        assertEquals(File(tempDir, "123-Alpha").absolutePath, entity.downloadDir)
        assertTrue(File(tempDir, "123-Alpha").isDirectory)
    }

    @Test
    fun `initUpload conflict returns success false and does not write`() {
        store[123L] = DownloadInfoEntity().apply {
            gid = 123L
            token = "old"
            title = "Old"
        }

        val response = service.initUpload(123L, req(), "alice")

        assertFalse(response.success)
        assertEquals("old", store.getValue(123L).token)
        assertEquals("Old", store.getValue(123L).title)
    }

    @Test
    fun `initUpload refuses when upload is disabled`() {
        setUp(uploadEnabled = false)

        val response = service.initUpload(123L, req(), "alice")

        assertFalse(response.success)
        assertTrue(response.message.contains("Upload disabled"))
        assertNull(store[123L])
    }

    @Test
    fun `initUpload with force upserts and keeps the entity identity`() {
        val existing = DownloadInfoEntity().apply {
            id = 99L
            gid = 123L
            token = "old"
            title = "Old"
            state = 3
        }
        store[123L] = existing

        val response = service.initUpload(123L, req(force = true), "alice")

        assertTrue(response.success)
        val entity = store.getValue(123L)
        assertEquals(99L, entity.id)
        assertEquals("tok123", entity.token)
        assertEquals("Alpha", entity.title)
        assertEquals(2, entity.state)
    }

    @Test
    fun `existingPages scans the percent-04d layout sorted`() {
        service.initUpload(123L, req(), "alice")
        val dir = File(tempDir, "123-Alpha")
        dir.mkdirs()
        File(dir, "0003.png").writeBytes(byteArrayOf(1))
        File(dir, "0001.jpg").writeBytes(byteArrayOf(1))
        File(dir, "0007.jpeg").writeBytes(byteArrayOf(1))
        File(dir, "not-a-page.txt").writeBytes(byteArrayOf(1))
        File(dir, "0002.jpg").writeBytes(ByteArray(0)) // 空文件不算

        assertEquals(listOf(1, 3, 7), service.existingPages(123L))
    }

    @Test
    fun `initUpload reports existingPages after a previous push`() {
        service.initUpload(123L, req(), "alice")
        val dir = File(tempDir, "123-Alpha")
        dir.mkdirs()
        File(dir, "0001.jpg").writeBytes(byteArrayOf(1))

        val response = service.initUpload(123L, req(), "alice")

        assertEquals(listOf(1), response.existingPages)
    }

    @Test
    fun `existingPages recognises both 4 and 8 digit layouts (U2)`() {
        // U2 现状 bug：写端 2026-08-30 起统一 %08d，读端只认 %04d → existingPages
        // 恒空集、每次全量重传。修后两种磁盘既成事实都识别；3 位从未被任何写端
        // 产出，仍不认（与 DownloadDirIndex \d{4,} 同口径）。
        service.initUpload(123L, req(), "alice")
        val dir = File(tempDir, "123-Alpha")
        dir.mkdirs()
        File(dir, "0001.jpg").writeBytes(byteArrayOf(1))       // 历史 4 位布局
        File(dir, "0002.png").writeBytes(byteArrayOf(1))       // 历史 4 位布局
        File(dir, "00000007.jpg").writeBytes(byteArrayOf(1))   // 现行 8 位布局
        File(dir, "00000009.webp").writeBytes(byteArrayOf(1))  // 现行 8 位布局
        File(dir, "12.jpg").writeBytes(byteArrayOf(1))         // 3 位：从未产出
        File(dir, "not-a-page.txt").writeBytes(byteArrayOf(1)) // 非白名单扩展名

        assertEquals(listOf(1, 2, 7, 9), service.existingPages(123L))
    }

    @Test
    fun `pages written by storePage are found by existingPages without re-push (U2 regression)`() {
        // 端到端钉死 U2：storePage 落的 8 位文件必须立刻能被断点续传扫描看到。
        service.storePage(123L, 1, "page.jpg", tinyValidJpeg())

        assertEquals(listOf(1), service.existingPages(123L))
    }

    // ── initUpload × 墓碑 / 脏数据（U4 / U5） ────────────────────

    @Test
    fun `initUpload with force over a tombstone resets deleted (U4)`() {
        store[123L] = DownloadInfoEntity().apply {
            id = 9L
            gid = 123L
            token = "old"
            title = "Old"
            state = 3
            deleted = true
        }

        val response = service.initUpload(123L, req(force = true), "alice")

        assertTrue(response.success)
        val entity = store.getValue(123L)
        // 复位墓碑：重传成功后行必须回到 Web 列表（列表只列 deleted=false）。
        assertFalse(entity.deleted)
        assertEquals(9L, entity.id)
        assertEquals(2, entity.state)
        assertEquals("tok123", entity.token)
    }

    @Test
    fun `dirty multi-row gid data takes the first row instead of failing (U5)`() {
        // findByGid（单实体派生查询）撞同 gid 多行会抛 IncorrectResultSizeDataAccessException；
        // findAllByGid + firstOrNull 对脏数据容错，仲裁与写路径都落第一行。
        val first = DownloadInfoEntity().apply { id = 1L; gid = 555L; token = "a"; title = "First" }
        val second = DownloadInfoEntity().apply { id = 2L; gid = 555L; token = "b"; title = "Second" }
        `when`(repo.findAllByGid(555L)).thenReturn(listOf(first, second))

        val response = service.initUpload(555L, req(force = true), "alice")
        assertTrue(response.success)
        assertFalse(first.deleted)
        assertEquals("tok123", first.token)
        assertEquals("b", second.token)

        assertTrue(service.completeUpload(555L, UploadCompleteRequest(total = 20, done = 20)))
        assertEquals(3, first.state)
        assertEquals(0, second.state)

        service.storePage(555L, 1, "page.jpg", tinyValidJpeg())
        assertTrue(File(File(tempDir, "555-Alpha"), "00000001.jpg").isFile)
    }

    // ── storePage ────────────────────────────────────────────────

    /** 最小合法 JPEG：SOI + 非尾字节 + EOI（V 门 V1/V2 均过，长度=5）。 */
    private fun tinyValidJpeg() =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun `storePage writes the file preserving the original extension`() {
        val bytes = fixture("valid.png")
        service.storePage(123L, 7, "page.PNG", bytes)

        val file = File(downloadDir(123L), "00000007.png")
        assertTrue(file.isFile)
        assertTrue(file.readBytes().contentEquals(bytes))
    }

    @Test
    fun `storePage overwrites an existing page idempotently`() {
        service.storePage(123L, 1, "page.jpg", tinyValidJpeg())
        val validJpeg = fixture("valid.jpg")
        service.storePage(123L, 1, "page.jpg", validJpeg)

        val file = File(downloadDir(123L), "00000001.jpg")
        assertEquals(validJpeg.size.toLong(), file.length())
        assertTrue(file.readBytes().contentEquals(validJpeg))
    }

    @Test
    fun `storePage rejects page below one`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.storePage(123L, 0, "page.jpg", ByteArray(1))
        }
    }

    @Test
    fun `storePage refuses when upload is disabled`() {
        setUp(uploadEnabled = false)

        assertThrows(IllegalArgumentException::class.java) {
            service.storePage(123L, 1, "page.jpg", fixture("valid.jpg"))
        }
        assertFalse(File(downloadDir(123L), "00000001.jpg").exists())
    }

    @Test
    fun `storePage rejects an unsupported extension`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.storePage(123L, 1, "page.txt", fixture("valid.jpg"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.storePage(123L, 1, "page", fixture("valid.jpg"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.storePage(123L, 1, null, fixture("valid.jpg"))
        }
        assertFalse(File(downloadDir(123L), "00000001.txt").exists())
    }

    // ── storePage × V 门 × 基线（文件完整性 Wave 2 S3） ──────────

    @Test
    fun `storePage rejects a truncated page without writing a file or a baseline`() {
        // truncated.jpg：V2_TAIL（头过尾不过）；html_disguised.jpg：V1_MAGIC（HTML 伪装）；
        // 空字节：V1_MAGIC。三种拒收都不得落盘、不得建基线。
        listOf("truncated.jpg", "html_disguised.jpg").forEach { name ->
            val ex = assertThrows(IllegalArgumentException::class.java) {
                service.storePage(123L, 1, "page.jpg", fixture(name))
            }
            assertTrue(ex.message!!.contains("integrity gate"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.storePage(123L, 1, "page.jpg", ByteArray(0))
        }

        assertFalse(File(downloadDir(123L), "00000001.jpg").exists())
        assertFalse(downloadDir(123L).exists())
        assertTrue(hashStore.isEmpty(), "拒收路径不得建基线")
    }

    @Test
    fun `storePage writes the file and the sha256 baseline on a valid page`() {
        val bytes = fixture("valid.jpg")
        service.storePage(123L, 1, "page.jpg", bytes)

        val file = File(downloadDir(123L), "00000001.jpg")
        assertTrue(file.readBytes().contentEquals(bytes))

        // 基线行 0-based 页号（page-1）；字段口径见 PageFileHashEntity/Wave 1 契约。
        val baseline = hashStore[PageFileHashId(123L, 0)]
        assertNotNull(baseline)
        assertEquals("24ac74130806ae02d7e4ee72881b977601999c6c9f94ee1545ed4830459b737f", baseline!!.hash)
        assertEquals(bytes.size.toLong(), baseline.size)
        assertEquals("jpg", baseline.ext)
        assertEquals("sha256", baseline.algo)
        assertEquals("upload", baseline.origin)
        assertTrue(baseline.createdAt > 0)
        assertNull(baseline.lastVerifiedAt, "新建基线未巡检")
        assertNull(baseline.verdict, "新建基线未巡检")
    }

    @Test
    fun `storePage overwrite refreshes the existing baseline and resets the inspection verdict`() {
        hashStore[PageFileHashId(123L, 0)] = PageFileHashEntity().apply {
            gid = 123L
            page = 0
            ext = "jpg"
            size = 3
            hash = "old-hash"
            origin = "heal"
            createdAt = 1L
            lastVerifiedAt = 42L
            verdict = "ok"
        }

        val bytes = fixture("valid.png")
        service.storePage(123L, 1, "page.png", bytes)

        // 覆盖写 = 刷新同一行（同 PK 不增行），旧巡检结论作废。
        assertEquals(1, hashStore.size)
        val baseline = hashStore.getValue(PageFileHashId(123L, 0))
        assertEquals("248b07a3d0e1e0f67d43d18065be8f74434549c0fdde6b0bfc08a7835d41909f", baseline.hash)
        assertEquals(bytes.size.toLong(), baseline.size)
        assertEquals("png", baseline.ext)
        assertEquals("upload", baseline.origin)
        assertNull(baseline.lastVerifiedAt)
        assertNull(baseline.verdict)
        assertTrue(baseline.createdAt > 1L)
    }

    // ── completeUpload ───────────────────────────────────────────

    @Test
    fun `completeUpload marks the row finished with totals`() {
        service.initUpload(123L, req(), "alice")

        assertTrue(service.completeUpload(123L, UploadCompleteRequest(total = 20, done = 20)))

        val entity = store.getValue(123L)
        assertEquals(3, entity.state)
        assertEquals(20, entity.total)
        assertEquals(20, entity.done)
    }

    @Test
    fun `completeUpload returns false when no row exists`() {
        assertFalse(service.completeUpload(123L, UploadCompleteRequest(total = 20, done = 20)))
        assertNull(store[123L])
    }

    @Test
    fun `completeUpload discards a stale pending progress frame so it cannot overwrite the finished row (E2E)`() {
        // 二期 Wave 2 E2E 缝隙：同 taskId 的下载 worker 曾上报过进度、帧滞留在批量
        // 器 pending 里；上传收尾写 state=3 后若不丢弃，下一 tick 的 drain（守卫只
        // 跳 0/4，**不跳 3**）会把旧 done 覆盖到终态行上。与 deleteDownload 墓碑化
        // 同款 discard，包在 flush 锁内与在途 tick 串行化。
        val existingRow = DownloadInfoEntity().apply {
            id = 77L
            gid = 123L
            token = "tok"
            title = "Old"
            state = 2
            total = 10
            done = 3
        }
        val repo = mock(DownloadInfoRepository::class.java).apply {
            `when`(save(any(DownloadInfoEntity::class.java))).thenAnswer { inv ->
                val e = inv.getArgument<DownloadInfoEntity>(0)
                store[e.gid] = e
                e
            }
            `when`(findAllByGid(anyLong())).thenAnswer { inv ->
                listOfNotNull(store[inv.getArgument<Long>(0)])
            }
            // drain 的下一 tick 落库路径需要 findById——桩到同一行才能暴露覆盖。
            `when`(findById(77L)).thenReturn(java.util.Optional.of(existingRow))
        }
        store[123L] = existingRow

        val persister = DownloadProgressPersister(
            repo,
            scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor().apply { shutdownNow() },
        )
        val serviceWithPersister = DownloadUploadService(
            repo,
            hashRepo,
            SiteCoreConfigProperties().apply { download.path = tempDir.absolutePath },
            mock(ServerConfigService::class.java).apply {
                `when`(getBoolean(anyString(), anyBoolean())).thenReturn(true)
            },
            persister,
        )

        persister.record(77L, 5) // 旧帧（上传终态写之前滞留）
        assertTrue(serviceWithPersister.completeUpload(123L, UploadCompleteRequest(total = 20, done = 20)))

        assertEquals(3, existingRow.state)
        assertEquals(20, existingRow.done)

        // 下一 tick 落库：pending 已被 discard，旧帧不得覆盖 state=3 行的 done。
        persister.tick()
        assertEquals(20, existingRow.done, "旧 done 帧不得覆盖 state=3 行（E2E 缝隙）")
        assertEquals(3, existingRow.state)
    }

    @Test
    // MASTER-2026-08-22 S3：upload_enabled 关闭时 finalize 被拒绝（与 storePage 同语义）。
    fun `completeUpload refuses when upload is disabled`() {
        setUp(uploadEnabled = false)

        org.junit.jupiter.api.assertThrows<UploadDisabledException> {
            service.completeUpload(123L, UploadCompleteRequest(total = 20, done = 20))
        }
        assertNull(store[123L])
    }
}
