package com.hippo.anotherviewer.web.websocket

import com.hippo.anotherviewer.web.dto.DownloadProgress
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * DownloadProgress → STOMP 推送（P-S8 节流改造）：事件经
 * [DownloadProgressThrottler] 每 gid 500ms 合帧只发最新一条，两 topic 同帧；
 * 终态帧（state 3/4）立即直发不节流。注意：这里是推送面的节流——
 * ApplicationEvent 流本身不节流（ProcessingAutomation 等其他监听方不受影响）。
 */
@Component
class DownloadProgressHandler(
    private val messagingTemplate: SimpMessagingTemplate,
    private val throttler: DownloadProgressThrottler,
) {

    private val logger = LoggerFactory.getLogger(DownloadProgressHandler::class.java)

    @EventListener
    fun handleDownloadProgress(progress: DownloadProgress) {
        throttler.submit(progress, ::send)
    }

    /** 两 topic 同帧发射（由节流器在合帧窗口边界调用）。 */
    private fun send(progress: DownloadProgress) {
        try {
            messagingTemplate.convertAndSend("/topic/download/${progress.gid}", progress)
            messagingTemplate.convertAndSend("/topic/download/all", progress)
        } catch (e: Exception) {
            // STOMP 推送失败只记日志：绝不打断事件消费（与节流器异常兜底叠加）。
            logger.warn("Download progress STOMP send failed for gid={}: {}", progress.gid, e.message)
        }
    }
}
