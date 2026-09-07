<!--
  AdminProcessing.vue — 管理面板「图像处理」页（Wave 6）.

  复用 AdminLayout 内容区与 settings 页的偏好分组样式
  （.pref-group / .pref-card / .pref / .switch / .select），单页三段（handoff §6）：

    ① 处理设置（4 行）+ 自动化（EntryPoint 地址/Token、下载自动处理、定期补跑）
       → 持久化走 PUT /settings 的 processing 段（完整段 600ms 防抖提交、
         失败回滚 + snackbar、unmount 冲刷——与 AdminAccess 相同的本地断言模式）；
    ② 进行中任务 → 3s 纯轮询 GET /process/tasks?active=1（D11：不引 WS，
       SmbBackupView pollProgress 先例；挂载启动、unmount 清理）；
    ③ 历史记录 → GET /process/history（page/20 + 状态过滤 + 分页），
       FAILED 行重试、活跃行取消。

  字段名与枚举值对齐后端 SettingsDto.kt / ImageProcessor.kt / ProcessingController：
  defaultType ∈ UPSCALE_2X | UPSCALE_4X | DENOISE | DENOISE_UPSCALE | REMOVE_BG，
  outputFormat ∈ png | jpeg | webp，outputQuality ∈ 1..100。
  Token 遵守 D1：服务端加密存储、GET 永不回明文（只回 entrypointTokenSet）——
  本地只持有 tokenDraft，永不回填已存值；随下一次持久化附带 entrypointToken，
  留空提交 = 保留旧值。
-->
<template>
  <div class="processing">
    <div class="processing__column">
      <header class="processing__header">
        <h1 class="processing__title">图像处理</h1>
        <span v-if="processing" class="processing__status" role="status">
          {{ processing.enabled ? '已启用' : '已停用' }}
        </span>
      </header>

      <!-- ═══ 处理设置 ═════════════════════════════════════════════════ -->
      <section>
        <SectionHeader title="处理设置" />
        <PrefCard>
          <PrefRow icon="similar-primary" title="启用图像处理" summary="开启后按默认类型处理页面图片">
            <AppSwitch
              :model-value="processing.enabled"
              aria-label="启用图像处理"
              @update:model-value="toggleEnabled"
            />
          </PrefRow>
          <PrefRow icon="similar-primary" title="默认处理类型" summary="对图片应用的默认增强方式">
            <AppSelect
              :model-value="processing.defaultType"
              :options="TYPE_OPTIONS"
              @update:model-value="(v) => onSelectValue('defaultType', v)"
            />
          </PrefRow>
          <PrefRow icon="similar-primary" title="输出格式" summary="处理完成后图片的保存格式">
            <AppSelect
              :model-value="processing.outputFormat"
              :options="FORMAT_OPTIONS"
              @update:model-value="(v) => onSelectValue('outputFormat', v)"
            />
          </PrefRow>
          <PrefRow icon="similar-primary" title="输出质量" summary="有损格式的编码质量，1–100">
            <span class="processing__quality-value" aria-hidden="true">
              {{ processing.outputQuality }}
            </span>
            <input
              v-model.number="qualityDraft"
              type="range"
              min="1"
              max="100"
              step="1"
              class="processing__slider"
              aria-label="输出质量"
              @change="commitQuality"
            />
          </PrefRow>
        </PrefCard>
      </section>

      <!-- ═══ 自动化 ═══════════════════════════════════════════════════ -->
      <section>
        <SectionHeader title="自动化" />
        <PrefCard>
          <PrefRow icon="similar-primary" title="EntryPoint 服务地址" summary="图像处理后端的 Base URL">
            <AppTextField
              class="processing__field"
              :model-value="processing.entrypointUrl"
              type="url"
              placeholder="http://192.168.6.141:9800"
              aria-label="EntryPoint 服务地址"
              @update:model-value="onEntrypointUrlInput"
            />
          </PrefRow>
          <PrefRow icon="similar-primary" title="EntryPoint API Token" summary="留空保留已存 Token">
            <AppTextField
              class="processing__field"
              :model-value="tokenDraft"
              type="password"
              autocomplete="new-password"
              :placeholder="processing.entrypointTokenSet ? '已配置——留空保留' : '未配置'"
              aria-label="EntryPoint API Token"
              @update:model-value="onTokenInput"
            />
          </PrefRow>
          <PrefRow icon="similar-primary" title="下载完成后自动处理" summary="下载完成的画廊自动按默认类型处理">
            <AppSwitch
              :model-value="processing.automationEnabled"
              aria-label="下载完成后自动处理"
              @update:model-value="toggleAutomation"
            />
          </PrefRow>
          <PrefRow icon="similar-primary" title="定期补跑未处理页" summary="按周期扫描下载库，补跑缺失页">
            <AppSwitch
              :model-value="processing.periodicEnabled"
              aria-label="定期补跑未处理页"
              @update:model-value="togglePeriodic"
            />
            <template #below>
              <div class="processing__periodic">
                <span class="processing__periodic-label">补跑周期</span>
                <AppSelect
                  :model-value="processing.periodicIntervalMinutes"
                  :options="INTERVAL_OPTIONS"
                  @update:model-value="onSelectInterval"
                />
              </div>
            </template>
          </PrefRow>
        </PrefCard>
      </section>

      <!-- ═══ 进行中任务 ═══════════════════════════════════════════════ -->
      <section>
        <SectionHeader title="进行中任务" />
        <PrefCard>
          <p v-if="activeTasks.length === 0" class="processing__empty">
            暂无进行中的处理任务
          </p>
          <div v-for="task in activeTasks" :key="task.taskId" class="processing__task-row">
            <div class="processing__task-head">
              <span class="processing__task-title">{{ task.title || `画廊 #${task.galleryId}` }}</span>
              <span class="processing__badge">{{ typeLabel(task.processingType) }}</span>
              <span class="processing__badge processing__badge--muted">
                {{ triggerLabel(task.trigger) }}
              </span>
              <span class="processing__badge" :class="stateBadgeClass(task.state)">
                {{ stateLabel(task.state) }}
              </span>
              <button
                v-if="task.state === 'PENDING' || task.state === 'PROCESSING'"
                type="button"
                class="processing__action processing__action--danger"
                :disabled="cancellingId === task.taskId"
                @click="cancelTask(task)"
              >
                取消
              </button>
            </div>
            <div class="processing__task-meta">
              <span>{{ task.pagesDone }}/{{ task.pagesTotal }} 页</span>
              <span v-if="currentPageOf(task) !== null">当前第 {{ currentPageOf(task) }} 页</span>
              <span>开始 {{ formatTime(task.startedAt) }}</span>
              <span>已耗时 {{ formatElapsed(task.startedAt) }}</span>
            </div>
            <div class="processing__task-paths">
              <span class="processing__path">{{ task.sourceDir }}</span>
              <span aria-hidden="true">→</span>
              <span class="processing__path">{{ task.outputDir }}</span>
            </div>
            <p v-if="task.errorCode" class="processing__task-error" role="alert">
              {{ task.errorCode }}{{ task.errorMessage ? `：${task.errorMessage}` : '' }}
            </p>
          </div>
        </PrefCard>
      </section>

      <!-- ═══ 历史记录 ═════════════════════════════════════════════════ -->
      <section>
        <SectionHeader title="历史记录" />
        <PrefCard>
          <div class="processing__history-toolbar">
            <span class="processing__history-toolbar-label">状态</span>
            <AppSelect
              :model-value="historyStateFilter"
              :options="STATE_FILTER_OPTIONS"
              @update:model-value="onHistoryFilterChange"
            />
            <div class="processing__page-nav">
              <button
                type="button"
                class="processing__page-btn"
                aria-label="上一页"
                :disabled="historyPage <= 0"
                @click="goHistoryPage(-1)"
              >
                上一页
              </button>
              <span class="processing__page-info">第 {{ historyPage + 1 }} / {{ historyPageCount }} 页</span>
              <button
                type="button"
                class="processing__page-btn"
                aria-label="下一页"
                :disabled="historyPage >= historyPageCount - 1"
                @click="goHistoryPage(1)"
              >
                下一页
              </button>
            </div>
          </div>
          <p v-if="historyItems.length === 0" class="processing__empty">暂无历史记录</p>
          <div v-for="task in historyItems" :key="task.taskId" class="processing__task-row">
            <div class="processing__task-head">
              <span class="processing__task-id">{{ task.taskId }}</span>
              <span class="processing__task-title">{{ task.title || `画廊 #${task.galleryId}` }}</span>
              <span class="processing__badge" :class="stateBadgeClass(task.state)">
                {{ stateLabel(task.state) }}
              </span>
              <button
                v-if="task.state === 'FAILED'"
                type="button"
                class="processing__action"
                :disabled="retryingId === task.taskId"
                @click="retryTask(task)"
              >
                重试
              </button>
            </div>
            <div class="processing__task-meta">
              <span>完成 {{ formatTime(task.finishedAt) }}</span>
            </div>
            <p v-if="task.errorCode" class="processing__task-error" role="alert">
              {{ task.errorCode }}{{ task.errorMessage ? `：${task.errorMessage}` : '' }}
            </p>
          </div>
        </PrefCard>
      </section>

      <!-- ═══ 说明 ═════════════════════════════════════════════════════ -->
      <p class="processing__note">
        处理任务在服务器后台队列中执行，进度与历史在本页实时刷新（每 3 秒轮询一次）。
      </p>
    </div>

    <!-- Snackbar. -->
    <Transition name="snack">
      <div v-if="snack" class="processing__snackbar" role="status">{{ snack }}</div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { settingsApi, type ProcessingSettings } from '@/api/settings'
import {
  processingApi,
  type ProcessingTaskRecord,
  type TaskState,
} from '@/api/processing'
import {
  AppSelect,
  AppSwitch,
  AppTextField,
  PrefCard,
  PrefRow,
  SectionHeader,
} from '@/components/form'

/* ------------------------------ option lists ----------------------------- */

/** 后端 ImageProcessor.kt ProcessingType 枚举，defaultType 以字符串存储。 */
type ProcessingType = 'UPSCALE_2X' | 'UPSCALE_4X' | 'DENOISE' | 'DENOISE_UPSCALE' | 'REMOVE_BG'
type OutputFormat = 'png' | 'jpeg' | 'webp'

const TYPE_OPTIONS: Array<{ value: ProcessingType; label: string }> = [
  { value: 'UPSCALE_2X', label: '2X 放大' },
  { value: 'UPSCALE_4X', label: '4X 放大' },
  { value: 'DENOISE', label: '降噪' },
  { value: 'DENOISE_UPSCALE', label: '降噪 + 放大' },
  { value: 'REMOVE_BG', label: '抠图（去背景）' },
]

const FORMAT_OPTIONS: Array<{ value: OutputFormat; label: string }> = [
  { value: 'png', label: 'PNG' },
  { value: 'jpeg', label: 'JPEG' },
  { value: 'webp', label: 'WebP' },
]

/** 定期补跑周期（分钟），min 15（handoff §4 / D9）。 */
const INTERVAL_OPTIONS: Array<{ value: number; label: string }> = [
  { value: 15, label: '15 分钟' },
  { value: 30, label: '30 分钟' },
  { value: 60, label: '1 小时' },
  { value: 360, label: '6 小时' },
  { value: 1440, label: '24 小时' },
]

/** 历史状态过滤（'' = 不过滤）。 */
const STATE_FILTER_OPTIONS: Array<{ value: '' | TaskState; label: string }> = [
  { value: '', label: '全部' },
  { value: 'DONE', label: '已完成' },
  { value: 'FAILED', label: '失败' },
]

/** 徽标文案映射（active 列表与历史共用）。 */
const TYPE_LABELS: Record<string, string> = {
  UPSCALE_2X: '2X 放大',
  UPSCALE_4X: '4X 放大',
  DENOISE: '降噪',
  DENOISE_UPSCALE: '降噪+放大',
  REMOVE_BG: '抠图',
}

const TRIGGER_LABELS: Record<string, string> = {
  MANUAL: '手动',
  DOWNLOAD_AUTO: '下载完成',
  SCHEDULED: '定期',
}

const STATE_LABELS: Record<TaskState, string> = {
  PENDING: '排队中',
  PROCESSING: '处理中',
  DONE: '已完成',
  FAILED: '失败',
}

function typeLabel(key: string): string {
  return TYPE_LABELS[key] ?? key
}

function triggerLabel(key: string): string {
  return TRIGGER_LABELS[key] ?? key
}

function stateLabel(state: TaskState): string {
  return STATE_LABELS[state] ?? state
}

function stateBadgeClass(state: TaskState): string {
  return `processing__badge--${state.toLowerCase()}`
}

/* ------------------------- processing settings --------------------------- */

/* processing 段类型直接取自 api/settings.ts（后端 SettingsResponse.processing），
 * 不再本地重复声明——局部 ProcessingType/OutputFormat 字面量联合是其 string
 * 字段的子集，仅用于下拉选项列表。 */

const DEFAULT_PROCESSING: ProcessingSettings = {
  enabled: false,
  defaultType: 'UPSCALE_2X',
  outputFormat: 'png',
  outputQuality: 90,
  entrypointUrl: 'http://192.168.6.141:9800',
  entrypointTokenSet: false,
  automationEnabled: false,
  periodicEnabled: false,
  periodicIntervalMinutes: 60,
}

const processing = reactive<ProcessingSettings>({ ...DEFAULT_PROCESSING })

/** 滑块拖动中的草稿值（仅 change 提交后持久化）。 */
const qualityDraft = ref(DEFAULT_PROCESSING.outputQuality)

/**
 * EntryPoint Token 草稿——D1：GET 永不回明文，因此本地只持有输入值，
 * 永不回填已存 Token；随下一次持久化附带提交，成功后清空。
 */
const tokenDraft = ref('')

watch(
  () => processing.outputQuality,
  (value) => {
    qualityDraft.value = value
  },
)

/* -------------------------------- persistence ---------------------------- */

let saveTimer: number | undefined
let pendingPayload: { processing: ProcessingSettings } | null = null

/**
 * 始终提交完整的 processing 段——后端会一次性写入全部字段。
 * entrypointToken 不进 processing reactive（避免回写污染），只在
 * payload 生成时按草稿非空附带（留空 = 后端保留旧值，D1）。
 */
function persistProcessing(): void {
  const token = tokenDraft.value.trim()
  pendingPayload = {
    processing: { ...processing, ...(token ? { entrypointToken: token } : {}) },
  }
  if (saveTimer) window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => {
    saveTimer = undefined
    const payload = pendingPayload
    pendingPayload = null
    if (payload) void saveProcessing(payload)
  }, 600)
}

async function saveProcessing(payload: { processing: ProcessingSettings }): Promise<void> {
  try {
    await settingsApi.update(payload)
    if (payload.processing.entrypointToken) {
      // 乐观置位：下一次渲染占位文案切换为「已配置——留空保留」，并清空草稿。
      processing.entrypointTokenSet = true
      tokenDraft.value = ''
    }
  } catch (error) {
    console.error('[AdminProcessing] failed to persist processing settings', error)
    const rollback = { ...payload.processing }
    delete rollback.entrypointToken // 写入专用字段不回写 reactive
    Object.assign(processing, rollback)
    showSnack('无法在服务器上保存设置')
  }
}

/** 卸载前冲刷待提交的防抖保存，避免导航时丢失编辑。 */
function flushPendingSave(): void {
  if (saveTimer) window.clearTimeout(saveTimer)
  saveTimer = undefined
  const payload = pendingPayload
  pendingPayload = null
  if (payload) {
    settingsApi.update(payload).catch((error) => {
      console.error('[AdminProcessing] failed to persist processing settings on unmount', error)
    })
  }
}

onBeforeUnmount(() => {
  stopTaskPolling()
  flushPendingSave()
  if (snackTimer) window.clearTimeout(snackTimer)
})

function toggleEnabled(): void {
  processing.enabled = !processing.enabled
  persistProcessing()
}

function onEntrypointUrlInput(value: string): void {
  processing.entrypointUrl = value
  persistProcessing()
}

function onTokenInput(value: string): void {
  tokenDraft.value = value
  persistProcessing()
}

function toggleAutomation(): void {
  processing.automationEnabled = !processing.automationEnabled
  persistProcessing()
}

function togglePeriodic(): void {
  processing.periodicEnabled = !processing.periodicEnabled
  persistProcessing()
}

function onSelectValue(key: 'defaultType' | 'outputFormat', value: string | number): void {
  processing[key] = value as never
  persistProcessing()
}

function onSelectInterval(value: string | number): void {
  processing.periodicIntervalMinutes = Number(value)
  persistProcessing()
}

function commitQuality(): void {
  processing.outputQuality = Math.min(100, Math.max(1, qualityDraft.value))
  persistProcessing()
}

/* ------------------------------ active tasks ------------------------------ */

/** 活跃任务 3s 轮询（D11：纯轮询不引 WS，节奏照 SmbBackupView pollProgress）。 */
const ACTIVE_POLL_MS = 3000

const activeTasks = ref<ProcessingTaskRecord[]>([])
const cancellingId = ref<string | null>(null)
let tasksTimer: number | undefined

async function refreshActiveTasks(): Promise<void> {
  try {
    activeTasks.value = await processingApi.getActiveTasks()
  } catch (error) {
    console.error('[AdminProcessing] failed to load active tasks', error)
  }
}

function startTaskPolling(): void {
  stopTaskPolling()
  void refreshActiveTasks()
  tasksTimer = window.setInterval(() => void refreshActiveTasks(), ACTIVE_POLL_MS)
}

function stopTaskPolling(): void {
  if (tasksTimer) {
    window.clearInterval(tasksTimer)
    tasksTimer = undefined
  }
}

async function cancelTask(task: ProcessingTaskRecord): Promise<void> {
  cancellingId.value = task.taskId
  try {
    await processingApi.cancel(task.taskId)
    await refreshActiveTasks()
  } catch (error) {
    console.error('[AdminProcessing] failed to cancel task', error)
    showSnack('取消失败：服务器不可达')
  } finally {
    cancellingId.value = null
  }
}

/** PROCESSING 行展示的当前页——Record 无独立游标，pagesDone 为已完成数，
 *  进行中页 = done+1（0-based 计数转 1-based 展示），钳到 total。 */
function currentPageOf(task: ProcessingTaskRecord): number | null {
  if (task.state !== 'PROCESSING' || task.pagesTotal <= 0) return null
  return Math.min(task.pagesDone + 1, task.pagesTotal)
}

/* -------------------------------- history --------------------------------- */

const HISTORY_PAGE_SIZE = 20

const historyItems = ref<ProcessingTaskRecord[]>([])
const historyTotal = ref(0)
const historyPage = ref(0)
const historyStateFilter = ref<'' | TaskState>('')
const retryingId = ref<string | null>(null)

const historyPageCount = computed(() =>
  Math.max(1, Math.ceil(historyTotal.value / HISTORY_PAGE_SIZE)),
)

async function refreshHistory(): Promise<void> {
  try {
    const page = await processingApi.getHistory(
      historyPage.value,
      HISTORY_PAGE_SIZE,
      historyStateFilter.value || undefined,
    )
    historyItems.value = page.items
    historyTotal.value = page.total
  } catch (error) {
    console.error('[AdminProcessing] failed to load history', error)
  }
}

function onHistoryFilterChange(value: string | number): void {
  historyStateFilter.value = value as '' | TaskState
  historyPage.value = 0
  void refreshHistory()
}

function goHistoryPage(delta: number): void {
  const next = historyPage.value + delta
  if (next < 0 || next > historyPageCount.value - 1) return
  historyPage.value = next
  void refreshHistory()
}

async function retryTask(task: ProcessingTaskRecord): Promise<void> {
  retryingId.value = task.taskId
  try {
    await processingApi.retry(task.taskId)
    showSnack('已重新提交')
    await Promise.all([refreshActiveTasks(), refreshHistory()])
  } catch (error) {
    console.error('[AdminProcessing] failed to retry task', error)
    showSnack('重试失败：服务器不可达')
  } finally {
    retryingId.value = null
  }
}

/* --------------------------------- helpers -------------------------------- */

/** epoch millis → 本地时间；0/空显示「—」。 */
function formatTime(ts: number): string {
  if (!ts) return '—'
  return new Date(ts).toLocaleString('zh-CN', { hour12: false })
}

/** 已耗时 mm:ss（轮询自然刷新）；未开始显示「—」。 */
function formatElapsed(startedAt: number): string {
  if (!startedAt) return '—'
  const totalSeconds = Math.max(0, Math.floor((Date.now() - startedAt) / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

/* --------------------------------- chrome --------------------------------- */

const snack = ref('')
let snackTimer: number | undefined

function showSnack(message: string): void {
  snack.value = message
  if (snackTimer) window.clearTimeout(snackTimer)
  snackTimer = window.setTimeout(() => {
    snack.value = ''
  }, 2600)
}

/* ---------------------------------- boot ---------------------------------- */

onMounted(() => {
  startTaskPolling()
  void refreshHistory()
  void loadSettings()
})

async function loadSettings(): Promise<void> {
  try {
    const settings = await settingsApi.get()
    Object.assign(processing, DEFAULT_PROCESSING, settings.processing)
  } catch (error) {
    console.error('[AdminProcessing] failed to load settings', error)
    showSnack('无法加载服务器设置')
  }
}
</script>

<style scoped>
.processing {
  min-height: 100%;
  background: var(--color-bg);
}

.processing__column {
  max-width: 760px;
  margin: 0 auto;
  padding: 4px var(--keyline-margin) calc(56px + var(--safe-area-bottom));
}

/* ---------------------------------- header --------------------------------- */

.processing__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 4px 4px;
}

.processing__title {
  margin: 0;
  font-size: clamp(17px, 20px, 24px);
  font-weight: 600;
  letter-spacing: 0.01em;
  color: var(--text-color-primary);
}

.processing__status {
  margin-left: auto;
  padding: 4px 12px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--color-accent) 14%, transparent);
  color: var(--text-color-theme-primary);
  font-size: clamp(11px, 12px, 14px);
  font-weight: 700;
  letter-spacing: 0.04em;
}

/* ----------------------------- preference group --------------------------- */

/* ----------------------------- automation fields --------------------------- */

.processing__field {
  width: 240px;
  max-width: 100%;
}

.processing__periodic {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 2px 0 6px;
}

.processing__periodic-label {
  font-size: clamp(11px, 12px, 14px);
  color: var(--text-color-secondary);
}

/* --------------------------------- slider --------------------------------- */

.processing__quality-value {
  min-width: 28px;
  text-align: right;
  font-size: clamp(13px, 14px, 16px);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--text-color-primary);
}

.processing__slider {
  flex: 0 0 200px;
  max-width: 100%;
  accent-color: var(--color-primary);
  cursor: pointer;
}

.processing__slider:disabled {
  opacity: 0.5;
  cursor: default;
}

/* ------------------------------ task/history rows -------------------------- */

.processing__empty {
  margin: 0;
  padding: 18px var(--keyline-margin, 16px);
  font-size: clamp(12px, 13px, 15px);
  color: var(--text-color-secondary);
}

.processing__task-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px var(--keyline-margin, 16px);
}

.processing__task-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 8px;
  min-height: 28px;
}

.processing__task-title {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: clamp(13px, 14px, 16px);
  font-weight: 600;
  color: var(--text-color-primary);
}

.processing__task-id {
  flex: 0 0 auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: clamp(10px, 11px, 13px);
  color: var(--text-color-secondary);
}

.processing__badge {
  flex: 0 0 auto;
  padding: 2px 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--color-accent) 12%, transparent);
  color: var(--text-color-theme-primary);
  font-size: clamp(10px, 11px, 13px);
  font-weight: 700;
  letter-spacing: 0.02em;
}

.processing__badge--muted,
.processing__badge--pending {
  background: color-mix(in srgb, var(--text-color-secondary) 14%, transparent);
  color: var(--text-color-secondary);
}

.processing__badge--processing {
  background: color-mix(in srgb, var(--color-accent) 14%, transparent);
  color: var(--text-color-theme-primary);
}

.processing__badge--done {
  background: color-mix(in srgb, var(--color-light-green-600, #7cb342) 16%, transparent);
  color: var(--color-light-green-600, #7cb342);
}

.processing__badge--failed {
  background: color-mix(in srgb, var(--color-error, #b00020) 12%, transparent);
  color: var(--color-error, #b00020);
}

.processing__task-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: clamp(11px, 12px, 14px);
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.processing__task-paths {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: clamp(10px, 11px, 13px);
  color: var(--text-color-secondary);
}

.processing__path {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.processing__task-error {
  margin: 0;
  font-size: clamp(11px, 12px, 14px);
  color: var(--color-error, #b00020);
  word-break: break-all;
}

.processing__action {
  margin-left: auto;
  padding: 4px 12px;
  border: 1px solid var(--color-outline, var(--color-divider));
  border-radius: var(--field-radius, 4px);
  background: transparent;
  color: var(--text-color-primary);
  font-size: clamp(11px, 12px, 14px);
  font-weight: 700;
  cursor: pointer;
  transition: border-color 150ms var(--ease-decelerate-quart);
}

.processing__action:hover {
  border-color: var(--color-primary);
}

.processing__action--danger {
  border-color: color-mix(in srgb, var(--color-error, #b00020) 45%, transparent);
  color: var(--color-error, #b00020);
}

.processing__action:disabled {
  opacity: 0.38;
  cursor: default;
}

/* ------------------------------ history toolbar ---------------------------- */

.processing__history-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  padding: 10px var(--keyline-margin, 16px);
}

.processing__history-toolbar-label {
  font-size: clamp(11px, 12px, 14px);
  color: var(--text-color-secondary);
}

.processing__page-nav {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
}

.processing__page-info {
  font-size: clamp(11px, 12px, 14px);
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.processing__page-btn {
  padding: 4px 12px;
  border: 1px solid var(--color-outline, var(--color-divider));
  border-radius: var(--field-radius, 4px);
  background: transparent;
  color: var(--text-color-primary);
  font-size: clamp(11px, 12px, 14px);
  cursor: pointer;
}

.processing__page-btn:disabled {
  opacity: 0.38;
  cursor: default;
}

/* ---------------------------------- note ----------------------------------- */

.processing__note {
  margin: 18px 4px 0;
  font-size: clamp(11px, 12px, 14px);
  line-height: 1.5;
  color: var(--text-color-secondary);
}

/* --------------------------------- snackbar -------------------------------- */

.processing__snackbar {
  position: fixed;
  left: 50%;
  bottom: calc(24px + var(--safe-area-bottom));
  translate: -50% 0;
  z-index: 300;
  max-width: min(480px, calc(100vw - 32px));
  padding: 12px 20px;
  border-radius: var(--card-radius);
  background: var(--gallery-slider-background);
  color: var(--color-white);
  font-size: clamp(13px, 14px, 16px);
  box-shadow: 0 4px 12px var(--shadow-color);
}

.snack-enter-active,
.snack-leave-active {
  transition:
    opacity var(--duration-scene-opacity) var(--ease-decelerate-quart),
    translate var(--duration-scene-translate) var(--ease-decelerate-quint);
}

.snack-enter-from,
.snack-leave-to {
  opacity: 0;
  translate: -50% 12px;
}

@media (prefers-reduced-motion: reduce) {
  .snack-enter-active,
  .snack-leave-active {
    transition: none;
  }
}
</style>
