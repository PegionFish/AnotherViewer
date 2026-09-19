<!--
  AdminStorage.vue — 管理面板 · 存储管理（存储自适配 Wave 3 / P3）。

  数据源 GET /admin/storage/profile（storageApi.getProfile）：
    - 当前存储池形态卡：profile 徽章 + 一次探测的完整证据（fs 类型/挂载源/底层盘/
      rotational/ZFS 池与加速卡提示/失败原因/override）。
    - 自适应 IO 参数表（后端按形态计算，此处只读展示）。
    - 部署不变量状态：cache 目录与 DB 文件所在卷必须 SSD——正常绿 / 真违例黄牌 /
      UNKNOWN（网络 FS 不可验证）灰牌，凭 PathCheck.profile 区分。
    - ZFS 建议清单：仅 ZFS profile 返回；应用无权改数据集属性，一律按「建议」
      黄牌样式渲染（有无「已满足」无检测依据，不代配）。

  「重新探测」按钮 → POST /admin/storage/redetect（后端 redetect + tuning refresh），
  成功后重拉 GET /profile 刷新整页。
-->
<template>
  <div class="admin-storage">
    <header class="admin-storage__toolbar">
      <h1 class="admin-storage__title">存储管理</h1>
      <button
        type="button"
        class="admin-storage__redetect"
        :disabled="redetecting"
        aria-label="重新探测存储池形态"
        @click="onRedetect"
      >
        <AppIcon name="refresh-dark" size="18px" />
        {{ redetecting ? '探测中…' : '重新探测' }}
      </button>
    </header>

    <main class="admin-storage__body">
      <div class="admin-storage__column">
        <p v-if="loading" class="admin-storage__state">正在加载存储信息…</p>
        <p v-else-if="!data" class="admin-storage__state">无法加载存储信息，请稍后重试。</p>

        <template v-else>
          <!-- ═══ 当前存储池形态 ════════════════════════════════════════════ -->
          <section>
            <SectionHeader title="当前存储池形态" />
            <PrefCard>
              <div class="profile-head">
                <span class="profile-badge" :class="`profile-badge--${data.profile.profile}`">
                  {{ profileLabel(data.profile.profile) }}
                </span>
                <span class="profile-head__root">{{ data.profile.evidence.rootPath }}</span>
              </div>
              <dl class="evidence">
                <div class="evidence__row">
                  <dt>文件系统</dt>
                  <dd>{{ fsTypeLabel }}</dd>
                </div>
                <div class="evidence__row">
                  <dt>挂载源</dt>
                  <dd>{{ data.profile.evidence.mountSource ?? '—' }}</dd>
                </div>
                <div class="evidence__row">
                  <dt>底层盘</dt>
                  <dd>{{ leafDevicesLabel }}</dd>
                </div>
                <div class="evidence__row">
                  <dt>rotational 标志</dt>
                  <dd>{{ rotationalLabel }}</dd>
                </div>
                <template v-if="data.profile.profile === 'ZFS'">
                  <div class="evidence__row">
                    <dt>ZFS 池</dt>
                    <dd>{{ data.profile.evidence.zfsPool ?? '—' }}</dd>
                  </div>
                  <div class="evidence__row">
                    <dt>cache / special vdev</dt>
                    <dd>{{ zfsCacheHintLabel }}</dd>
                  </div>
                </template>
                <div v-if="data.profile.evidence.overrideApplied" class="evidence__row">
                  <dt>形态强制</dt>
                  <dd>
                    强制 {{ data.profile.evidence.overrideApplied }}（自然探测
                    {{ data.profile.evidence.naturalProfile ?? '—' }}）
                  </dd>
                </div>
              </dl>
              <p v-if="data.profile.evidence.failureReason" class="profile-failure">
                探测未走全：{{ data.profile.evidence.failureReason }}——已按保守参数运行。
              </p>
            </PrefCard>
          </section>

          <!-- ═══ 自适应 IO 参数 ════════════════════════════════════════════ -->
          <section>
            <SectionHeader title="自适应 IO 参数" />
            <PrefCard>
              <PrefRow icon="reorder" title="读并发闸门" summary="同时放行的页文件读请求数上限">
                <span class="tuning-value">{{ concurrencyLabel }}</span>
              </PrefRow>
              <PrefRow icon="play-dark" title="翻页预读" summary="阅读翻页时后台预读下一页">
                <span class="tuning-value">{{ data.tuning.pageTurnPrefetchEnabled ? '开启' : '关闭' }}</span>
              </PrefRow>
              <PrefRow icon="check-all-dark" title="完整性巡检限速" summary="巡检读盘速度上限">
                <span class="tuning-value">{{ data.tuning.scrubRateLimitMbPerSec }} MB/s</span>
              </PrefRow>
              <PrefRow icon="pause-dark" title="维护 IO 优先级" summary="巡检/回填等后台维护任务的让路策略">
                <span class="tuning-value">
                  {{ data.tuning.maintenanceIoPriority === 'NORMAL' ? '正常' : '让路（IDLE）' }}
                </span>
              </PrefRow>
            </PrefCard>
          </section>

          <!-- ═══ 部署不变量 ═══════════════════════════════════════════════ -->
          <section>
            <SectionHeader title="部署不变量" />
            <div class="dep-card" :class="`dep-card--${deploymentState}`" role="status">
              <div class="dep-card__head">
                <span class="dep-card__title">{{ deploymentTitle }}</span>
                <span class="dep-card__chip">{{ deploymentChip }}</span>
              </div>
              <p class="dep-card__summary">{{ deploymentSummary }}</p>
              <ul class="dep-card__legs">
                <li v-for="leg in deploymentLegs" :key="leg.key" class="dep-leg">
                  <span class="dep-leg__name">{{ leg.name }}</span>
                  <span class="dep-leg__path">{{ leg.path }}</span>
                  <span class="dep-leg__profile">{{ leg.profileLabel }}</span>
                  <span class="dep-leg__status" :class="`dep-leg__status--${leg.state}`">
                    {{ leg.stateLabel }}
                  </span>
                </li>
              </ul>
            </div>
          </section>

          <!-- ═══ ZFS 建议（仅 ZFS profile；只建议不代配） ═════════════════ -->
          <section v-if="data.zfsRecommendations.length > 0">
            <SectionHeader title="ZFS 池建议" />
            <p class="zfs-intro">
              应用无权修改数据集属性——以下为池级调优建议，请由管理员在 ZFS 侧执行。
            </p>
            <ul class="zfs-list">
              <li v-for="rec in data.zfsRecommendations" :key="rec.id" class="zfs-item">
                <div class="zfs-item__head">
                  <AppIcon name="info-dark" size="16px" />
                  <span class="zfs-item__title">{{ rec.title }}</span>
                  <span class="zfs-item__chip">建议</span>
                </div>
                <p class="zfs-item__desc">{{ rec.description }}</p>
              </li>
            </ul>
          </section>
        </template>
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
import {
  storageApi,
  UNLIMITED_POOL_CONCURRENCY,
  type StorageDeploymentCheck,
  type StoragePathCheck,
  type StorageProfile,
} from '@/api/storage'
import AppIcon from '@/components/atoms/AppIcon.vue'
import { PrefCard, PrefRow, SectionHeader } from '@/components/form'

const data = ref<Awaited<ReturnType<typeof storageApi.getProfile>> | null>(null)
const loading = ref(true)
const redetecting = ref(false)

/* --------------------------------- labels --------------------------------- */

const PROFILE_LABELS: Record<StorageProfile, string> = {
  SSD: 'SSD',
  HDD: '机械盘（HDD）',
  ZFS: 'ZFS 池',
  UNKNOWN: '网络 FS（不可判）',
}

function profileLabel(profile: StorageProfile): string {
  return PROFILE_LABELS[profile] ?? profile
}

const fsTypeLabel = computed(() => {
  const e = data.value?.profile.evidence
  if (!e?.fsType) return '未知'
  const source = e.fsTypeSource === 'file-store' ? 'getFileStore' : e.fsTypeSource === 'mountinfo' ? 'mountinfo' : null
  return source ? `${e.fsType}（${source}）` : e.fsType
})

const leafDevicesLabel = computed(() => {
  const devices = data.value?.profile.evidence.leafDevices ?? []
  return devices.length > 0 ? devices.join('、') : '—'
})

const rotationalLabel = computed(() => {
  const entries = Object.entries(data.value?.profile.evidence.rotational ?? {})
  if (entries.length === 0) return '—'
  return entries.map(([disk, flag]) => `${disk}=${flag ?? '?'}`).join('、')
})

const zfsCacheHintLabel = computed(() => {
  const hint = data.value?.profile.evidence.zfsCacheHint
  if (hint === true) return '有'
  if (hint === false) return '确认无'
  return '未知（按保守并发 8 运行）'
})

const concurrencyLabel = computed(() => {
  const limit = data.value?.tuning.poolReadConcurrencyLimit
  if (limit === undefined) return '—'
  return limit >= UNLIMITED_POOL_CONCURRENCY ? '不设闸' : `${limit} 路`
})

/* ------------------------- deployment invariant --------------------------- */

type DeploymentState = 'ok' | 'violation' | 'unverifiable'
type LegState = 'ok' | 'violation' | 'unknown' | 'missing'

interface DeploymentLegView {
  key: string
  name: string
  path: string
  profileLabel: string
  state: LegState
  stateLabel: string
}

function legStateOf(leg: StoragePathCheck | null): LegState {
  if (!leg) return 'missing'
  if (leg.profile === 'UNKNOWN') return 'unknown'
  return leg.compliant ? 'ok' : 'violation'
}

const LEG_STATE_LABELS: Record<LegState, string> = {
  ok: '合规',
  violation: '违例',
  unknown: '不可验证',
  missing: '未配置',
}

function legViews(deployment: StorageDeploymentCheck | null): DeploymentLegView[] {
  return [
    { key: 'cache', name: '页面缓存（Cache）', leg: deployment?.cache ?? null },
    { key: 'db', name: '数据库（SQLite）', leg: deployment?.db ?? null },
  ].map(({ key, name, leg }) => {
    const state = legStateOf(leg)
    return {
      key,
      name,
      path: leg?.path ?? '未配置',
      profileLabel: leg ? profileLabel(leg.profile) : '—',
      state,
      stateLabel: LEG_STATE_LABELS[state],
    }
  })
}

const deploymentLegs = computed<DeploymentLegView[]>(() => legViews(data.value?.deployment ?? null))

const deploymentState = computed<DeploymentState>(() => {
  const d = data.value?.deployment
  if (!d) return 'unverifiable'
  if (d.invariantHeld) return 'ok'
  // 真违例（HDD/ZFS 落池）优先于不可验证（UNKNOWN/未配置）——黄牌压过灰牌。
  const violated = deploymentLegs.value.some((leg) => leg.state === 'violation')
  return violated ? 'violation' : 'unverifiable'
})

const DEPLOYMENT_META: Record<DeploymentState, { title: string; chip: string; summary: string }> = {
  ok: {
    title: '部署不变量成立',
    chip: '正常',
    summary: '页面缓存与数据库均位于 SSD 卷。',
  },
  violation: {
    title: '部署不变量违例',
    chip: '注意',
    summary: 'cache/DB 应恒在 SSD 运行——检测到落池/落机械盘，请迁移后重启服务。',
  },
  unverifiable: {
    title: '部署不变量不可验证',
    chip: '待确认',
    summary: '所在卷为网络/用户态文件系统（或路径未配置），形态无法判定——请人工确认 cache 与 DB 位于 SSD。',
  },
}

const deploymentTitle = computed(() => DEPLOYMENT_META[deploymentState.value].title)
const deploymentChip = computed(() => DEPLOYMENT_META[deploymentState.value].chip)
const deploymentSummary = computed(() => DEPLOYMENT_META[deploymentState.value].summary)

/* --------------------------------- actions -------------------------------- */

async function load(): Promise<void> {
  try {
    data.value = await storageApi.getProfile()
  } catch (error) {
    console.error('[AdminStorage] failed to load storage profile', error)
    data.value = null
    showSnack('无法加载存储信息')
  } finally {
    loading.value = false
  }
}

/** 重新探测：后端 redetect + tuning refresh；成功后重拉整页数据。 */
async function onRedetect(): Promise<void> {
  if (redetecting.value) return
  redetecting.value = true
  try {
    await storageApi.redetect()
    await load()
    showSnack('已重新探测')
  } catch (error) {
    console.error('[AdminStorage] redetect failed', error)
    showSnack('重新探测失败，请稍后重试')
  } finally {
    redetecting.value = false
  }
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

onBeforeUnmount(() => {
  if (snackTimer) window.clearTimeout(snackTimer)
})

onMounted(() => {
  void load()
})
</script>

<style scoped>
/* Scene shell — content column lives inside AdminLayout. */
.admin-storage {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--color-bg);
}

/* --------------------------------- toolbar -------------------------------- */

.admin-storage__toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  padding: 16px var(--keyline-margin) 0;
}

.admin-storage__title {
  margin: 0;
  font-size: clamp(17px, 20px, 24px);
  font-weight: 600;
  letter-spacing: 0.01em;
  color: var(--text-color-primary);
}

.admin-storage__redetect {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
  padding: 8px 16px;
  border: none;
  border-radius: 999px;
  background: var(--color-primary);
  color: var(--color-white);
  font-size: clamp(12px, 13px, 14px);
  font-weight: 700;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition:
    background-color 150ms var(--ease-decelerate-quart),
    transform 120ms var(--ease-decelerate-quart);
}

.admin-storage__redetect:hover:not(:disabled) {
  background: var(--color-primary-dark);
}

.admin-storage__redetect:active:not(:disabled) {
  transform: scale(0.97);
}

.admin-storage__redetect:disabled {
  opacity: 0.6;
  cursor: default;
}

/* ---------------------------------- body ---------------------------------- */

.admin-storage__body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.admin-storage__column {
  max-width: 760px;
  margin: 0 auto;
  padding: 4px var(--keyline-margin) calc(56px + var(--safe-area-bottom));
}

.admin-storage__state {
  margin: 24px 0;
  text-align: center;
  font-size: clamp(13px, 14px, 16px);
  color: var(--text-color-secondary);
}

/* ------------------------------ profile card ------------------------------ */

.profile-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  padding: 12px var(--keyline-margin);
}

.profile-badge {
  padding: 3px 12px;
  border-radius: 999px;
  font-size: clamp(12px, 13px, 14px);
  font-weight: 700;
  letter-spacing: 0.04em;
}

.profile-badge--SSD {
  background: color-mix(in srgb, var(--color-light-green-600) 16%, transparent);
  color: var(--color-deep-green-600);
}

.profile-badge--HDD {
  background: color-mix(in srgb, var(--color-yellow-800) 16%, transparent);
  color: var(--color-yellow-800);
}

.profile-badge--ZFS {
  background: color-mix(in srgb, var(--color-cat-non-h) 16%, transparent);
  color: var(--color-cat-non-h);
}

.profile-badge--UNKNOWN {
  background: color-mix(in srgb, var(--grey-500) 18%, transparent);
  color: var(--text-color-secondary);
}

.profile-head__root {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: clamp(12px, 13px, 14px);
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.evidence {
  margin: 0;
  padding: 0 var(--keyline-margin) 8px;
}

.evidence__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  padding: 7px 0;
}

.evidence__row + .evidence__row {
  border-top: 1px solid var(--color-divider);
}

.evidence__row dt {
  flex: 0 0 auto;
  font-size: clamp(12px, 13px, 14px);
  color: var(--text-color-secondary);
}

.evidence__row dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  text-align: right;
  font-size: clamp(12px, 13px, 14px);
  color: var(--text-color-primary);
  font-variant-numeric: tabular-nums;
}

.profile-failure {
  margin: 0;
  padding: 8px var(--keyline-margin) 12px;
  font-size: clamp(12px, 13px, 14px);
  line-height: 1.5;
  color: var(--color-yellow-800);
}

/* ------------------------------ tuning values ----------------------------- */

.tuning-value {
  font-size: clamp(13px, 14px, 16px);
  font-weight: 700;
  color: var(--text-color-primary);
  font-variant-numeric: tabular-nums;
}

/* --------------------------- deployment card ------------------------------ */

.dep-card {
  border: 1px solid var(--color-divider);
  border-radius: var(--card-radius);
  background: var(--color-surface);
  padding: 12px var(--keyline-margin);
}

.dep-card--violation {
  border-color: color-mix(in srgb, var(--color-yellow-800) 55%, transparent);
  background: color-mix(in srgb, var(--color-yellow-800) 10%, var(--color-surface));
}

.dep-card--unverifiable {
  background: color-mix(in srgb, var(--grey-500) 10%, var(--color-surface));
}

.dep-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dep-card__title {
  font-size: clamp(14px, 15px, 16px);
  font-weight: 700;
  color: var(--text-color-primary);
}

.dep-card__chip {
  margin-left: auto;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: clamp(11px, 12px, 13px);
  font-weight: 700;
}

.dep-card--ok .dep-card__chip {
  background: color-mix(in srgb, var(--color-light-green-600) 18%, transparent);
  color: var(--color-deep-green-600);
}

.dep-card--violation .dep-card__chip {
  background: color-mix(in srgb, var(--color-yellow-800) 20%, transparent);
  color: var(--color-yellow-800);
}

.dep-card--unverifiable .dep-card__chip {
  background: color-mix(in srgb, var(--grey-500) 20%, transparent);
  color: var(--text-color-secondary);
}

.dep-card__summary {
  margin: 6px 0 0;
  font-size: clamp(12px, 13px, 14px);
  line-height: 1.5;
  color: var(--text-color-secondary);
}

.dep-card__legs {
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}

.dep-leg {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 12px;
  padding: 7px 0;
}

.dep-leg + .dep-leg {
  border-top: 1px solid var(--color-divider);
}

.dep-leg__name {
  flex: 0 0 auto;
  font-size: clamp(12px, 13px, 14px);
  font-weight: 700;
  color: var(--text-color-primary);
}

.dep-leg__path {
  flex: 1 1 160px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: clamp(11px, 12px, 13px);
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.dep-leg__profile {
  flex: 0 0 auto;
  font-size: clamp(11px, 12px, 13px);
  color: var(--text-color-secondary);
}

.dep-leg__status {
  flex: 0 0 auto;
  font-size: clamp(11px, 12px, 13px);
  font-weight: 700;
}

.dep-leg__status--ok {
  color: var(--color-deep-green-600);
}

.dep-leg__status--violation {
  color: var(--color-yellow-800);
}

.dep-leg__status--unknown,
.dep-leg__status--missing {
  color: var(--text-color-secondary);
}

/* ----------------------------- ZFS suggestions ---------------------------- */

.zfs-intro {
  margin: 0 0 8px;
  padding: 0 var(--keyline-margin);
  font-size: clamp(12px, 13px, 14px);
  color: var(--text-color-secondary);
}

.zfs-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.zfs-item {
  border: 1px solid color-mix(in srgb, var(--color-yellow-800) 40%, transparent);
  border-radius: var(--card-radius);
  background: color-mix(in srgb, var(--color-yellow-800) 8%, var(--color-surface));
  padding: 10px var(--keyline-margin);
}

.zfs-item + .zfs-item {
  margin-top: 8px;
}

.zfs-item__head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-color-primary);
}

.zfs-item__title {
  min-width: 0;
  font-size: clamp(13px, 14px, 15px);
  font-weight: 700;
}

.zfs-item__chip {
  margin-left: auto;
  flex: 0 0 auto;
  padding: 2px 10px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--color-yellow-800) 20%, transparent);
  color: var(--color-yellow-800);
  font-size: clamp(11px, 12px, 13px);
  font-weight: 700;
}

.zfs-item__desc {
  margin: 6px 0 0;
  font-size: clamp(12px, 13px, 14px);
  line-height: 1.5;
  color: var(--text-color-secondary);
}

/* --------------------------------- snackbar ------------------------------- */

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
