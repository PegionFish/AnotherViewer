package com.hippo.anotherviewer.web.service

import com.hippo.anotherviewer.web.any
import com.hippo.anotherviewer.web.argThatK
import com.hippo.anotherviewer.web.eq
import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.dto.DownloadAddRequest
import com.hippo.anotherviewer.web.entity.DownloadInfoEntity
import com.hippo.anotherviewer.web.repository.DownloadInfoRepository
import com.hippo.anotherviewer.web.repository.DownloadLabelRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.context.ApplicationEventPublisher
import java.util.Optional
/**
 * Pins the G4 metrics fixes and the EH-DOWN download-start semantics
 * (plan-2026-08-30 §3.2/§3.3): state counts via COUNT SQL (countByState,
 * no entity materialisation) and immediate FAILED instead of a silent
 * pending task while EH is DOWN.
 */
class DownloadServiceTest {

    /** addDownload 会在 download.path 下建目录——用临时目录隔离，不污染模块 CWD。 */
    @TempDir
    lateinit var tempDir: java.io.File

    private lateinit var downloadRepository: DownloadInfoRepository
    private lateinit var labelRepository: DownloadLabelRepository
    private lateinit var availability: EhAvailabilityService
    private lateinit var downloadDirIndex: DownloadDirIndex
    private lateinit var historyRepository: com.hippo.anotherviewer.web.repository.HistoryInfoRepository
    private lateinit var service: DownloadService

    @BeforeEach
    fun setUp() {
        downloadRepository = mock(DownloadInfoRepository::class.java)
        labelRepository = mock(DownloadLabelRepository::class.java)
        availability = mock(EhAvailabilityService::class.java)
        downloadDirIndex = mock(DownloadDirIndex::class.java)
        historyRepository = mock(com.hippo.anotherviewer.web.repository.HistoryInfoRepository::class.java)
        val sessionManager = mock(SiteSessionManager::class.java)
        service = DownloadService(
            downloadRepository,
            labelRepository,
            SiteCoreConfigProperties().apply {
                download.path = java.io.File(tempDir, "downloads").absolutePath
            },
            mock(ApplicationEventPublisher::class.java),
            mock(ImageCacheService::class.java),
            sessionManager,
            mock(GalleryLookupService::class.java),
            mock(ServerConfigService::class.java),
            availability,
            downloadDirIndex,
            historyRepository,
            stubProvider("test-user"),
        )
    }

    /** A7-1: 纯 Mockito 单测不碰 SecurityContext——注入固定用户的 Provider stub。 */
    private fun stubProvider(name: String): com.hippo.anotherviewer.web.config.CurrentUsernameProvider =
        mock(com.hippo.anotherviewer.web.config.CurrentUsernameProvider::class.java)
            .apply { `when`(currentUsername()).thenReturn(name) }

    @Test
    fun `state counts use countByStateAndDeletedFalse instead of loading entities`() {
        // A7-2（D8）: stats 计数不计墓碑（COUNT SQL，不加载实体，语义同前）。
        `when`(downloadRepository.countByStateAndDeletedFalse(3)).thenReturn(9171L)
        `when`(downloadRepository.countByStateAndDeletedFalse(4)).thenReturn(3L)

        assertEquals(9171L, service.getCompletedDownloadCount())
        assertEquals(3L, service.getFailedDownloadCount())

        verify(downloadRepository).countByStateAndDeletedFalse(3)
        verify(downloadRepository).countByStateAndDeletedFalse(4)
        verify(downloadRepository, never()).findByStateAndDeletedFalse(3)
        verify(downloadRepository, never()).findByStateAndDeletedFalse(4)
    }

    @Test
    fun `listDownloads fills readProgress with one batched history query (S10)`() {
        val row = DownloadInfoEntity().apply {
            id = 1L; gid = 42L; token = "tok42"; title = "T"; total = 10; done = 5
        }
        val rowNoHistory = DownloadInfoEntity().apply {
            id = 2L; gid = 43L; token = "tok43"; title = "T2"
        }
        `when`(downloadRepository.findAllByDeletedFalse(any(org.springframework.data.domain.Pageable::class.java)))
            .thenReturn(org.springframework.data.domain.PageImpl(listOf(row, rowNoHistory)))
        `when`(historyRepository.findByGidIn(listOf(42L, 43L))).thenReturn(
            listOf(com.hippo.anotherviewer.web.entity.HistoryInfoEntity().apply {
                gid = 42L; token = "tok42"; page = 7
            })
        )

        val response = service.listDownloads()

        val byGid = response.downloads.associateBy { it.gid }
        assertEquals(7, byGid[42L]!!.readProgress)
        // 无历史行 → 0（未读），与其他列表端点语义一致。
        assertEquals(0, byGid[43L]!!.readProgress)
        verify(historyRepository).findByGidIn(listOf(42L, 43L))
    }

    @Test
    fun `list limit above 500 is clamped back to 500`() {
        // 分页契约：limit 钳制 [1,500]（2026-09-06 回滚 098f1c04 的 100_000
        // 放宽）——size 以 500 进 PageRequest，返回行数同步受钳。
        val rows = (1..500L).map {
            DownloadInfoEntity().apply { id = it; gid = it; token = "t$it"; title = "T$it" }
        }
        `when`(
            downloadRepository.findAllByDeletedFalse(any(org.springframework.data.domain.Pageable::class.java))
        ).thenReturn(org.springframework.data.domain.PageImpl(rows))
        `when`(
            historyRepository.findByGidIn(any<Collection<Long>>())
        ).thenReturn(emptyList())
        `when`(downloadRepository.countByDeletedFalse()).thenReturn(500L)

        val response = service.listDownloads(limit = 100_000)

        assertEquals(500, response.downloads.size)
        assertEquals(500, response.total)
        // Pageable 拿到的 size 被钳到 500，而非透传 100_000。
        org.mockito.Mockito.verify(downloadRepository).findAllByDeletedFalse(
            com.hippo.anotherviewer.web.argThatK<org.springframework.data.domain.Pageable> { it.pageSize == 500 }
        )
    }

    @Test
    fun `restartAll skips disk-verified rows and restarts the rest`() {
        `when`(availability.isBlocked()).thenReturn(false)
        // DownloadDirs.resolve：相对 downloadDir 原样采用 → CWD 下建测试目录。
        val dir = java.io.File("dl-test-42").apply { mkdirs() }
        try {
            java.io.File(dir, "0001.jpg").writeBytes(ByteArray(10))
            java.io.File(dir, "0002.jpg").writeBytes(ByteArray(10))
            java.io.File(dir, "0003.jpg").writeBytes(ByteArray(10))
            val verified = DownloadInfoEntity().apply {
                id = 1L; gid = 42L; token = "tok-v"; total = 3; done = 3
                state = 3; downloadDir = "dl-test-42" // 相对路径 → resolve 原样返回
            }
            `when`(downloadRepository.findAllByDeletedFalseOrderById()).thenReturn(listOf(verified))
            `when`(downloadRepository.findById(1L)).thenReturn(Optional.of(verified))
            `when`(downloadRepository.save(any(DownloadInfoEntity::class.java))).thenAnswer { it.getArgument(0) }

            val restarted = service.restartAllDownloads()

            assertEquals(0, restarted) // verified 行跳过；无其他行可重下
            verify(downloadRepository).save(verified) // 仍写回 state=3/done=3
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `completeIfVerified only upgrades when disk holds full pages`() {
        val dir = java.io.File("dl-test-99").apply { mkdirs() }
        try {
            java.io.File(dir, "0001.jpg").writeBytes(ByteArray(10))
            java.io.File(dir, "0002.jpg").writeBytes(ByteArray(10))
            java.io.File(dir, "0003.jpg").writeBytes(ByteArray(10))
            java.io.File(dir, "0004.jpg").writeBytes(ByteArray(10))
            java.io.File(dir, "0005.jpg").writeBytes(ByteArray(10))

            val entity = DownloadInfoEntity().apply {
                id = 7L; gid = 99L; token = "tok"; total = 5; done = 2; state = 0
                downloadDir = "dl-test-99"
            }
            `when`(downloadRepository.findAllByGid(99L)).thenReturn(listOf(entity))
            `when`(downloadRepository.findById(7L)).thenReturn(Optional.of(entity))
            `when`(downloadRepository.save(any(DownloadInfoEntity::class.java))).thenAnswer { it.getArgument(0) }

            service.completeIfVerified(99L)

            assertEquals(3, entity.state)
            assertEquals(5, entity.done)
            verify(downloadRepository).save(entity)
            verify(downloadDirIndex).invalidate(99L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `startDownload marks the task FAILED with EH_UNAVAILABLE error while blocked`() {
        `when`(availability.isBlocked()).thenReturn(true)
        val entity = DownloadInfoEntity().apply {
            id = 1
            gid = 42L
            token = "tok"
            state = 0
        }
        `when`(downloadRepository.findById(1L)).thenReturn(Optional.of(entity))
        `when`(downloadRepository.save(any(DownloadInfoEntity::class.java))).thenAnswer { it.getArgument(0) }

        assertFalse(service.startDownload(1L))
        assertEquals(4, entity.state, "blocked start must FAILED immediately")
        assertEquals("EH_UNAVAILABLE: EH 平台当前不可达", entity.error)
        verify(downloadRepository).save(entity)
    }

    @Test
    fun `startDownload checks availability even for resumable paused tasks`() {
        // DOWN 重新开始：paused(0) 任务不被静默改回 1（无 worker、保持可重试的 FAILED 语义）。
        `when`(availability.isBlocked()).thenReturn(true)
        val entity = DownloadInfoEntity().apply {
            id = 1
            gid = 42L
            token = "tok"
            state = 0
        }
        `when`(downloadRepository.findById(1L)).thenReturn(Optional.of(entity))
        `when`(downloadRepository.save(any(DownloadInfoEntity::class.java))).thenAnswer { it.getArgument(0) }

        service.startDownload(1L)

        assertEquals(4, entity.state)
    }

    // ── A7-1: 统一 stamping + worker 线程规则（§3.4） ──

    @Test
    fun `addDownload stamps username and lastModified on the new row`() {
        `when`(downloadRepository.findAllByGid(77L)).thenReturn(emptyList())

        assertTrue(service.addDownload(DownloadAddRequest(gid = 77L, token = "a1b2c3d4e5", title = "T", thumb = null)))

        verify(downloadRepository).save(
            argThatK {
                it.gid == 77L && it.username == "test-user" && it.lastModified > 0 && it.time > 0
            }
        )
    }

    @Test
    fun `state writes bump lastModified but never write username`() {
        // startDownload 的 EH-DOWN 状态写（等价 updateEntity 路径）：state 变更是同步
        // 可见字段 → bump lastModified；worker/请求线程规则：username 不写。
        val entity = DownloadInfoEntity().apply {
            id = 1
            gid = 42L
            token = "tok"
            state = 0
            lastModified = 3L
        }
        `when`(availability.isBlocked()).thenReturn(true)
        `when`(downloadRepository.findById(1L)).thenReturn(Optional.of(entity))
        `when`(downloadRepository.save(any(DownloadInfoEntity::class.java))).thenAnswer { it.getArgument(0) }

        assertFalse(service.startDownload(1L))

        assertEquals(4, entity.state)
        assertTrue(entity.lastModified > 3L)
        assertNull(entity.username)
    }

    @Test
    fun `updateEntity skips tombstoned rows so state writes cannot resurrect them`() {
        // deleteDownload 后 cancelDownload 的终态写不得复活墓碑（deleted=true 行直接跳过）。
        val tombstone = DownloadInfoEntity().apply {
            id = 2
            gid = 43L
            token = "tok"
            state = 2
            deleted = true
        }
        `when`(downloadRepository.findById(2L)).thenReturn(Optional.of(tombstone))

        assertTrue(service.cancelDownload(2L))

        assertEquals(2, tombstone.state) // 状态写被跳过
        verify(downloadRepository, never()).save(any(DownloadInfoEntity::class.java))
    }

    // ── A7-2: 软删 / 复活 / 过滤 ──

    @Test
    fun `deleteDownload deletes files but keeps a tombstoned row`() {
        val dir = java.io.File("dl-test-del-7").apply { mkdirs() }
        try {
            java.io.File(dir, "0001.jpg").writeBytes(ByteArray(10))
            val row = DownloadInfoEntity().apply {
                id = 7L; gid = 71L; token = "tok"; state = 3
                downloadDir = "dl-test-del-7"; lastModified = 3L
            }
            `when`(downloadRepository.findById(7L)).thenReturn(Optional.of(row))
            `when`(downloadRepository.existsById(7L)).thenReturn(true)
            `when`(downloadRepository.save(any(DownloadInfoEntity::class.java))).thenAnswer { it.getArgument(0) }

            assertTrue(service.deleteDownload(7L))

            // 磁盘文件照删（行为不变）。
            assertFalse(dir.exists())
            // DB 行墓碑化：行保留、deleted=true、水位 bump（不再物理删）。
            assertTrue(row.deleted)
            assertTrue(row.lastModified > 3L)
            verify(downloadRepository, never()).deleteById(7L)
        } finally {
            java.io.File("dl-test-del-7").deleteRecursively()
        }
    }

    @Test
    fun `addDownload resurrects a tombstoned row instead of rejecting`() {
        val tombstone = DownloadInfoEntity().apply {
            id = 9L; gid = 91L; token = "old"; title = "Old"; state = 3; done = 7
            deleted = true
        }
        `when`(downloadRepository.findAllByGid(91L)).thenReturn(listOf(tombstone))
        `when`(downloadRepository.save(any(DownloadInfoEntity::class.java))).thenAnswer { it.getArgument(0) }

        assertTrue(service.addDownload(DownloadAddRequest(gid = 91L, token = "new", title = "New", thumb = null)))

        assertTrue(tombstone.deleted.not())
        assertEquals("new", tombstone.token)
        assertEquals(0, tombstone.state)
        assertEquals(0, tombstone.done)
        assertTrue(tombstone.lastModified > 0)
        verify(downloadRepository, never()).deleteById(9L)
    }

    @Test
    fun `getDownloadInfo returns null for a tombstoned row`() {
        val tombstone = DownloadInfoEntity().apply { id = 3L; gid = 44L; token = "tok"; deleted = true }
        `when`(downloadRepository.findById(3L)).thenReturn(Optional.of(tombstone))

        assertNull(service.getDownloadInfo(3L))
    }

    @Test
    fun `listDownloads queries live rows only across label and default paths`() {
        // A7-2（D1）: 默认路径与 label 路径都走 deleted=false 查询（total 同步）。
        val live = DownloadInfoEntity().apply { id = 1L; gid = 42L; token = "tok"; title = "T" }
        `when`(downloadRepository.findAllByDeletedFalse(any(org.springframework.data.domain.Pageable::class.java)))
            .thenReturn(org.springframework.data.domain.PageImpl(listOf(live)))
        `when`(downloadRepository.countByDeletedFalse()).thenReturn(1L)
        `when`(labelRepository.findAll()).thenReturn(emptyList())
        `when`(historyRepository.findByGidIn(any<Collection<Long>>())).thenReturn(emptyList())

        val response = service.listDownloads()

        assertEquals(1, response.total)
        verify(downloadRepository, never()).findAll(any(org.springframework.data.domain.Pageable::class.java))
        verify(downloadRepository, never()).count()

        `when`(downloadRepository.findByLabelAndDeletedFalse(eq(5), any(org.springframework.data.domain.Pageable::class.java)))
            .thenReturn(org.springframework.data.domain.PageImpl(listOf(live)))
        `when`(downloadRepository.countByLabelAndDeletedFalse(5)).thenReturn(1L)

        assertEquals(1, service.listDownloads(labelId = 5).total)
    }

    @Test
    fun `startAll restartAll pauseAll skip tombstones`() {
        // A7-2（D5/D6/D7）: 生命周期批量遍历用 deleted=false 查询。
        val waiting = DownloadInfoEntity().apply { id = 1L; gid = 42L; token = "tok"; state = 0 }
        `when`(downloadRepository.findByStateAndDeletedFalse(0)).thenReturn(listOf(waiting))
        `when`(downloadRepository.findByStateAndDeletedFalse(1)).thenReturn(emptyList())
        `when`(downloadRepository.findByStateAndDeletedFalse(2)).thenReturn(emptyList())
        `when`(downloadRepository.findAllByDeletedFalseOrderById()).thenReturn(emptyList())
        `when`(downloadRepository.findById(1L)).thenReturn(Optional.of(waiting.copyLike()))
        `when`(downloadRepository.save(any(DownloadInfoEntity::class.java))).thenAnswer { it.getArgument(0) }
        `when`(availability.isBlocked()).thenReturn(false)

        service.startAllDownloads()
        service.pauseAllDownloads()
        service.restartAllDownloads()

        verify(downloadRepository).findByStateAndDeletedFalse(0)
        verify(downloadRepository).findByStateAndDeletedFalse(1)
        verify(downloadRepository).findByStateAndDeletedFalse(2)
        verify(downloadRepository).findAllByDeletedFalseOrderById()
        verify(downloadRepository, never()).findAll()
    }

    // ── F8: 画廊并发运行时生效 ──

    @Test
    fun `applyGalleryConcurrency resizes the worker pool at runtime`() {
        // 构造期取 yml 默认 3（core==max 不变式）。
        assertEquals(3, service.galleryConcurrency)

        service.applyGalleryConcurrency(8)
        assertEquals(8, service.galleryConcurrency)

        // 与当前值相等时是 no-op。
        service.applyGalleryConcurrency(8)
        assertEquals(8, service.galleryConcurrency)

        // 越界值钳制到 DTO 允许的 1..20；扩缩两个方向都不抛 core>max。
        service.applyGalleryConcurrency(999)
        assertEquals(20, service.galleryConcurrency)
        service.applyGalleryConcurrency(0)
        assertEquals(1, service.galleryConcurrency)
    }

    /** 测试辅助：构造同 id 的独立副本（startDownload 会把 state 写进加载的行）。 */
    private fun DownloadInfoEntity.copyLike(): DownloadInfoEntity = DownloadInfoEntity().apply {
        id = this@copyLike.id
        gid = this@copyLike.gid
        token = this@copyLike.token
        title = this@copyLike.title
        state = this@copyLike.state
        total = this@copyLike.total
        done = this@copyLike.done
        label = this@copyLike.label
        downloadDir = this@copyLike.downloadDir
    }
}
