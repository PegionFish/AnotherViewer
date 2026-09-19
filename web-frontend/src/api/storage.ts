import client from './client'

/**
 * 存储池形态管理 API（存储自适配 Wave 3 / P3；后端 StorageAdminController，
 * 设计 docs/design-2026-09-20-storage-profile-autoadapt.md §一/§三）。
 *
 * 形态与证据由后端探测（mountinfo/sysfs/zpool），前端只展示 + 手动重探。
 * 部署不变量（cache/DB 必须在 SSD）违例与 UNKNOWN（网络 FS 不可验证）由
 * PathCheck.profile 区分展示。
 */

/** 存储池形态（后端 StorageProfile 枚举名）。 */
export type StorageProfile = 'SSD' | 'HDD' | 'ZFS' | 'UNKNOWN'

/** 维护任务 IO 优先级（后端 StorageTuning.IoPriority 枚举名）。 */
export type IoPriority = 'NORMAL' | 'IDLE'

/** 一次探测的完整证据（后端 StorageEvidence，管理页证据明细卡的数据源）。 */
export interface StorageEvidence {
  rootPath: string
  /** FS 类型；两路都拿不到为 null。 */
  fsType: string | null
  /** fsType 来源："file-store" | "mountinfo" | null。 */
  fsTypeSource: string | null
  /** mountinfo 最长前缀匹配到的挂载源（如 /dev/sdf1、tank/downloads）。 */
  mountSource: string | null
  /** 沿 slaves 递归展开后的底层物理盘。 */
  leafDevices: string[]
  /** 底层盘 → rotational 标志（null=不可读）。 */
  rotational: Record<string, number | null>
  /** ZFS 池名。 */
  zfsPool: string | null
  /** true=有 cache/special vdev；false=确认无；null=探测失败（保守 ZFS）。 */
  zfsCacheHint: boolean | null
  /** 非 null：探测未走通/未走全的原因（结果为保守 HDD 或保守 ZFS）。 */
  failureReason: string | null
  /** storage.profile-override 强制时记录被强制的形态。 */
  overrideApplied: StorageProfile | null
  /** override 生效时记录未强制的自然形态。 */
  naturalProfile: StorageProfile | null
}

/** 探测结果：形态 + 证据。 */
export interface StorageProfileResult {
  profile: StorageProfile
  evidence: StorageEvidence
}

/** 部署不变量单腿检查（cache 目录 / DB 文件）。 */
export interface StoragePathCheck {
  path: string
  profile: StorageProfile
  evidence: StorageEvidence
  /** 仅 SSD 满足部署不变量；UNKNOWN=不可验证（前端与真违例区分展示）。 */
  compliant: boolean
}

/** 部署不变量复查结果：cache/DB 两腿必须均可判定且为 SSD。 */
export interface StorageDeploymentCheck {
  invariantHeld: boolean
  cache: StoragePathCheck | null
  db: StoragePathCheck | null
}

/** 按形态计算的自适应 IO 参数快照（后端 StorageTuningParams）。 */
export interface StorageTuningParams {
  profile: StorageProfile
  /** 页文件读并发闸门；等于 {@link UNLIMITED_POOL_CONCURRENCY} 表示不设闸（SSD）。 */
  poolReadConcurrencyLimit: number
  pageTurnPrefetchEnabled: boolean
  scrubRateLimitMbPerSec: number
  maintenanceIoPriority: IoPriority
}

/**
 * 「不设闸」哨兵值：后端 SSD 档发 Int.MAX_VALUE（2147483647），展示层据此渲染
 * 「不设闸」而不是天文数字。
 */
export const UNLIMITED_POOL_CONCURRENCY = 2147483647

/** ZFS 建议清单的一项（只建议不代配；无「已满足」检测依据，一律按建议渲染）。 */
export interface ZfsRecommendation {
  id: string
  title: string
  description: string
}

/** GET /admin/storage/profile 响应。 */
export interface StorageProfileResponse {
  profile: StorageProfileResult
  deployment: StorageDeploymentCheck
  tuning: StorageTuningParams
  /** 仅 ZFS 非空。 */
  zfsRecommendations: ZfsRecommendation[]
}

/** POST /admin/storage/redetect 响应：重探结果 + 重算后的参数。 */
export interface StorageRedetectResponse {
  profile: StorageProfileResult
  tuning: StorageTuningParams
}

export const storageApi = {
  async getProfile(): Promise<StorageProfileResponse> {
    const { data } = await client.get<StorageProfileResponse>('/admin/storage/profile')
    return data
  },

  async redetect(): Promise<StorageRedetectResponse> {
    const { data } = await client.post<StorageRedetectResponse>('/admin/storage/redetect')
    return data
  },
}
