package com.hippo.anotherviewer.web.websocket

import com.hippo.anotherviewer.web.argThatK
import com.hippo.anotherviewer.web.dto.DownloadProgress
import com.hippo.anotherviewer.web.eq
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.messaging.simp.SimpMessagingTemplate

/**
 * P-S8 STOMP 进度节流测试：同 gid 500ms 合帧只发最新一条（尾沿帧）；跨 gid
 * 互不干扰；终态（state 3/4）立即直发并丢弃待发旧帧；发射/调度异常不打断事件
 * 消费；handler 层两 topic 同帧。
 */
class DownloadProgressThrottleTest {

    /** 同步调度收集器：不真等 500ms，手动按序驱动到期任务。 */
    private val scheduledTasks = mutableListOf<() -> Unit>()

    private lateinit var throttler: DownloadProgressThrottler

    private val emitted = mutableListOf<DownloadProgress>()

    @BeforeEach
    fun setUp() {
        scheduledTasks.clear()
        emitted.clear()
        throttler = DownloadProgressThrottler(
            intervalMs = 500L,
            schedule = { _, task -> scheduledTasks.add(task) },
        )
    }

    private fun progress(gid: Long = 42L, state: Int = 2, downloaded: Int) =
        DownloadProgress(gid = gid, state = state, downloaded = downloaded, total = 100, speed = 0, label = 0)

    @Test
    fun `high frequency events for one gid coalesce into the latest frame`() {
        repeat(10) { page -> throttler.submit(progress(downloaded = page + 1), emitted::add) }

        // 窗口内零发射（合帧）、只挂一个到期任务。
        assertTrue(emitted.isEmpty())
        assertEquals(1, scheduledTasks.size)

        // 到期：只发最新一条（done=10），旧帧全部被覆盖丢弃。
        scheduledTasks[0].invoke()
        assertEquals(listOf(10), emitted.map { it.downloaded })

        // 发射后 gid 状态清理：新事件重新挂一个到期任务并发出新最新帧。
        throttler.submit(progress(downloaded = 11), emitted::add)
        assertEquals(2, scheduledTasks.size)
        scheduledTasks[1].invoke()
        assertEquals(listOf(10, 11), emitted.map { it.downloaded })
    }

    @Test
    fun `cross gid events do not interfere`() {
        throttler.submit(progress(gid = 1, downloaded = 1), emitted::add)
        throttler.submit(progress(gid = 2, downloaded = 100), emitted::add)
        throttler.submit(progress(gid = 1, downloaded = 2), emitted::add)
        throttler.submit(progress(gid = 2, downloaded = 200), emitted::add)

        assertEquals(2, scheduledTasks.size, "每个 gid 各挂一个到期任务")

        scheduledTasks[0].invoke()
        assertEquals(listOf(1L), emitted.map { it.gid }, "gid=1 发自己的最新帧")
        assertEquals(2, emitted.single().downloaded)
        scheduledTasks[1].invoke()
        assertEquals(listOf(1L, 2L), emitted.map { it.gid }, "gid=2 的帧不被 gid=1 合并")
        assertEquals(200, emitted.last().downloaded)
    }

    @Test
    fun `terminal state is emitted immediately and drops the pending stale frame`() {
        throttler.submit(progress(downloaded = 5), emitted::add)
        assertTrue(emitted.isEmpty(), "非终态先合帧")

        throttler.submit(progress(state = 3, downloaded = 100), emitted::add)

        // 终态直发：不等窗口、不经过调度任务。
        assertEquals(listOf(100), emitted.map { it.downloaded })
        assertEquals(listOf(3), emitted.map { it.state })

        // 在途到期任务空转：待发旧帧（done=5）已被终态丢弃。
        scheduledTasks.single().invoke()
        assertEquals(1, emitted.size)
    }

    @Test
    fun `failed state is also treated as terminal`() {
        throttler.submit(progress(state = 4, downloaded = 33), emitted::add)

        assertEquals(listOf(33), emitted.map { it.downloaded })
        assertTrue(scheduledTasks.isEmpty(), "终态不挂到期任务")
    }

    @Test
    fun `emit failure does not break subsequent event consumption`() {
        var failures = 0
        val throwingEmit: (DownloadProgress) -> Unit = {
            failures++
            throw IllegalStateException("broker down")
        }

        throttler.submit(progress(downloaded = 1), throwingEmit)
        scheduledTasks[0].invoke()
        assertEquals(1, failures)

        // 发射抛错后节流器状态自愈：后续帧照常合帧、照常到期。
        val ok = mutableListOf<DownloadProgress>()
        throttler.submit(progress(downloaded = 2), ok::add)
        scheduledTasks[1].invoke()
        assertEquals(2, ok.single().downloaded)
    }

    @Test
    fun `scheduler throwing on submit does not break event consumption`() {
        var failScheduling = false
        val flakyThrottler = DownloadProgressThrottler(intervalMs = 500) { _, task ->
            if (failScheduling) throw IllegalStateException("scheduler saturated")
            scheduledTasks.add(task)
        }

        flakyThrottler.submit(progress(gid = 1, downloaded = 1), emitted::add)
        failScheduling = true
        // 调度抛错被节流器吞掉：submit 不向事件发布方外泄异常。
        flakyThrottler.submit(progress(gid = 2, downloaded = 2), emitted::add)
        failScheduling = false
        scheduledTasks.single().invoke()
        assertEquals(listOf(1), emitted.map { it.downloaded })
    }
}

/**
 * P-S8 handler 接线测试：事件经节流器合帧后，两 topic 同帧各发一次；
 * 未到期前零推送；终态事件立即推送。
 */
class DownloadProgressHandlerThrottleTest {

    private lateinit var messagingTemplate: SimpMessagingTemplate
    private lateinit var handler: DownloadProgressHandler
    private val scheduledTasks = mutableListOf<() -> Unit>()

    @BeforeEach
    fun setUp() {
        scheduledTasks.clear()
        messagingTemplate = mock(SimpMessagingTemplate::class.java)
        handler = DownloadProgressHandler(
            messagingTemplate,
            DownloadProgressThrottler(intervalMs = 500) { _, task -> scheduledTasks.add(task) },
        )
    }

    private fun progress(gid: Long = 42L, state: Int = 2, downloaded: Int) =
        DownloadProgress(gid = gid, state = state, downloaded = downloaded, total = 10, speed = 0, label = 0)

    @Test
    fun `coalesced frame goes to both topics with the same payload`() {
        handler.handleDownloadProgress(progress(downloaded = 1))
        handler.handleDownloadProgress(progress(downloaded = 2))
        handler.handleDownloadProgress(progress(downloaded = 3))
        verifyNoInteractions(messagingTemplate)

        scheduledTasks.single().invoke()

        listOf("/topic/download/42", "/topic/download/all").forEach { topic ->
            verify(messagingTemplate).convertAndSend(
                eq(topic),
                argThatK<Any> { payload ->
                    payload is DownloadProgress && payload.downloaded == 3
                },
            )
        }
        verifyNoMoreInteractions(messagingTemplate)
    }

    @Test
    fun `terminal event is pushed immediately on both topics`() {
        handler.handleDownloadProgress(progress(state = 3, downloaded = 10))

        // 终态直发：不经调度（收集器为空），两 topic 立即各一帧。
        assertTrue(scheduledTasks.isEmpty())
        listOf("/topic/download/42", "/topic/download/all").forEach { topic ->
            verify(messagingTemplate).convertAndSend(
                eq(topic),
                argThatK<Any> { payload ->
                    payload is DownloadProgress && payload.state == 3 && payload.downloaded == 10
                },
            )
        }
        verifyNoMoreInteractions(messagingTemplate)
    }
}
