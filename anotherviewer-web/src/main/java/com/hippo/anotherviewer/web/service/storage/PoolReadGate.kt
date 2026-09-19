package com.hippo.anotherviewer.web.service.storage

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 池读闸门票据（AutoCloseable，配合 `use { }` 使用）。
 *
 * 两种实现：
 * - [UnlimitedPoolPermit]：不设闸（SSD）时的零开销直通票，close 无操作；
 * - [CountedPoolPermit]：占票票（counted=true，close 归还名额）或超时强制放行的
 *   超额票（counted=false，close 不归还名额——强制票从未占用过账面额度）。
 */
sealed interface PoolReadPermit : AutoCloseable

internal object UnlimitedPoolPermit : PoolReadPermit {
    override fun close() {}
}

internal class CountedPoolPermit(
    private val gate: PoolReadGate,
    private val core: PoolReadGate.Core,
    /** true=占用一个并发名额（close 归还）；false=排队超时强制放行的超额票（close 不归还）。 */
    private val counted: Boolean,
) : PoolReadPermit {
    private val released = AtomicBoolean(false)

    /** 幂等：重复 close 只归还一次。 */
    override fun close() {
        if (released.compareAndSet(false, true)) {
            gate.release(core, counted)
        }
    }
}

/**
 * 存储池读并发闸门（设计定稿 §二 行为矩阵，Wave 3 P 接线）。
 *
 * 行为：
 * - limit = [Int.MAX_VALUE]（SSD）：`acquire` 直接放行零开销票据，不进任何锁/队列；
 * - limit = 3（HDD，UNKNOWN 同）/ 8 / 16（ZFS）：并发名额 + **同目录页序放行**——
 *   同一 [acquire] directory 内按 page 升序优先放行（减少盘面寻道抖动）；
 *   跨目录相互独立，放行时取全局 (page, 先到序) 最小的等待者；
 * - **防饿死**：排队超过 [forceGrantTimeoutMs]（默认 5s）强制放行——出队并签发
 *   超额票（不占账面名额，close 不归还），响应线程绝不会无限排队；
 * - **profile 变更**：每次 acquire 惰性比对 limitProvider()，值变化即整体重建
 *   内部状态核（不监听、不回调）；旧核上的等待者由 5s 超时兜底强制放行，
 *   旧票 close 归还到旧核（随旧核一起自然排空），无需迁移。
 *
 * 线程模型：名额与队列由每核一把监视器锁保护；临界区只有入队/出队/记账，
 * 持票期间（真正的池文件读）不持有任何锁。
 */
@Component
open class PoolReadGate(
    /** 当前生效的池读并发上限（生产取 StorageTuning.current().poolReadConcurrencyLimit）。 */
    private val limitProvider: () -> Int,
    /** 排队等待超时 ms：超时强制放行（防饿死）。 */
    val forceGrantTimeoutMs: Long,
) {

    /** Spring 装配入口：上限与开关全部跟随 [StorageTuning] 当前快照（惰性读取，不监听）。 */
    @Autowired
    constructor(storageTuning: StorageTuning) : this(
        { storageTuning.current().poolReadConcurrencyLimit },
        DEFAULT_FORCE_GRANT_TIMEOUT_MS,
    )

    /** 一份并发额度下的全部闸门状态；limit 变更时整体重建。 */
    internal class Core(val limit: Int) {
        /** 同页并列时的先到序。 */
        val seq = AtomicLong(0)

        /** 名额/队列的监视器锁。 */
        val lock = Object()

        /** 空闲名额（lock 保护）。 */
        var available: Int = limit

        /** 目录 → 等待者最小堆（lock 保护）。 */
        val queues = HashMap<String, PriorityQueue<Waiter>>()
    }

    /** (page, seq) 排序的等待者。 */
    internal class Waiter(val page: Int, val seq: Long) : Comparable<Waiter> {
        @Volatile var granted = false

        override fun compareTo(other: Waiter): Int {
            val byPage = page.compareTo(other.page)
            return if (byPage != 0) byPage else seq.compareTo(other.seq)
        }
    }

    @Volatile private var core = Core(normalizeLimit(limitProvider()))

    /**
     * 取一张读票。limit=[Int.MAX_VALUE] 时零开销直通；否则受名额与同目录页序
     * 约束，排队超过 [forceGrantTimeoutMs] 强制放行（超额票）。中断同样以
     * 强制放行收场（恢复中断标记），绝不向上抛——调用方是页 serve 路径。
     */
    open fun acquire(directory: String, page: Int): PoolReadPermit {
        val core = coreForCurrentLimit()
        if (core.limit == Int.MAX_VALUE) return UnlimitedPoolPermit

        val deadlineNanos = System.nanoTime() + forceGrantTimeoutMs * NANOS_PER_MS
        synchronized(core.lock) {
            val mySeq = core.seq.getAndIncrement()
            // 快路径：有空闲名额且本目录没有页号更小（或同页先到）的等待者。
            // 队头即本目录最小等待者，一次 peek 即可判定。
            val head = core.queues[directory]?.peek()
            if (core.available > 0 && (head == null || head.page > page)) {
                core.available--
                return CountedPoolPermit(this, core, counted = true)
            }
            val waiter = Waiter(page, mySeq)
            core.queues.getOrPut(directory) { PriorityQueue() }.add(waiter)
            while (!waiter.granted) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) {
                    // 防饿死强制放行：离开队列 + 超额票（不占账面名额）。
                    removeFromQueue(core, directory, waiter)
                    return CountedPoolPermit(this, core, counted = false)
                }
                try {
                    core.lock.wait(remainingNanos / NANOS_PER_MS, (remainingNanos % NANOS_PER_MS).toInt())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    removeFromQueue(core, directory, waiter)
                    return CountedPoolPermit(this, core, counted = false)
                }
            }
            // 被 release() 放行：名额已由 release 代扣，此处照常持票。
            return CountedPoolPermit(this, core, counted = true)
        }
    }

    /** 归还票据（通常经 [CountedPoolPermit.close]）。counted=false 的超额票不归还名额。 */
    internal fun release(core: Core, counted: Boolean) {
        synchronized(core.lock) {
            if (counted) {
                core.available++
            }
            grantWaiters(core)
            core.lock.notifyAll()
        }
    }

    /** 放行全局 (page, seq) 最小的等待者直至名额用尽——同目录内即页升序；跨目录独立（只看页序，无目录间耦合）。 */
    private fun grantWaiters(core: Core) {
        while (core.available > 0) {
            var minDir: String? = null
            var min: Waiter? = null
            for ((dir, queue) in core.queues) {
                val head = queue.peek() ?: continue
                val currentMin = min
                if (currentMin == null || head < currentMin) {
                    min = head
                    minDir = dir
                }
            }
            val dir = minDir ?: return
            val waiter = min!!
            removeFromQueue(core, dir, waiter)
            waiter.granted = true
            core.available--
        }
    }

    private fun removeFromQueue(core: Core, directory: String, waiter: Waiter) {
        val queue = core.queues[directory] ?: return
        queue.remove(waiter)
        if (queue.isEmpty()) core.queues.remove(directory)
    }

    /** 惰性重建：limitProvider 值变化即换核；旧核的票与等待者自然排空（5s 超时兜底）。 */
    private fun coreForCurrentLimit(): Core {
        val current = core
        val limit = normalizeLimit(limitProvider())
        if (current.limit == limit) return current
        synchronized(this) {
            if (core.limit == limit) return core
            core = Core(limit)
            return core
        }
    }

    private fun normalizeLimit(limit: Int): Int = if (limit <= 0) 1 else limit

    // ── 诊断 / 测试观测面 ─────────────────────────────────────────────────────

    /** 当前排队等待的请求数（所有目录合计）。 */
    internal fun queuedWaiters(): Int {
        val c = core
        return synchronized(c.lock) { c.queues.values.sumOf { it.size } }
    }

    /** 当前空闲名额（可能短暂被强制票「超额」——强制票不占账面）。 */
    internal fun freeSlots(): Int {
        val c = core
        return synchronized(c.lock) { c.available }
    }

    companion object {
        /** 排队强制放行的默认超时（设计定稿：防饿死，响应线程等待上限）。 */
        const val DEFAULT_FORCE_GRANT_TIMEOUT_MS = 5_000L

        private const val NANOS_PER_MS = 1_000_000L
    }
}
