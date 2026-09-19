package com.hippo.anotherviewer.web.service.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * W3-P 存储池读闸门单测（设计定稿 §二 行为矩阵的 HDD/ZFS 排序语义）。
 * 全部用真实时钟 + 小超时/短睡眠编排，不读真实文件系统。
 */
class PoolReadGateTest {

    private fun gate(limit: Int, timeoutMs: Long = 5_000L) = PoolReadGate({ limit }, timeoutMs)

    /** 轮询等待条件成立（编排并发用，不做断言主体）。 */
    private fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met within ${timeoutMs}ms")
            Thread.sleep(10)
        }
    }

    // ── MAX 零开销路径 ────────────────────────────────────────────────────────

    @Test
    fun `unlimited limit lets everyone through at once with no accounting`() {
        val g = gate(Int.MAX_VALUE)
        val parties = 16
        val barrier = CyclicBarrier(parties)
        val reached = AtomicInteger(0)
        val threads = (1..parties).map { n ->
            thread {
                g.acquire("dir", n).use {
                    reached.incrementAndGet()
                    // 全员同时在场 ⇒ 没有任何并发上限生效（若被限流这里会超时破裂）。
                    barrier.await(5, TimeUnit.SECONDS)
                }
            }
        }
        threads.forEach { it.join(10_000) }
        assertTrue(threads.none { it.isAlive }, "all threads must finish")
        assertEquals(parties, reached.get())
        assertEquals(Int.MAX_VALUE, g.freeSlots())
    }

    // ── 并发上限 ──────────────────────────────────────────────────────────────

    @Test
    fun `gate caps concurrent holders at the limit`() {
        val g = gate(3)
        val inFlight = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val done = CountDownLatch(12)
        repeat(12) { i ->
            thread {
                g.acquire("gallery", i).use {
                    val now = inFlight.incrementAndGet()
                    maxObserved.updateAndGet { prev -> maxOf(prev, now) }
                    Thread.sleep(30)
                    inFlight.decrementAndGet()
                }
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "all 12 holders must complete")
        assertEquals(0, g.queuedWaiters())
        assertTrue(maxObserved.get() in 1..3, "observed concurrency ${maxObserved.get()} must be <= 3")
    }

    // ── 同目录页序优先 ────────────────────────────────────────────────────────

    @Test
    fun `same directory waiters are granted in ascending page order`() {
        val g = gate(1)
        val order = CopyOnWriteArrayList<Int>()
        val p5 = g.acquire("d", 5)
        val bothDone = CountDownLatch(2)
        // 故意让高页号先入队：放行仍必须按页升序。
        val t3 = thread { g.acquire("d", 3).use { order.add(3); bothDone.countDown() } }
        waitUntil { g.queuedWaiters() == 1 }
        val t1 = thread { g.acquire("d", 1).use { order.add(1); bothDone.countDown() } }
        waitUntil { g.queuedWaiters() == 2 }

        p5.close()
        assertTrue(bothDone.await(5, TimeUnit.SECONDS), "both queued pages must be granted")
        assertEquals(listOf(1, 3), order.toList(), "page 1 must precede page 3 despite arrival order")

        t1.join(5_000)
        t3.join(5_000)
        assertTrue(threadsAlive(t1, t3).isEmpty())
        assertEquals(0, g.queuedWaiters())
    }

    // ── 跨目录独立 ────────────────────────────────────────────────────────────

    @Test
    fun `cross directory progress is independent of another directory's queued waiters`() {
        val g = gate(3)
        val order = CopyOnWriteArrayList<String>()
        val a9 = g.acquire("A", 9)
        val a8 = g.acquire("A", 8)
        val b7 = g.acquire("B", 7)

        val a1Done = CountDownLatch(1)
        val b2Done = CountDownLatch(1)
        val ta1 = thread { g.acquire("A", 1).use { order.add("A1"); a1Done.countDown() } }
        val tb2 = thread { g.acquire("B", 2).use { order.add("B2"); b2Done.countDown() } }
        waitUntil { g.queuedWaiters() == 2 }

        // A9 释放 → 全局最小页 A1 放行；A1 由 use 归还后，B2 获得空出的名额。
        a9.close()
        assertTrue(a1Done.await(5, TimeUnit.SECONDS))
        assertTrue(b2Done.await(5, TimeUnit.SECONDS))
        assertEquals(
            listOf("A1", "B2"), order.toList(),
            "A1 first (smallest page); B2 must proceed without waiting on any A-ordering"
        )

        a8.close()
        b7.close()
        ta1.join(5_000)
        tb2.join(5_000)
        assertTrue(threadsAlive(ta1, tb2).isEmpty())
        assertEquals(0, g.queuedWaiters())
    }

    // ── 防饿死：超时强制放行 ──────────────────────────────────────────────────

    @Test
    fun `waiter is force granted after the timeout even though the limit stays exhausted`() {
        val g = gate(1, timeoutMs = 120)
        val holder = g.acquire("A", 9) // 占住唯一席位，先不放
        val entered = AtomicInteger(0)
        val waiter = thread { g.acquire("A", 1).use { entered.incrementAndGet() } }

        waitUntil { g.queuedWaiters() == 1 }
        Thread.sleep(400) // > 120ms 超时

        assertEquals(1, entered.get(), "queued waiter must be force granted past the timeout")
        assertEquals(0, g.queuedWaiters(), "forced waiter must have left the queue")

        holder.close()
        waiter.join(5_000)
        assertTrue(!waiter.isAlive)

        // 账目自愈：强制票不占名额，归还不污染后续发放。
        val next = CountDownLatch(1)
        val followUp = thread { g.acquire("A", 2).use { next.countDown() } }
        assertTrue(next.await(2, TimeUnit.SECONDS), "fresh acquire must pass immediately after drain")
        followUp.join(5_000)
        assertEquals(1, g.freeSlots())
    }

    @Test
    fun `interrupted waiter is force granted instead of erroring`() {
        val g = gate(1, timeoutMs = 30_000)
        val holder = g.acquire("A", 9)
        val entered = AtomicInteger(0)
        val waiter = thread { g.acquire("A", 1).use { entered.incrementAndGet() } }
        waitUntil { g.queuedWaiters() == 1 }

        waiter.interrupt()
        waiter.join(2_000)
        assertTrue(!waiter.isAlive, "interrupted acquire must still complete (force granted), not hang")
        assertEquals(1, entered.get(), "interrupted acquire must still produce a permit (force granted)")
        assertEquals(0, g.queuedWaiters())
        holder.close()
    }

    // ── profile 变更：惰性重建 ────────────────────────────────────────────────

    @Test
    fun `gate rebuilds when the tuned limit changes`() {
        val limit = AtomicInteger(1)
        val g = PoolReadGate({ limit.get() }, PoolReadGate.DEFAULT_FORCE_GRANT_TIMEOUT_MS)
        val completed = AtomicInteger(0)

        val holder = g.acquire("A", 0) // 旧核 limit 1 → 占满
        val oldCoreWaiter = thread { g.acquire("A", 1).use { completed.incrementAndGet() } }
        waitUntil { g.queuedWaiters() == 1 }

        limit.set(Int.MAX_VALUE) // 探测翻转为 SSD → 新请求立即放行，不等旧核
        g.acquire("A", 2).use { completed.incrementAndGet() }
        assertEquals(1, completed.get(), "post-rebuild acquire must pass through immediately")

        holder.close() // 旧核排空：等待者获得旧核放行，不悬挂
        oldCoreWaiter.join(5_000)
        assertTrue(!oldCoreWaiter.isAlive, "waiters stranded on the old core must still complete")
        assertEquals(2, completed.get())
    }

    // ── 幂等 close ────────────────────────────────────────────────────────────

    @Test
    fun `closing a permit twice keeps the accounting intact`() {
        val g = gate(1)
        val p = g.acquire("A", 0)
        assertEquals(0, g.freeSlots())
        p.close()
        p.close()
        assertEquals(1, g.freeSlots(), "double close must not double-release the slot")

        val q = g.acquire("A", 1)
        assertEquals(0, g.freeSlots())
        q.close()
        assertEquals(1, g.freeSlots())
    }

    @Test
    fun `forced permit close does not return a slot that was never taken`() {
        val g = gate(1, timeoutMs = 100)
        val holder = g.acquire("A", 9)
        val waiter = thread { g.acquire("A", 1).use { /* forced */ } }
        waitUntil { g.queuedWaiters() == 1 }
        Thread.sleep(250) // 等待者超时强制放行并 close
        waiter.join(5_000)
        assertEquals(0, g.freeSlots(), "forced ticket close must not inflate the accounting")
        holder.close()
        assertEquals(1, g.freeSlots())
    }

    private fun threadsAlive(vararg threads: Thread): List<Thread> = threads.filter { it.isAlive }
}
