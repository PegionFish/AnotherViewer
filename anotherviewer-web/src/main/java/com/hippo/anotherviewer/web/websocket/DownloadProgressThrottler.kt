package com.hippo.anotherviewer.web.websocket

import com.hippo.anotherviewer.web.dto.DownloadProgress
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * P-S8 下载进度 STOMP 推送节流器：同一 gid 500ms 合帧只发最新一条（UI 高频
 * 页进度不再每页双 topic 全量推送；JobService 的 500ms 节流先例的合帧强化版
 * ——尾沿合帧：窗口内只保留最新事件，到期发的是**最新**而非首个）。
 *
 * - 非 [DownloadProgress] 终态帧（state 3=FINISHED / 4=FAILED，口径同
 *   DownloadService 状态机）：内存留最新帧，每 gid 只挂一个到期发射任务；
 * - 终态帧：立即直发（不节流），并丢弃该 gid 待发的旧帧（终态即最新状态）；
 * - 帧「发射」由调用方注入的 [emit] 完成（handler 里两 topic 同帧各发一次），
 *   异常只记日志——**绝不打断事件消费**（调度线程存活，后续帧照常节流）；
 * - 发射后清理 gid 状态，无键泄漏。
 *
 * 可测性：[schedule] 注入（单测用同步收集器手动驱动到期任务，不真等 500ms）；
 * 生产默认单守护线程调度器。
 */
@Component
class DownloadProgressThrottler(
    /** 合帧窗口（毫秒）。 */
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    /** 延时调度注入缝：在 delayMs 后执行 task。 */
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit = { delayMs, task ->
        defaultScheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    },
) {

    private val logger = LoggerFactory.getLogger(DownloadProgressThrottler::class.java)

    /** gid → 窗口内最新的待发帧。 */
    private val pending = ConcurrentHashMap<Long, DownloadProgress>()

    /** 已挂到期发射任务的 gid（add 的原子性防重复挂任务）。 */
    private val scheduled = ConcurrentHashMap.newKeySet<Long>()

    /**
     * 提交一帧：终态直发；非终态留最新帧、该 gid 无在途发射任务时挂一个
     * [intervalMs] 后的发射。异常兜底（emit/调度抛错不影响调用方事件消费）。
     */
    fun submit(progress: DownloadProgress, emit: (DownloadProgress) -> Unit) {
        try {
            if (progress.state == STATE_FINISHED || progress.state == STATE_FAILED) {
                // 终态直发：丢弃待发旧帧（终态帧信息量覆盖一切中间帧）。若此刻
                // 有在途到期任务，到期时 pending 已空 → no-op，状态自清理。
                pending.remove(progress.gid)
                emitSafely(progress, emit)
                return
            }
            pending[progress.gid] = progress
            if (scheduled.add(progress.gid)) {
                schedule(intervalMs) { fire(progress.gid, emit) }
            }
        } catch (e: Exception) {
            logger.warn("Download progress throttle submit failed for gid={}: {}", progress.gid, e.message)
        }
    }

    /** 到期发射：清调度标记 → 取最新帧发出（与事件到达的竞态最坏提前几毫秒发射，无害）。 */
    private fun fire(gid: Long, emit: (DownloadProgress) -> Unit) {
        scheduled.remove(gid)
        val frame = pending.remove(gid) ?: return
        emitSafely(frame, emit)
    }

    private fun emitSafely(frame: DownloadProgress, emit: (DownloadProgress) -> Unit) {
        try {
            emit(frame)
        } catch (e: Exception) {
            // 发射异常（如 STOMP broker 断连）只记日志：不打断事件消费与后续节流。
            logger.warn("Download progress emit failed for gid={}: {}", frame.gid, e.message)
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 500L

        /** DownloadProgress.state 的终态值（DownloadService 状态机：3=FINISHED, 4=FAILED）。 */
        const val STATE_FINISHED = 3
        const val STATE_FAILED = 4

        private val defaultScheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "download-progress-throttle").apply { isDaemon = true }
            }
    }
}
