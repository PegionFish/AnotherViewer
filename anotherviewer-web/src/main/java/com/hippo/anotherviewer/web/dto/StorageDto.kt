package com.hippo.anotherviewer.web.dto

import com.hippo.anotherviewer.web.service.storage.DeploymentCheck
import com.hippo.anotherviewer.web.service.storage.StorageProfileResult
import com.hippo.anotherviewer.web.service.storage.StorageTuningParams

/**
 * 存储池形态管理端点响应体（存储自适配 Wave 3 / P3；设计
 * docs/design-2026-09-20-storage-profile-autoadapt.md §三/§四）。
 *
 * 载荷直接复用 P1 的 service 层数据类（[StorageProfileResult] /
 * [DeploymentCheck] / [StorageTuningParams]）——它们本就为「INFO 日志 + 管理页
 * 展示 + 测试断言共用」设计，全部由可 JSON 序列化的标量构成（枚举按名字序列化）；
 * 本文件只补端点外壳与 ZFS 建议条目，不再造一层逐字段镜像 DTO（两处字段漂移
 * 比这一层耦合更糟）。
 */

/**
 * ZFS 建议清单的一项（设计 §三：应用无权改数据集属性——只建议不代配）。
 * 无「已满足」的后端检测依据，前端一律按「建议」黄牌样式渲染。
 *
 * @param id 稳定标识（前端 key / e2e 定位用）
 * @param title 建议标题（含建议的属性与取值）
 * @param description 依据与收益说明
 */
data class ZfsRecommendation(
    val id: String,
    val title: String,
    val description: String,
)

/**
 * GET /api/v1/admin/storage/profile 响应：当前探测结果（含证据）+ 部署不变量
 * 复查 + 自适应 IO 参数 + ZFS 建议清单。
 */
data class StorageProfileResponse(
    /** 下载根目录的最近探测结果（服务未探测过时端点现场探测一次再返回）。 */
    val profile: StorageProfileResult,
    /** cache 目录 / DB 文件所在卷的部署不变量复查（两者必须 SSD）。 */
    val deployment: DeploymentCheck,
    /** 当前生效的自适应 IO 参数快照。 */
    val tuning: StorageTuningParams,
    /** 仅 ZFS profile 非空；其余形态恒为空清单。 */
    val zfsRecommendations: List<ZfsRecommendation> = emptyList(),
)

/** POST /api/v1/admin/storage/redetect 响应：重探结果 + 重算后的参数快照。 */
data class StorageRedetectResponse(
    val profile: StorageProfileResult,
    val tuning: StorageTuningParams,
)
