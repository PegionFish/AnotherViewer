package com.hippo.anotherviewer.web.service

import com.hippo.anotherviewer.web.entity.HistoryInfoEntity
import com.hippo.anotherviewer.web.repository.HistoryInfoRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import com.hippo.anotherviewer.web.any
import com.hippo.anotherviewer.web.argThatK
import com.hippo.anotherviewer.web.captureK
import com.hippo.anotherviewer.web.eq
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable

class HistoryServiceTest {

    private lateinit var historyRepository: HistoryInfoRepository
    private lateinit var historyService: HistoryService

    @BeforeEach
    fun setUp() {
        historyRepository = mock(HistoryInfoRepository::class.java)
        historyService = HistoryService(historyRepository, stubProvider("test-user"))
    }

    /** A7-1: 纯 Mockito 单测不碰 SecurityContext——注入固定用户的 Provider stub。 */
    private fun stubProvider(name: String): com.hippo.anotherviewer.web.config.CurrentUsernameProvider =
        mock(com.hippo.anotherviewer.web.config.CurrentUsernameProvider::class.java)
            .apply { `when`(currentUsername()).thenReturn(name) }

    private fun entity(gid: Long, time: Long): HistoryInfoEntity {
        val e = HistoryInfoEntity()
        e.gid = gid
        e.token = "token$gid"
        e.title = "Title $gid"
        e.time = time
        return e
    }

    @Test
    fun `absent params returns the full history list`() {
        `when`(historyRepository.findAllByOrderByTimeDesc())
            .thenReturn(listOf(entity(3, 3000), entity(2, 2000), entity(1, 1000)))

        val response = historyService.listHistory()

        assertEquals(3, response.history.size)
        assertEquals(3, response.total)
        assertEquals(listOf(3L, 2L, 1L), response.history.map { it.gid })
        verify(historyRepository).findAllByOrderByTimeDesc()
        verify(historyRepository, never()).findHistoryPaged(any(Pageable::class.java))
    }

    @Test
    fun `provided params returns paged slice with total`() {
        val paged = PageImpl(listOf(entity(2, 2000), entity(1, 1000)), Pageable.unpaged(), 10)
        `when`(historyRepository.findHistoryPaged(any(Pageable::class.java))).thenReturn(paged)

        val response = historyService.listHistory(page = 1, pageSize = 50)

        assertEquals(listOf(2L, 1L), response.history.map { it.gid })
        assertEquals(10, response.total)
        verify(historyRepository).findHistoryPaged(argThatK { it.pageNumber == 1 && it.pageSize == 50 })
        verify(historyRepository, never()).findAllByOrderByTimeDesc()
    }

    @Test
    fun `pageSize is clamped to 200`() {
        `when`(historyRepository.findHistoryPaged(any(Pageable::class.java)))
            .thenReturn(PageImpl(emptyList<HistoryInfoEntity>(), Pageable.unpaged(), 0))

        historyService.listHistory(page = 0, pageSize = 500)

        val captor = ArgumentCaptor.forClass(Pageable::class.java)
        verify(historyRepository).findHistoryPaged(captureK<Pageable>(captor))
        assertEquals(0, captor.value.pageNumber)
        assertEquals(200, captor.value.pageSize)
    }

    @Test
    fun `page only defaults pageSize to 50`() {
        `when`(historyRepository.findHistoryPaged(any(Pageable::class.java)))
            .thenReturn(PageImpl(emptyList<HistoryInfoEntity>(), Pageable.unpaged(), 0))

        historyService.listHistory(page = 2)

        val captor = ArgumentCaptor.forClass(Pageable::class.java)
        verify(historyRepository).findHistoryPaged(captureK<Pageable>(captor))
        assertEquals(2, captor.value.pageNumber)
        assertEquals(50, captor.value.pageSize)
    }

    @Test
    fun `pageSize only defaults page to 0`() {
        `when`(historyRepository.findHistoryPaged(any(Pageable::class.java)))
            .thenReturn(PageImpl(emptyList<HistoryInfoEntity>(), Pageable.unpaged(), 0))

        historyService.listHistory(pageSize = 10)

        val captor = ArgumentCaptor.forClass(Pageable::class.java)
        verify(historyRepository).findHistoryPaged(captureK<Pageable>(captor))
        assertEquals(0, captor.value.pageNumber)
        assertEquals(10, captor.value.pageSize)
    }

    @Test
    fun `addHistory with mode persists it`() {
        `when`(historyRepository.findByGid(7L)).thenReturn(null)

        historyService.addHistory(7L, "t7", "Title 7", null, null, 1, 5.0f, mode = 9)

        verify(historyRepository).save(argThatK { it.gid == 7L && it.mode == 9 })
    }

    @Test
    fun `addHistory without mode defaults to 0`() {
        `when`(historyRepository.findByGid(8L)).thenReturn(null)

        historyService.addHistory(8L, "t8", "Title 8", null, null, 1, 5.0f)

        verify(historyRepository).save(argThatK { it.gid == 8L && it.mode == 0 })
    }

    @Test
    fun `addHistory on existing row updates mode`() {
        val existing = entity(9L, 1000).apply { mode = 5 }
        `when`(historyRepository.findByGid(9L)).thenReturn(existing)

        historyService.addHistory(9L, "t9", "Title 9", null, null, 1, 5.0f, mode = 7)

        assertEquals(7, existing.mode)
        verify(historyRepository).save(existing)
    }

    // ── A7-1: 统一 stamping（新行 username/lastModified；更新只 bump 水位） ──

    @Test
    fun `addHistory insert stamps username and lastModified`() {
        `when`(historyRepository.findByGid(21L)).thenReturn(null)

        historyService.addHistory(21L, "t21", "Title 21", null, null, 1, 5.0f)

        verify(historyRepository).save(
            argThatK { it.gid == 21L && it.username == "test-user" && it.lastModified > 0 }
        )
    }

    @Test
    fun `addHistory update bumps lastModified for incremental pull and never overwrites the owner`() {
        val existing = entity(22L, 1000).apply { username = "alice"; lastModified = 5L }
        `when`(historyRepository.findByGid(22L)).thenReturn(existing)

        historyService.addHistory(22L, "t22", "Title 22", null, null, 1, 5.0f)

        assertEquals("alice", existing.username)
        assertTrue(existing.lastModified > 5L)
        verify(historyRepository).save(existing)
    }

    @Test
    fun `addHistory update claims a legacy null-username row in place`() {
        val existing = entity(23L, 1000)
        `when`(historyRepository.findByGid(23L)).thenReturn(existing)

        historyService.addHistory(23L, "t23", "Title 23", null, null, 1, 5.0f)

        assertEquals("test-user", existing.username)
        verify(historyRepository).save(existing)
    }

    @Test
    fun `updateFavoriteSlot bumps lastModified only on change`() {
        val row = entity(24L, 1000).apply { favoriteSlot = 3; lastModified = 7L }
        `when`(historyRepository.findByGid(24L)).thenReturn(row)

        // 值未变：不产生任何写（无同步流量）。
        assertTrue(historyService.updateFavoriteSlot(24L, 3))
        verify(historyRepository, never()).save(any(HistoryInfoEntity::class.java))

        // 值变化：写回 + bump lastModified（favoriteSlot 是同步可见字段）。
        assertTrue(historyService.updateFavoriteSlot(24L, 5))
        assertEquals(5, row.favoriteSlot)
        assertTrue(row.lastModified > 7L)
        verify(historyRepository).save(row)
    }

    // ── A7-2: 软删 / clearHistory 墓碑化 / 重读复活 ──

    @Test
    fun `addHistory on a tombstoned row revives it on reread`() {
        // clearHistory 后重读同一画廊必须复活历史行（对齐 mergeHistory 墓碑复活），
        // 否则墓碑被反复更新而列表永远为空——「清空后重读」功能性丢失。
        val tombstone = entity(25L, 1000).apply { deleted = true; lastModified = 5L }
        `when`(historyRepository.findByGid(25L)).thenReturn(tombstone)

        historyService.addHistory(25L, "t25", "Title 25", null, null, 1, 5.0f)

        assertFalse(tombstone.deleted)
        assertTrue(tombstone.lastModified > 5L)
        verify(historyRepository).save(tombstone)
    }

    @Test
    fun `clearHistory tombstones rows instead of deleting`() {
        val mine = entity(1L, 3000).apply { username = "test-user"; lastModified = 10L }
        val legacyNull = entity(2L, 2000)
        `when`(historyRepository.findByUsername("test-user")).thenReturn(listOf(mine))
        `when`(historyRepository.findAllByUsernameIsNull()).thenReturn(listOf(legacyNull))

        historyService.clearHistory()

        // 行数不变、deleted=true、lastModified bump（增量 pull 才能拿到墓碑）；
        // 不再 deleteAll() 物理删——那会让删除永不传播。
        assertTrue(mine.deleted)
        assertTrue(mine.lastModified > 10L)
        assertTrue(legacyNull.deleted)
        assertTrue(legacyNull.lastModified > 0L)
        // NULL 存量行就地收养（等价一次 adopt）。
        assertEquals("test-user", legacyNull.username)
        verify(historyRepository, never()).deleteAll()
        verify(historyRepository, never()).delete(any(HistoryInfoEntity::class.java))
    }

    @Test
    fun `clearHistory scopes to the acting user`() {
        // 多用户作用域：只墓碑化当前用户的行（+NULL 收养）——他人行不在
        // findByUsername(当前用户) 的结果里（作用域由查询承载），已墓碑的行
        // 不重复 bump 水位。
        val mine = entity(1L, 3000).apply { username = "test-user" }
        val other = entity(4L, 500).apply { username = "alice" }
        val alreadyTombstoned = entity(3L, 1000).apply { username = "test-user"; deleted = true; lastModified = 10L }
        `when`(historyRepository.findByUsername("test-user")).thenReturn(listOf(mine, alreadyTombstoned))
        `when`(historyRepository.findAllByUsernameIsNull()).thenReturn(emptyList())

        historyService.clearHistory()

        assertTrue(mine.deleted)
        assertTrue(mine.lastModified > 0L)
        verify(historyRepository, never()).save(other)             // 他人行不碰
        assertFalse(other.deleted)
        verify(historyRepository, never()).save(alreadyTombstoned) // 已墓碑不重复 bump
        assertEquals(10L, alreadyTombstoned.lastModified)
    }

    @Test
    fun `listHistory is empty after clearHistory`() {
        // 端到端同义验收：墓碑化后 REST 列表为空，但行仍在仓储。
        val mine = entity(1L, 3000).apply { username = "test-user" }
        val legacyNull = entity(2L, 2000)
        `when`(historyRepository.findByUsername("test-user")).thenReturn(listOf(mine, legacyNull))
        `when`(historyRepository.findAllByUsernameIsNull()).thenReturn(emptyList())
        `when`(historyRepository.findAllByOrderByTimeDesc()).thenAnswer { listOf(mine, legacyNull) }

        historyService.clearHistory()

        val response = historyService.listHistory()
        assertTrue(response.history.isEmpty())
        assertEquals(0, response.total)
        verify(historyRepository, never()).deleteAll()
    }

    @Test
    fun `addHistory rejects masked serial title on insert (PrivacyMask 脱敏防污染)`() {
        `when`(historyRepository.findByGid(12L)).thenReturn(null)

        historyService.addHistory(12L, "t12", "#12", null, null, 1, 5.0f)

        verify(historyRepository).save(argThatK { it.gid == 12L && it.title == null })
    }

    @Test
    fun `addHistory keeps real title on insert`() {
        `when`(historyRepository.findByGid(13L)).thenReturn(null)

        historyService.addHistory(13L, "t13", "(C99) Real Title", null, null, 1, 5.0f)

        verify(historyRepository).save(argThatK { it.gid == 13L && it.title == "(C99) Real Title" })
    }

    // ── S3: addHistory page 语义——null 保持已存值，非 null 原样写入（0=重读） ──

    @Test
    fun `addHistory with page persists it on a new row`() {
        `when`(historyRepository.findByGid(11L)).thenReturn(null)

        historyService.addHistory(11L, "t11", "Title 11", null, null, 1, 5.0f, page = 37)

        verify(historyRepository).save(argThatK { it.gid == 11L && it.page == 37 })
    }

    @Test
    fun `addHistory without page keeps the stored progress`() {
        val existing = entity(12L, 1000).apply { page = 42 }
        `when`(historyRepository.findByGid(12L)).thenReturn(existing)

        historyService.addHistory(12L, "t12", "Title 12", null, null, 1, 5.0f)

        assertEquals(42, existing.page)
        verify(historyRepository).save(existing)
    }

    @Test
    fun `addHistory with explicit page 0 rewrites the stored progress to zero`() {
        val existing = entity(13L, 1000).apply { page = 42 }
        `when`(historyRepository.findByGid(13L)).thenReturn(existing)

        historyService.addHistory(13L, "t13", "Title 13", null, null, 1, 5.0f, page = 0)

        assertEquals(0, existing.page)
        verify(historyRepository).save(existing)
    }

    @Test
    fun `addHistory clamps a negative page to zero`() {
        `when`(historyRepository.findByGid(14L)).thenReturn(null)

        historyService.addHistory(14L, "t14", "Title 14", null, null, 1, 5.0f, page = -3)

        verify(historyRepository).save(argThatK { it.gid == 14L && it.page == 0 })
    }

    @Test
    fun `toItem returns the entity page in list response`() {
        val e = entity(15L, 1000).apply { page = 9 }
        `when`(historyRepository.findAllByOrderByTimeDesc()).thenReturn(listOf(e))

        val response = historyService.listHistory()

        assertEquals(9, response.history.single().page)
    }

    @Test
    fun `toItem returns the entity mode in list response`() {
        val e = entity(5L, 5000).apply { mode = 5 }
        `when`(historyRepository.findAllByOrderByTimeDesc()).thenReturn(listOf(e))

        val response = historyService.listHistory()

        assertEquals(5, response.history.single().mode)
    }

    @Test
    fun `listHistory hides tombstone rows deleted by sync`() {
        val live = entity(1L, 3000)
        val tombstone = entity(2L, 2000).apply { deleted = true }
        `when`(historyRepository.findAllByOrderByTimeDesc()).thenReturn(listOf(live, tombstone))

        val response = historyService.listHistory()

        assertEquals(listOf(1L), response.history.map { it.gid })
        assertEquals(1, response.total)
    }

    @Test
    fun `listHistory with only tombstones returns an empty list`() {
        val tombstone = entity(2L, 2000).apply { deleted = true }
        `when`(historyRepository.findAllByOrderByTimeDesc()).thenReturn(listOf(tombstone))

        val response = historyService.listHistory()

        assertTrue(response.history.isEmpty())
        assertEquals(0, response.total)
    }

    @Test
    fun `q filter is answered by DB paging without a full-table load`() {
        // P2: q 子串过滤下沉 DB（LIKE + DB 分页）——不再 findAllByOrderByTimeDesc
        // 不分页全表载入；total = DB 匹配总数，信封与旧内存路径一致。
        val pageable = PageRequest.of(0, 50)
        `when`(historyRepository.findLiveByTitleOrTitleJpnContainingPaged("futa", pageable))
            .thenReturn(
                PageImpl(
                    listOf(entity(3, 3000, "Futanari Story"), entity(1, 1000, "Futa and More")),
                    pageable,
                    2,
                )
            )

        val response = historyService.listHistory(q = "futa")

        assertEquals(listOf(3L, 1L), response.history.map { it.gid })
        assertEquals(2, response.total)
        verify(historyRepository).findLiveByTitleOrTitleJpnContainingPaged("futa", pageable)
        verify(historyRepository, never()).findAllByOrderByTimeDesc()
    }

    @Test
    fun `q filter honours DB pagination with match count as total`() {
        // 0 基页码透传 DB（page=1 → PageRequest.of(1, 2)），total = 匹配总数 5。
        val pageable = PageRequest.of(1, 2)
        `when`(historyRepository.findLiveByTitleOrTitleJpnContainingPaged("Title", pageable))
            .thenReturn(
                PageImpl(
                    listOf(entity(3, 3000, "Title 3"), entity(2, 2000, "Title 2")),
                    pageable,
                    5,
                )
            )

        val response = historyService.listHistory(page = 1, pageSize = 2, q = "Title")

        assertEquals(listOf(3L, 2L), response.history.map { it.gid })
        assertEquals(5, response.total)
        verify(historyRepository).findLiveByTitleOrTitleJpnContainingPaged("Title", pageable)
        verify(historyRepository, never()).findAllByOrderByTimeDesc()
    }

    @Test
    fun `regex filter runs only on the DB pre-filtered set`() {
        // P2: regex 保内存语义，但先推字面种子 "futa"（来自 (?i)^futa）做 DB LIKE
        // 预过滤，不再全表载入；titleJpn 命中同样生效。
        `when`(historyRepository.findLiveByTitleOrTitleJpnContaining("futa"))
            .thenReturn(listOf(entity(1, 3000, "Plain", "フタナリ"), entity(2, 2000, "Futanari Story")))

        val response = historyService.listHistory(q = "(?i)^futa", regex = true)

        assertEquals(listOf(2L), response.history.map { it.gid })
        assertEquals(1, response.total)
        verify(historyRepository).findLiveByTitleOrTitleJpnContaining("futa")
        verify(historyRepository, never()).findAllByOrderByTimeDesc()
    }

    @Test
    fun `regex without derivable literal seed falls back to the legacy full-set path`() {
        // 顶层交替（a|b）无公共保证字面 → 种子为 null，回退存量全量路径（语义优先）。
        `when`(historyRepository.findAllByOrderByTimeDesc())
            .thenReturn(listOf(entity(1, 3000, "alpha"), entity(2, 2000, "bravo")))

        val response = historyService.listHistory(q = "alpha|bravo", regex = true)

        assertEquals(listOf(1L, 2L), response.history.map { it.gid })
        assertEquals(2, response.total)
    }

    @Test
    fun `regex literal seed extraction stays conservative`() {
        // 种子必须是「任何匹配都必然包含」的字面串：量词可缺席的尾字符要丢、
        // 组/类内容不取、顶层交替直接放弃（null）——偏长会漏配。
        assertEquals("futa", regexLiteralSeed("(?i)^futa"))
        assertEquals("tle", regexLiteralSeed("T.tle"))
        assertEquals("colo", regexLiteralSeed("colou?r"))
        assertEquals("uta", regexLiteralSeed("[Ff]uta"))
        assertEquals("c", regexLiteralSeed("(ab)*c"))
        assertEquals("hello", regexLiteralSeed("hello"))
        assertNull(regexLiteralSeed("alpha|bravo"))
        assertNull(regexLiteralSeed(".*"))
        assertNull(regexLiteralSeed(""))
    }

    @Test
    fun `invalid regex throws IllegalArgumentException`() {
        `when`(historyRepository.findAllByOrderByTimeDesc()).thenReturn(listOf(entity(1, 1000)))

        assertThrows(IllegalArgumentException::class.java) {
            historyService.listHistory(q = "(", regex = true)
        }
        // 非法正则在进 DB 之前抛出，不触发任何全表查询。
        verify(historyRepository, never()).findAllByOrderByTimeDesc()
    }

    @Test
    fun `regex takes precedence over substring when regex is true`() {
        // 种子 "tle"（来自 T.tle）做 DB 预过滤；内存 regex 精确过滤——
        // "T.tle" 作为子串不匹配任何 title，作为正则命中 "Title 1"。
        `when`(historyRepository.findLiveByTitleOrTitleJpnContaining("tle"))
            .thenReturn(listOf(entity(1, 3000, "Title 1"), entity(2, 2000, "Plain")))

        val response = historyService.listHistory(q = "T.tle", regex = true)

        assertEquals(listOf(1L), response.history.map { it.gid })
        assertEquals(1, response.total)
        verify(historyRepository, never()).findAllByOrderByTimeDesc()
    }

    @Test
    fun `regex true with blank q returns the full in-memory list`() {
        `when`(historyRepository.findAllByOrderByTimeDesc())
            .thenReturn(listOf(entity(3, 3000), entity(2, 2000), entity(1, 1000)))

        val response = historyService.listHistory(q = "  ", regex = true)

        assertEquals(listOf(3L, 2L, 1L), response.history.map { it.gid })
        assertEquals(3, response.total)
        verify(historyRepository, never()).findHistoryPaged(any(Pageable::class.java))
    }

    private fun entity(gid: Long, time: Long, title: String, titleJpn: String? = null): HistoryInfoEntity {
        val e = entity(gid, time)
        e.title = title
        e.titleJpn = titleJpn
        return e
    }
}
