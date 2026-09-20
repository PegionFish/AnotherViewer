package com.hippo.anotherviewer.web.service

import com.hippo.anotherviewer.web.any
import com.hippo.anotherviewer.web.entity.DownloadInfoEntity
import com.hippo.anotherviewer.web.repository.DownloadInfoRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * P-S8 进度批量落库器单测：同周期多页只留最新、一次合并落库一条 SQL 对；
 * 终态/取消/完成化立即 flush；@PreDestroy（destroy）flush；守卫语义与旧
 * persistProgress 一致（墓碑/暂停/失败行不写）；异常兜底不杀 tick。
 */
class DownloadProgressPersisterTest {

    private lateinit var repository: DownloadInfoRepository

    /** 可注入时钟：lastModified 断言用。 */
    private val clockMs = AtomicLong(1_000L)

    /** 已 shutdown 的调度器：直构单测禁用后台 tick，全靠显式 flush/tick 驱动。 */
    private val noScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor().apply {
        shutdownNow()
    }

    private lateinit var persister: DownloadProgressPersister

    @BeforeEach
    fun setUp() {
        repository = mock(DownloadInfoRepository::class.java)
        persister = DownloadProgressPersister(
            repository,
            clock = { clockMs.get() },
            intervalMs = 1_000L,
            scheduler = noScheduler,
        )
    }

    private fun row(id: Long, state: Int = 2, deleted: Boolean = false): DownloadInfoEntity =
        DownloadInfoEntity().apply {
            this.id = id
            gid = 42L
            token = "tok"
            this.state = state
            this.deleted = deleted
            total = 100
            done = 0
        }

    private fun stubLive(row: DownloadInfoEntity) {
        `when`(repository.findById(row.id)).thenReturn(Optional.of(row))
        `when`(repository.save(row)).thenAnswer { it.getArgument(0) }
    }

    @Test
    fun `ten pages recorded within one interval coalesce into a single save`() {
        val row = row(1L)
        stubLive(row)

        // 连续 10 页进度（1s 窗口内）：内存覆盖，无任何 SQL。
        repeat(10) { page -> persister.record(1L, page + 1) }
        verify(repository, never()).findById(anyLong())
        verify(repository, never()).save(row)

        // 一个合并周期：每任务恰好一次 findById+save，落的是最新 done=10。
        persister.tick()

        verify(repository, times(1)).findById(1L)
        verify(repository, times(1)).save(row)
        assertEquals(10, row.done)
        assertEquals(clockMs.get(), row.lastModified, "lastModified 取 flush 时刻")
    }

    @Test
    fun `next tick with no new records is a no-op`() {
        val row = row(1L)
        stubLive(row)
        persister.record(1L, 5)
        persister.tick()

        persister.tick()

        // 已合并过的任务不再重复落库（每周期只写有新进度的任务）。
        verify(repository, times(1)).save(row)
    }

    @Test
    fun `terminal flush persists immediately without waiting for the interval`() {
        val row = row(1L)
        stubLive(row)
        persister.record(1L, 7)

        persister.flush(1L)

        verify(repository, times(1)).save(row)
        assertEquals(7, row.done)
        // flush 后待写清空：再 tick 无第二次 save。
        persister.tick()
        verify(repository, times(1)).save(row)
    }

    @Test
    fun `destroy flushes all pending progress (PreDestroy semantics)`() {
        val a = row(1L)
        val b = row(2L)
        stubLive(a)
        stubLive(b)
        persister.record(1L, 3)
        persister.record(2L, 4)

        persister.destroy()

        verify(repository, times(1)).save(a)
        verify(repository, times(1)).save(b)
        assertEquals(3, a.done)
        assertEquals(4, b.done)
        // destroy 后再 flush 幂等（pending 已清空）。
        persister.flush(1L)
        verify(repository, times(1)).save(a)
    }

    @Test
    fun `guards match the legacy persistProgress semantics`() {
        // 墓碑行、暂停(0)、失败(4) 行：进度写一律跳过（不得复活状态/删除）。
        val tombstone = row(1L, deleted = true)
        val paused = row(2L, state = 0)
        val failed = row(3L, state = 4)
        listOf(tombstone, paused, failed).forEach { stubLive(it) }

        persister.record(1L, 9)
        persister.record(2L, 9)
        persister.record(3L, 9)
        persister.flushAll()

        verify(repository, never()).save(tombstone)
        verify(repository, never()).save(paused)
        verify(repository, never()).save(failed)
        // 待写已消费（守卫拒绝≠留 pending 反复重试）：再 tick/flush 无第二次查询。
        persister.tick()
        persister.flushAll()
        verify(repository, times(1)).findById(1L)
        verify(repository, times(1)).findById(2L)
        verify(repository, times(1)).findById(3L)
    }

    @Test
    fun `unknown task id flushes nothing`() {
        `when`(repository.findById(404L)).thenReturn(Optional.empty())

        persister.record(404L, 5)
        persister.flush(404L)

        verify(repository, times(1)).findById(404L)
        verify(repository, never()).save(any(DownloadInfoEntity::class.java))
    }

    @Test
    fun `repository failure is swallowed and does not poison later flushes`() {
        val row = row(1L)
        stubLive(row)
        `when`(repository.save(row)).thenThrow(IllegalStateException("disk full"))
        persister.record(1L, 5)
        persister.tick()
        assertEquals(5, row.done, "save 抛错前已内存赋值，但异常不得外泄")

        // 异常兜底后任务照常可用：修好仓库，后续 flush 恢复落库。
        // （上一条 stub 会抛错，必须用 doAnswer 旁路 when() 的方法调用。）
        org.mockito.Mockito.doAnswer { it.getArgument(0) }.`when`(repository).save(row)
        persister.record(1L, 6)
        persister.flush(1L)
        assertEquals(6, row.done)
    }

    @Test
    fun `discard drops pending progress without touching the database`() {
        val row = row(1L)
        stubLive(row)
        persister.record(1L, 8)

        persister.discard(1L)
        persister.tick()

        verify(repository, never()).findById(1L)
        verify(repository, never()).save(row)
    }

    @Test
    fun `flushAll drains every pending task exactly once`() {
        val rows = (1L..5L).map { row(it).also(::stubLive) }
        rows.forEachIndexed { index, r -> persister.record(r.id, index + 1) }

        persister.flushAll()

        rows.forEach { verify(repository, times(1)).save(it) }
        assertTrue(rows.all { it.done > 0 })
    }

    @Test
    fun `scheduling uses the injected interval (afterPropertiesSet wires the timer)`() {
        val captured = mutableListOf<Pair<Long, Runnable?>>()
        val scheduler = mock(ScheduledExecutorService::class.java)
        `when`(
            scheduler.scheduleWithFixedDelay(
                any(Runnable::class.java),
                anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit::class.java),
            )
        ).thenAnswer { inv ->
            captured.add(inv.getArgument<Long>(1) to inv.getArgument<Runnable>(0))
            null
        }
        val p = DownloadProgressPersister(repository, { clockMs.get() }, 1_234L, scheduler)
        p.afterPropertiesSet()

        assertEquals(1, captured.size)
        assertEquals(1_234L, captured[0].first)
        // 周期任务即 tick：驱动一次可合并落库。
        val row = row(9L)
        stubLive(row)
        p.record(9L, 2)
        captured[0].second!!.run()
        verify(repository, times(1)).save(row)
    }
}
