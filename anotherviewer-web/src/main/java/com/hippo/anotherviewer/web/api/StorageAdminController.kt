package com.hippo.anotherviewer.web.api

import com.hippo.anotherviewer.web.dto.StorageProfileResponse
import com.hippo.anotherviewer.web.dto.StorageRedetectResponse
import com.hippo.anotherviewer.web.dto.ZfsRecommendation
import com.hippo.anotherviewer.web.service.storage.StorageProfile
import com.hippo.anotherviewer.web.service.storage.StorageProfileService
import com.hippo.anotherviewer.web.service.storage.StorageTuning
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 存储池形态管理 API（存储自适配 Wave 3 / P3；设计
 * docs/design-2026-09-20-storage-profile-autoadapt.md §三/§四）。
 *
 * 鉴权与其余 /api 端点一致——Bearer token，由 SecurityConfig 的全局规则
 * （/api 前缀通配 authenticated）覆盖，本控制器零额外配置。
 *
 * 端点一览：
 * - GET /profile：当前探测结果（含证据）+ 部署不变量复查 + 自适应 IO 参数 +
 *   ZFS 建议清单（仅 ZFS profile 返回；应用无权改数据集属性——只建议不代配，
 *   管理页按黄牌建议渲染）。
 * - POST /redetect：对上次下载根重探（P1 redetect）+ StorageTuning.refresh()
 *   重算参数（P1 契约：redetect 后必须 refresh），返回完整结果与参数。
 *
 * 两端点均无业务错误路径（重探/查询永不失败——探测失败本身就是保守 HDD/保守
 * ZFS 的正常结果，凭 evidence.failureReason 表达），故不涉及 IntegrityController
 * 先例的 errorEnvelope；全局兜底归 GlobalExceptionHandler。
 *
 * 探测时机由接线方驱动（P1）：启动、download.path 配置变更、本控制器手动重探。
 * 在启动接线缺位（服务从未探测过）时两端点回退对配置的下载根现场探测一次，
 * 保证管理页永不空转。
 */
@RestController
@RequestMapping("/api/v1/admin/storage")
class StorageAdminController(
    private val profileService: StorageProfileService,
    private val storageTuning: StorageTuning,
    /** 下载根目录：服务从未探测过时的现场探测回退（正常部署即 P1 的探测对象）。 */
    @Value("\${anotherviewer.download.path:}") private val downloadPath: String = "",
) {

    private val logger = LoggerFactory.getLogger(StorageAdminController::class.java)

    /** 当前形态 + 证据 + 部署不变量复查 + 自适应参数 + ZFS 建议清单（仅 ZFS）。 */
    @GetMapping("/profile")
    fun profile(): ResponseEntity<StorageProfileResponse> {
        val cached = profileService.current()
        // P1 契约：detect 之后须调 StorageTuning.refresh() 重算参数——回退探测路径
        // 也一样，否则 GET 返回的参数快照仍是构造时的 UNKNOWN 保守值。
        val tuning = if (cached == null) {
            profileService.detect(downloadPath)
            storageTuning.refresh()
        } else {
            storageTuning.current()
        }
        val current = cached ?: profileService.current()!!
        return ResponseEntity.ok(
            StorageProfileResponse(
                profile = current,
                deployment = profileService.deploymentCheck(),
                tuning = tuning,
                zfsRecommendations = if (current.profile == StorageProfile.ZFS) ZFS_RECOMMENDATIONS else emptyList(),
            ),
        )
    }

    /** 重探上次下载根并重算自适应参数（refresh 是 P1 契约的必跟动作）。 */
    @PostMapping("/redetect")
    fun redetect(): ResponseEntity<StorageRedetectResponse> {
        val result = profileService.redetect() ?: run {
            logger.info("storage redetect: no prior detection — probing configured download path")
            profileService.detect(downloadPath)
        }
        val tuning = storageTuning.refresh()
        logger.info(
            "storage redetect: profile={} tuning(concurrency={},scrub={}MB/s,prefetch={},priority={})",
            result.profile, tuning.poolReadConcurrencyLimit, tuning.scrubRateLimitMbPerSec,
            tuning.pageTurnPrefetchEnabled, tuning.maintenanceIoPriority,
        )
        return ResponseEntity.ok(StorageRedetectResponse(result, tuning))
    }

    companion object {
        /**
         * ZFS 建议清单（设计 §三——DB/Cache 已由部署不变量保证在 SSD，清单只涉及
         * 库数据集；内容为静态结构，是否满足无后端检测依据）。
         */
        val ZFS_RECOMMENDATIONS = listOf(
            ZfsRecommendation(
                id = "recordsize",
                title = "downloads 数据集 recordsize=512K–1M",
                description = "页文件均值约 380KB（实测 112KB webp 常见）：默认 128K 会把大页切成跨块碎片、" +
                    "放大写放大，过大又放大随机读——512K–1M 区间最贴合页文件尺寸。",
            ),
            ZfsRecommendation(
                id = "special-vdev",
                title = "元数据 special vdev（NVMe）",
                description = "把目录/dentry 等元数据挪到 NVMe special vdev，中和海量目录的 stat 风暴——" +
                    "对随机元数据读收益最大的一项。",
            ),
            ZfsRecommendation(
                id = "compression",
                title = "downloads 数据集 compression=off",
                description = "webp/jpg 本身已压缩，ZFS 二次压缩只白耗 CPU 不省空间。",
            ),
            ZfsRecommendation(
                id = "arc-max",
                title = "zfs_arc_max 显式限制（建议 6–8GB）",
                description = "ARC 与 JVM 同机争 RAM——无论 DB 在哪这条都成立；不显式限制时 ARC 可吞掉" +
                    "大部分内存，建议按机器规模设 6–8GB 上限。",
            ),
            ZfsRecommendation(
                id = "l2arc",
                title = "L2ARC（NVMe，可选）",
                description = "可选加速层：重启后读热集保温，避免 ARC 冷启动期全量回源机械盘。",
            ),
            ZfsRecommendation(
                id = "fs-tweaks",
                title = "atime=off · xattr=sa · ashift=12",
                description = "三项通用小项：关闭访问时间戳写、扩展属性内联 dnode、建池时扇区对齐 4K。",
            ),
        )
    }
}
