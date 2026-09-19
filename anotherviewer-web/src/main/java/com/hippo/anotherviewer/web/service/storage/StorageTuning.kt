package com.hippo.anotherviewer.web.service.storage

import org.springframework.stereotype.Service

/**
 * 按 [StorageProfile] 计算的自适应 IO 参数快照（行为矩阵见设计文档 §二）。
 * Wave 3 消费方：页服务读闸门 / 翻页预读 / 完整性巡检限速（integrity 计划 S2.1 参数化）/ 维护 IO 优先级。
 */
data class StorageTuningParams(
    /** 依据的形态（含 override 情况），诊断/展示用。 */
    val profile: StorageProfile,

    /**
     * 存储池读并发闸门：同时放行的页文件读请求数上限（消费方按 (目录, 页号) 排序放行）。
     * SSD=Int.MAX_VALUE（不设闸）；HDD=3；ZFS=8（无 cache/special hint，保守）/ 16（有）。
     */
    val poolReadConcurrencyLimit: Int,

    /**
     * 翻页预读 N+1（页服务后台预读下一页）：SSD=false（顺序读本来快，预读白耗 IO）；
     * HDD=true（把寻道摊平）；ZFS=true。
     */
    val pageTurnPrefetchEnabled: Boolean,

    /** 完整性巡检限速 MB/s：SSD=400；HDD=60；ZFS=120。 */
    val scrubRateLimitMbPerSec: Int,

    /** 维护任务 IO 优先级：SSD=NORMAL；HDD/ZFS=IDLE（不与前台读抢道）。 */
    val maintenanceIoPriority: StorageTuning.IoPriority,
)

/**
 * 自适应 IO 参数 bean（Wave 1 只按 profile 计算并暴露，消费接线在 Wave 3）。
 *
 * 依据 [StorageProfileService.current()] 的最近探测结果计算；尚未探测时按 UNKNOWN 保守参数。
 * `StorageProfileService.detect()/redetect()` 之后需调用 [refresh] 重算。
 */
@Service
class StorageTuning(private val profileService: StorageProfileService) {

    @Volatile private var params: StorageTuningParams = compute()

    /** 当前生效参数。 */
    fun current(): StorageTuningParams = params

    val profile: StorageProfile get() = params.profile
    val poolReadConcurrencyLimit: Int get() = params.poolReadConcurrencyLimit
    val pageTurnPrefetchEnabled: Boolean get() = params.pageTurnPrefetchEnabled
    val scrubRateLimitMbPerSec: Int get() = params.scrubRateLimitMbPerSec
    val maintenanceIoPriority: StorageTuning.IoPriority get() = params.maintenanceIoPriority

    /** 依据 profileService 的当前探测结果重算参数（redetect 后调用）。 */
    fun refresh(): StorageTuningParams {
        params = compute()
        return params
    }

    private fun compute(): StorageTuningParams {
        val current = profileService.current()
        return forProfile(current?.profile ?: StorageProfile.UNKNOWN, current?.evidence?.zfsCacheHint)
    }

    enum class IoPriority {
        /** SSD：维护任务与前台同优先级。 */
        NORMAL,

        /** HDD/ZFS：维护任务让路（纯 Java 节流退避实现，见设计文档 §二）。 */
        IDLE,
    }

    companion object {
        /** 行为矩阵（设计文档 §二）。UNKNOWN 形态不明 → 与 HDD 同参数（保守：HDD 行为放 SSD 上无害，反向有害）。 */
        fun forProfile(profile: StorageProfile, zfsCacheHint: Boolean?): StorageTuningParams = when (profile) {
            StorageProfile.SSD -> StorageTuningParams(
                profile, poolReadConcurrencyLimit = Int.MAX_VALUE,
                pageTurnPrefetchEnabled = false, scrubRateLimitMbPerSec = 400,
                maintenanceIoPriority = IoPriority.NORMAL,
            )
            StorageProfile.HDD -> StorageTuningParams(
                profile, poolReadConcurrencyLimit = 3,
                pageTurnPrefetchEnabled = true, scrubRateLimitMbPerSec = 60,
                maintenanceIoPriority = IoPriority.IDLE,
            )
            StorageProfile.ZFS ->
                if (zfsCacheHint == true) StorageTuningParams(
                    profile, poolReadConcurrencyLimit = 16,
                    pageTurnPrefetchEnabled = true, scrubRateLimitMbPerSec = 120,
                    maintenanceIoPriority = IoPriority.IDLE,
                )
                else StorageTuningParams(
                    profile, poolReadConcurrencyLimit = 8,
                    pageTurnPrefetchEnabled = true, scrubRateLimitMbPerSec = 120,
                    maintenanceIoPriority = IoPriority.IDLE,
                )
            StorageProfile.UNKNOWN -> StorageTuningParams(
                profile, poolReadConcurrencyLimit = 3,
                pageTurnPrefetchEnabled = true, scrubRateLimitMbPerSec = 60,
                maintenanceIoPriority = IoPriority.IDLE,
            )
        }
    }
}
