import client from './client'

/**
 * 图像处理任务监控 API（settings/server/processing 三区块的数据面）。
 * 契约：contracts/openapi.yaml Processing tag + docs/dev-plan-2026-09-08-image-processing-execution-handoff.md §4。
 */

/** 任务状态——镜像后端 TaskState（取消=FAILED + errorCode，无独立 CANCELLED）。 */
export type TaskState = 'PENDING' | 'PROCESSING' | 'DONE' | 'FAILED'

/** 触发来源。 */
export type ProcessingTrigger = 'MANUAL' | 'DOWNLOAD_AUTO' | 'SCHEDULED'

/** 任务历史记录（DB 镜像；活跃任务为内存瞬时值，同形状）。 */
export interface ProcessingTaskRecord {
  taskId: string
  galleryId: number
  title: string
  trigger: ProcessingTrigger
  processingType: string
  state: TaskState
  pagesTotal: number
  pagesDone: number
  pagesFailed: number
  /** 输入页所在目录（绝对路径，展示用）。 */
  sourceDir: string
  /** enhanced 产物目录（绝对路径，展示用）。 */
  outputDir: string
  /** EntryPoint 侧 task_id（调试用）。 */
  epTaskIds: string[]
  /** 机读码（EP_UNREACHABLE/EP_TIMEOUT/... 或 EntryPoint 原码）；空=无错。 */
  errorCode: string
  errorMessage: string
  /** epoch millis。 */
  createdAt: number
  startedAt: number
  finishedAt: number
  updatedAt: number
}

export interface ProcessingHistoryPage {
  items: ProcessingTaskRecord[]
  total: number
  page: number
  size: number
}

/** POST /process/retry/{taskId} 的响应（与既有 ProcessingTaskResponse 同形）。 */
export interface ProcessingRetryResponse {
  taskId: string
  galleryId: number
  totalPages: number
  state: TaskState
}

export const processingApi = {
  /** 活跃任务（QUEUED/RUNNING 瞬时值）。 */
  async getActiveTasks(): Promise<ProcessingTaskRecord[]> {
    const { data } = await client.get<{ tasks: ProcessingTaskRecord[] }>('/process/tasks', {
      params: { active: 1 },
    })
    return data.tasks ?? []
  },

  /** 分页历史（新→旧）；state 可选过滤。 */
  async getHistory(page = 0, size = 50, state?: TaskState): Promise<ProcessingHistoryPage> {
    const { data } = await client.get<ProcessingHistoryPage>('/process/history', {
      params: { page, size, ...(state ? { state } : {}) },
    })
    return data
  },

  /** 重试失败/取消任务：只重跑原任务失败页，返回新任务。 */
  async retry(taskId: string): Promise<ProcessingRetryResponse> {
    const { data } = await client.post<ProcessingRetryResponse>(`/process/retry/${taskId}`)
    return data
  },

  /** 取消活跃任务（本地取消；EntryPoint 侧尽力而为）。 */
  async cancel(taskId: string): Promise<void> {
    await client.post(`/process/cancel/${taskId}`)
  },
}
