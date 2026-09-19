<!--
  AdminIntegrity.vue — 管理面板 · 下载完整性（文件完整性 Wave 3, F1）.

  数据源（api/integrity.ts，契约 contracts/openapi.yaml「Integrity」段）:
    - GET  /integrity/report   上次巡检概要（lastRun）+ 坏页/分歧清单（同参分页）
    - POST /integrity/refresh/{gid}/{page}  单页强制重取（healed/failed 都 ride 200）
    - POST /integrity/reverify/{gid}        REVERIFY Job 提交/中断（对齐统一 Job 模式，
                                            轮询 jobsApi.getJob，终态统计挂 Job.result）
    - POST /integrity/scrub                 手动触发巡检（202 受理 / 409 已在跑）
    - POST /integrity/backfill              单画廊页数回填（dry-run 先行 → 确认应用，U1）

  页号口径全部 1-based（服务端契约出口）。分歧清单为只读快照（证据展示，
  不提供动作）；处置走 refresh / reverify。
-->
<template>
  <div class="admin-integrity">
    <!-- ═══ 加载态 ═════════════════════════════════════════════════════ -->
    <div v-if="loadState === 'loading'" class="integrity-state" role="status">
      正在加载完整性报告…
    </div>

    <!-- ═══ 错误态 ═════════════════════════════════════════════════════ -->
    <div v-else-if="loadState === 'error'" class="integrity-state" role="alert">
      <p class="integrity-state__text">无法加载完整性报告，请稍后重试</p>
      <button type="button" class="btn-primary" @click="loadReport">重试</button>
    </div>

    <!-- ═══ 正常态 ═════════════════════════════════════════════════════ -->
    <main v-else class="admin-integrity__body">
      <div class="admin-integrity__column">
        <!-- 上次巡检概要 -->
        <section>
          <SectionHeader title="上次巡检" />
          <PrefCard>
            <template v-if="report?.lastRun">
              <PrefRow icon="history-black" title="巡检时间" :summary="runRange(report.lastRun)" />
              <PrefRow
                icon="check-all-dark"
                title="巡检结果"
                :summary="
                  `共 ${report.lastRun.totalPages} 页 · 坏页 ${report.lastRun.badPages}` +
                  ` · 分歧 ${report.lastRun.divergences} · 单侧证据 ${report.lastRun.oneSided}`
                "
              />
            </template>
            <PrefRow v-else icon="info-dark" title="尚未运行过巡检" summary="点击「立即巡检」开始第一次全库扫描" />
            <PrefRow icon="play-dark" title="立即巡检" summary="后台扫描全部下载页文件并刷新报告">
              <button
                type="button"
                class="btn-primary"
                :disabled="scrubBusy"
                :aria-busy="scrubBusy"
                @click="triggerScrub"
              >
                {{ scrubBusy ? '提交中…' : '立即巡检' }}
              </button>
            </PrefRow>
          </PrefCard>
        </section>

        <!-- 坏页清单（分页 + 单条/批量刷新） -->
        <section>
          <SectionHeader title="坏页清单" />
          <PrefCard>
            <div v-if="badPages.length === 0" class="integrity-empty">没有坏页记录。</div>
            <template v-else>
              <div class="integrity-toolbar">
                <span class="integrity-toolbar__total">共 {{ report?.badPageTotal ?? 0 }} 条</span>
                <button
                  type="button"
                  class="btn-text"
                  :disabled="batchBusy"
                  @click="refreshAllBadPages"
                >
                  {{ batchBusy ? `刷新中 ${batchDone}/${batchTotal}…` : '全部刷新' }}
                </button>
              </div>
              <ul class="integrity-list">
                <li v-for="item in badPages" :key="`${item.gid}-${item.page}`" class="integrity-list__item">
                  <span class="integrity-list__main">
                    <span class="integrity-list__title">#{{ item.gid }} · 第 {{ item.page }} 页</span>
                    <span class="integrity-list__meta">
                      {{ verdictLabel(item.verdict) }}<template v-if="item.hashShort"> · {{ item.hashShort }}</template>
                    </span>
                  </span>
                  <button
                    type="button"
                    class="btn-text"
                    :disabled="refreshBusyKey === rowKey(item) || batchBusy"
                    @click="refreshPage(item)"
                  >
                    {{ refreshBusyKey === rowKey(item) ? '修复中…' : '刷新此页' }}
                  </button>
                </li>
              </ul>
              <div class="integrity-pager">
                <button type="button" class="btn-text" :disabled="page <= 0 || listBusy" @click="goPage(page - 1)">
                  上一页
                </button>
                <span class="integrity-pager__label">第 {{ page + 1 }} / {{ maxBadPage }} 页</span>
                <button
                  type="button"
                  class="btn-text"
                  :disabled="page + 1 >= maxBadPage || listBusy"
                  @click="goPage(page + 1)"
                >
                  下一页
                </button>
              </div>
            </template>
          </PrefCard>
        </section>

        <!-- 分歧清单（只读） -->
        <section>
          <SectionHeader title="分歧清单" />
          <PrefCard>
            <div v-if="divergences.length === 0" class="integrity-empty">没有基线与对端哈希分歧。</div>
            <template v-else>
              <div class="integrity-toolbar">
                <span class="integrity-toolbar__total">共 {{ report?.divergenceTotal ?? 0 }} 条 · 只读证据</span>
              </div>
              <ul class="integrity-list">
                <li v-for="item in divergences" :key="`${item.gid}-${item.page}`" class="integrity-list__item">
                  <span class="integrity-list__main">
                    <span class="integrity-list__title">#{{ item.gid }} · 第 {{ item.page }} 页</span>
                    <span class="integrity-list__meta">
                      基线 {{ shortHash(item.localHash) }} / 对端 {{ shortHash(item.peerHash) }}
                    </span>
                  </span>
                </li>
              </ul>
            </template>
          </PrefCard>
        </section>

        <!-- 整本复验（REVERIFY Job） -->
        <section>
          <SectionHeader title="整本复验" />
          <PrefCard>
            <div class="integrity-form">
              <input
                v-model="rvGid"
                class="gid-input"
                type="number"
                min="1"
                placeholder="画廊 gid"
                aria-label="复验画廊 gid"
                :disabled="rvRunning"
              />
              <button
                type="button"
                class="btn-primary"
                :disabled="rvRunning"
                :aria-busy="rvRunning"
                @click="startReverify"
              >
                {{ rvRunning ? '复验中…' : '开始复验' }}
              </button>
              <button
                v-if="rvRunning"
                type="button"
                class="btn-text"
                @click="interruptReverify"
              >
                中断
              </button>
            </div>
            <p v-if="rvRunning && rvJob" class="integrity-progress" role="status">
              {{ rvJob.stage || '复验中' }} · {{ rvJob.processed }}/{{ rvJob.total }} 页（{{ rvJob.percent }}%）
            </p>
            <div v-if="rvStats" class="integrity-stats" data-testid="reverify-stats">
              <span>共 {{ rvStats.total }} 页</span>
              <span>完好 {{ rvStats.ok }}</span>
              <span>损坏 {{ rvStats.bad }}</span>
              <span v-if="rvStats.refreshed != null">修复 {{ rvStats.refreshed }}</span>
              <span v-if="rvStats.interrupted">（已中断）</span>
            </div>
          </PrefCard>
        </section>

        <!-- 单画廊页数回填（U1：dry-run 先行 → 确认应用） -->
        <section>
          <SectionHeader title="页数回填" />
          <PrefCard>
            <div class="integrity-form">
              <input
                v-model="bfGid"
                class="gid-input"
                type="number"
                min="1"
                placeholder="画廊 gid"
                aria-label="回填画廊 gid"
                :disabled="bfBusy"
              />
              <button
                type="button"
                class="btn-primary"
                :disabled="bfBusy"
                :aria-busy="bfBusy"
                @click="runBackfill(bfDryRun)"
              >
                {{ bfBusy ? '执行中…' : bfDryRun ? '预演回填（dry-run）' : '按磁盘回填页数' }}
              </button>
            </div>
            <PrefRow icon="check-all-dark" title="先 dry-run" summary="只统计将要修改的行，不写入；确认后再应用">
              <AppSwitch
                :model-value="bfDryRun"
                aria-label="回填 dry-run"
                :disabled="bfBusy"
                @update:model-value="(v: boolean) => (bfDryRun = v)"
              />
            </PrefRow>
            <div v-if="bfStats" class="integrity-stats" data-testid="backfill-stats">
              <span>检查 {{ bfStats.rowsExamined }} 行</span>
              <span>校正页数 {{ bfStats.rowsPagesUpdated }} 行</span>
              <span>完成化 {{ bfStats.rowsCompleted }} 行</span>
              <span>{{ bfStats.dryRun ? '（dry-run，未写入）' : '（已写入）' }}</span>
            </div>
            <div v-if="bfStats && bfStats.dryRun" class="integrity-form">
              <button type="button" class="btn-primary" :disabled="bfBusy" @click="runBackfill(false)">
                确认应用
              </button>
              <span class="integrity-form__hint">按上面 dry-run 统计真实写入</span>
            </div>
          </PrefCard>
        </section>
      </div>
    </main>

    <!-- Snackbar. -->
    <Transition name="snack">
      <div v-if="snack" class="snackbar" role="status">{{ snack }}</div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { errorCodeOf } from '@/api/client'
import {
  integrityApi,
  type IntegrityBadPage,
  type IntegrityBackfillPageCountsStats,
  type IntegrityReport,
  type ReverifyStats,
} from '@/api/integrity'
import { jobsApi, type Job } from '@/api/jobs'
import AppSwitch from '@/components/form/AppSwitch.vue'
import { PrefCard, PrefRow, SectionHeader } from '@/components/form'

/* --------------------------------- 报告加载 -------------------------------- */

const PAGE_SIZE = 20
/** 批量刷新的取数上限（服务端 pageSize clamp 1..200）。 */
const BATCH_PAGE_SIZE = 200

type LoadState = 'loading' | 'ready' | 'error'

const loadState = ref<LoadState>('loading')
const report = ref<IntegrityReport | null>(null)
const page = ref(0)
const listBusy = ref(false)

const badPages = computed(() => report.value?.badPages ?? [])
const divergences = computed(() => report.value?.divergences ?? [])
const maxBadPage = computed(() => Math.max(1, Math.ceil((report.value?.badPageTotal ?? 0) / PAGE_SIZE)))

async function loadReport(): Promise<void> {
  loadState.value = 'loading'
  try {
    report.value = await integrityApi.getReport(page.value, PAGE_SIZE)
    loadState.value = 'ready'
  } catch (error) {
    console.error('[AdminIntegrity] failed to load report', error)
    loadState.value = 'error'
  }
}

async function goPage(next: number): Promise<void> {
  if (listBusy.value) return
  listBusy.value = true
  const prev = page.value
  page.value = next
  try {
    report.value = await integrityApi.getReport(page.value, PAGE_SIZE)
  } catch (error) {
    console.error('[AdminIntegrity] failed to change page', error)
    page.value = prev
    showSnack('翻页失败，请稍后重试')
  } finally {
    listBusy.value = false
  }
}

/* ------------------------------- 手动巡检 --------------------------------- */

const scrubBusy = ref(false)

async function triggerScrub(): Promise<void> {
  scrubBusy.value = true
  try {
    await integrityApi.scrub()
    showSnack('巡检已受理，后台执行中')
  } catch (error) {
    console.error('[AdminIntegrity] scrub trigger failed', error)
    // 409 CONFLICT = 已有巡检在跑；其余走兜底。
    showSnack(errorCodeOf(error) === 'CONFLICT' ? '已有巡检在进行中' : '巡检提交失败，请稍后重试')
  } finally {
    scrubBusy.value = false
  }
}

/* ------------------------------ 坏页刷新 ---------------------------------- */

function rowKey(item: IntegrityBadPage): string {
  return `${item.gid}:${item.page}`
}

const VERDICT_LABELS: Record<string, string> = {
  MISSING: '文件缺失',
  MISMATCH: '哈希不符',
  READ_ERROR: '读取失败',
}

function verdictLabel(verdict: string): string {
  return VERDICT_LABELS[verdict] ?? verdict
}

function shortHash(hash: string | undefined): string {
  return typeof hash === 'string' && hash.length > 12 ? hash.slice(0, 12) : hash || '—'
}

const refreshBusyKey = ref<string | null>(null)

/** 单条刷新：healed/failed 都 ride 200，按 status 反馈；成功后重载当前页。 */
async function refreshPage(item: IntegrityBadPage): Promise<void> {
  if (refreshBusyKey.value) return
  refreshBusyKey.value = rowKey(item)
  try {
    const result = await integrityApi.refreshPage(item.gid, item.page)
    if (result.status === 'healed') {
      showSnack(`#${item.gid} 第 ${item.page} 页已修复`)
    } else {
      showSnack(`#${item.gid} 第 ${item.page} 页修复失败：${result.message || '源不可得'}`)
    }
    report.value = await integrityApi.getReport(page.value, PAGE_SIZE)
  } catch (error) {
    console.error('[AdminIntegrity] page refresh failed', error)
    showSnack(`#${item.gid} 第 ${item.page} 页修复请求失败`)
  } finally {
    refreshBusyKey.value = null
  }
}

const batchBusy = ref(false)
const batchDone = ref(0)
const batchTotal = ref(0)

/** 批量刷新：逐条调 refresh（服务端一次一页，无批量端点），汇总结果。 */
async function refreshAllBadPages(): Promise<void> {
  if (batchBusy.value) return
  batchBusy.value = true
  batchDone.value = 0
  let healed = 0
  let failed = 0
  try {
    // 全量坏页用大页取数（上限 200），不跟随当前分页。
    const full = await integrityApi.getReport(0, BATCH_PAGE_SIZE)
    batchTotal.value = full.badPages.length
    for (const item of full.badPages) {
      try {
        const result = await integrityApi.refreshPage(item.gid, item.page)
        if (result.status === 'healed') healed += 1
        else failed += 1
      } catch (error) {
        console.error(`[AdminIntegrity] batch refresh failed for #${item.gid} p${item.page}`, error)
        failed += 1
      }
      batchDone.value += 1
    }
    showSnack(`批量刷新完成：修复 ${healed} 页，失败 ${failed} 页`)
    report.value = await integrityApi.getReport(page.value, PAGE_SIZE)
  } catch (error) {
    console.error('[AdminIntegrity] batch refresh failed to list bad pages', error)
    showSnack('批量刷新失败，请稍后重试')
  } finally {
    batchBusy.value = false
    batchDone.value = 0
    batchTotal.value = 0
  }
}

/* ------------------------------ 整本复验 ---------------------------------- */

const rvGid = ref('')
const rvJob = ref<Job | null>(null)
const rvStats = ref<ReverifyStats | null>(null)
/** 轮询中（提交成功到终态之间）：驱动进度展示与中断按钮。 */
const rvRunning = ref(false)
/** 当前轮询中的提交 gid（中断调用复用；中断本身与 gid 无关——任务按 type 单实例）。 */
let rvActiveGid: number | null = null
let rvPollTimer: number | undefined
let rvDisposed = false

const POLL_INTERVAL_MS = 1000

function parseGid(raw: string): number | null {
  const gid = Number(raw)
  return Number.isInteger(gid) && gid > 0 ? gid : null
}

function isReverifyStats(value: unknown): value is ReverifyStats {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return typeof v.total === 'number' && typeof v.ok === 'number' && typeof v.bad === 'number'
}

function stopPolling(): void {
  if (rvPollTimer !== undefined) {
    window.clearTimeout(rvPollTimer)
    rvPollTimer = undefined
  }
  rvRunning.value = false
}

/**
 * 轮询 /jobs/{jobId} 至终态；终态统计挂 Job.result（REVERIFY → ReverifyStats）。
 * 单次拉取失败不终止轮询（瞬时错误下帧重试）。
 */
function pollReverifyJob(jobId: string): Promise<void> {
  return new Promise((resolve) => {
    const tick = async (): Promise<void> => {
      if (rvDisposed) {
        resolve()
        return
      }
      let job: Job | null = null
      try {
        job = await jobsApi.getJob(jobId)
        rvJob.value = job
      } catch (error) {
        console.error('[AdminIntegrity] job poll failed', error)
      }
      if (job && (job.state === 'COMPLETED' || job.state === 'FAILED')) {
        stopPolling()
        if (job.state === 'COMPLETED' && isReverifyStats(job.result)) {
          rvStats.value = job.result
        } else if (job.state === 'FAILED') {
          showSnack(job.error || '复验任务失败')
        }
        resolve()
        return
      }
      rvPollTimer = window.setTimeout(() => void tick(), POLL_INTERVAL_MS)
    }
    void tick()
  })
}

async function startReverify(): Promise<void> {
  if (rvRunning.value) return
  const gid = parseGid(rvGid.value)
  if (gid === null) {
    showSnack('请输入有效的画廊 gid')
    return
  }
  rvStats.value = null
  rvJob.value = null
  rvActiveGid = gid
  try {
    const { jobId } = await integrityApi.reverify(gid)
    rvRunning.value = true
    await pollReverifyJob(jobId)
  } catch (error) {
    console.error('[AdminIntegrity] reverify submit failed', error)
    const code = errorCodeOf(error)
    showSnack(
      code === 'INTEGRITY_NOT_FOUND'
        ? '该 gid 没有本地下载记录'
        : code === 'CONFLICT'
          ? '已有后台任务在进行中，请稍后再试'
          : '复验提交失败，请稍后重试',
    )
  }
}

async function interruptReverify(): Promise<void> {
  if (rvActiveGid === null) return
  try {
    // 202 = 中断旗标已交付，跑完当前页后以部分统计 COMPLETED（轮询继续）。
    await integrityApi.reverify(rvActiveGid, { interrupt: true })
    showSnack('中断请求已发出，等待当前页完成后收场')
  } catch (error) {
    console.error('[AdminIntegrity] reverify interrupt failed', error)
    showSnack(errorCodeOf(error) === 'NO_ACTIVE_JOB' ? '没有进行中的复验任务' : '中断请求失败')
  }
}

/* ---------------------------- 单画廊页数回填（U1） -------------------------- */

const bfGid = ref('')
const bfDryRun = ref(true)
const bfBusy = ref(false)
const bfStats = ref<IntegrityBackfillPageCountsStats | null>(null)

/**
 * 两步流程（两步都调 /backfill kind=pageCounts）：
 *   1. dry-run（默认开）→ 只统计将要校正/完成化的行；
 *   2. 「确认应用」→ dryRun=false 真实写入。
 */
async function runBackfill(dryRun: boolean): Promise<void> {
  if (bfBusy.value) return
  const gid = parseGid(bfGid.value)
  if (gid === null) {
    showSnack('请输入有效的画廊 gid')
    return
  }
  bfBusy.value = true
  try {
    const stats = await integrityApi.backfill({ kind: 'pageCounts', gid, dryRun })
    if ('rowsExamined' in stats) {
      bfStats.value = stats
      showSnack(
        stats.dryRun
          ? `dry-run 完成：将校正 ${stats.rowsPagesUpdated} 行、完成化 ${stats.rowsCompleted} 行`
          : `回填完成：校正 ${stats.rowsPagesUpdated} 行、完成化 ${stats.rowsCompleted} 行`,
      )
    }
  } catch (error) {
    console.error('[AdminIntegrity] backfill failed', error)
    const code = errorCodeOf(error)
    showSnack(
      code === 'INTEGRITY_NOT_FOUND'
        ? '该 gid 没有本地下载记录'
        : '回填执行失败，请稍后重试',
    )
  } finally {
    bfBusy.value = false
  }
}

/* --------------------------------- chrome --------------------------------- */

function runRange(run: NonNullable<IntegrityReport['lastRun']>): string {
  const start = new Date(run.startedAt).toLocaleString()
  return run.finishedAt === null ? `${start} · 进行中` : `${start} ~ ${new Date(run.finishedAt).toLocaleString()}`
}

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
  rvDisposed = false
  void loadReport()
})

onBeforeUnmount(() => {
  rvDisposed = true
  stopPolling()
  if (snackTimer) window.clearTimeout(snackTimer)
})
</script>

<style scoped>
/* Scene shell — content column lives inside SettingsLayout. */
.admin-integrity {
  height: 100%;
  background: var(--color-bg);
}

/* ----------------------------- loading / error ---------------------------- */

.integrity-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  height: 100%;
  padding: 24px;
  color: var(--text-color-secondary);
  font-size: clamp(13px, 14px, 16px);
}

.integrity-state__text {
  margin: 0;
}

/* ---------------------------------- body ---------------------------------- */

.admin-integrity__body {
  height: 100%;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.admin-integrity__column {
  max-width: 760px;
  margin: 0 auto;
  padding: 4px var(--keyline-margin) calc(56px + var(--safe-area-bottom));
}

/* --------------------------------- toolbar -------------------------------- */

.integrity-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px var(--keyline-margin) 0;
}

.integrity-toolbar__total {
  font-size: clamp(11px, 12px, 14px);
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
}

/* ---------------------------------- lists --------------------------------- */

.integrity-empty {
  padding: 14px var(--keyline-margin);
  font-size: clamp(13px, 14px, 16px);
  color: var(--text-color-secondary);
}

.integrity-list {
  margin: 0;
  padding: 0 4px;
  list-style: none;
}

.integrity-list__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 52px;
  padding: 6px var(--keyline-margin);
}

.integrity-list__item + .integrity-list__item {
  border-top: 1px solid var(--color-divider);
}

.integrity-list__main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.integrity-list__title {
  font-size: clamp(13px, 14px, 16px);
  font-weight: 600;
  color: var(--text-color-primary);
  font-variant-numeric: tabular-nums;
}

.integrity-list__meta {
  font-size: clamp(11px, 12px, 14px);
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------------------------------- pager --------------------------------- */

.integrity-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 8px 0 12px;
}

.integrity-pager__label {
  font-size: clamp(12px, 13px, 15px);
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
}

/* ----------------------------- forms / progress ---------------------------- */

.integrity-form {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 12px var(--keyline-margin) 4px;
}

.integrity-form__hint {
  font-size: clamp(11px, 12px, 14px);
  color: var(--text-color-secondary);
}

.gid-input {
  flex: 1 1 140px;
  max-width: 220px;
  padding: 8px 10px;
  border: 1px solid var(--color-divider);
  border-radius: var(--card-radius);
  background: transparent;
  font-size: clamp(13px, 14px, 16px);
  font-variant-numeric: tabular-nums;
  color: var(--text-color-primary);
  outline: none;
  transition: border-color 150ms var(--ease-decelerate-quart);
}

.gid-input:focus {
  border-color: var(--color-primary);
}

.gid-input:disabled {
  opacity: 0.5;
}

.integrity-progress {
  margin: 0;
  padding: 4px var(--keyline-margin) 0;
  font-size: clamp(12px, 13px, 15px);
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.integrity-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  margin: 8px var(--keyline-margin) 4px;
  padding: 10px 14px;
  border: 1px solid var(--color-divider);
  border-radius: var(--card-radius);
  font-size: clamp(12px, 13px, 15px);
  color: var(--text-color-primary);
  font-variant-numeric: tabular-nums;
}

/* --------------------------------- buttons --------------------------------- */

.btn-primary {
  padding: 9px 22px;
  border: none;
  border-radius: var(--card-radius);
  background: var(--color-primary);
  color: var(--color-white);
  font-size: clamp(13px, 14px, 16px);
  font-weight: 700;
  letter-spacing: 0.02em;
  cursor: pointer;
  box-shadow: 0 1px 3px var(--shadow-color);
  transition:
    background-color 150ms var(--ease-decelerate-quart),
    transform 120ms var(--ease-decelerate-quart);
}

.btn-primary:hover:not(:disabled) {
  background: var(--color-primary-dark);
}

.btn-primary:active:not(:disabled) {
  transform: scale(0.97);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: default;
}

.btn-text {
  padding: 9px 14px;
  border: none;
  border-radius: var(--card-radius);
  background: transparent;
  color: var(--text-color-theme-primary);
  font-size: clamp(13px, 14px, 16px);
  font-weight: 700;
  cursor: pointer;
  transition: background-color 150ms var(--ease-decelerate-quart);
}

.btn-text:hover:not(:disabled) {
  background: var(--color-surface);
}

.btn-text:disabled {
  opacity: 0.5;
  cursor: default;
}

/* --------------------------------- snackbar -------------------------------- */

.snackbar {
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
