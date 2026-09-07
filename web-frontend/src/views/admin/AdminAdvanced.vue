<!--
  AdminAdvanced.vue — 管理面板「高级」页（Wave 6）.

  复用 AdminLayout 内容区与 settings 页的偏好分组 / 对话框样式：

    - 同步策略（ADR-0003）：冲突仲裁策略与自动同步间隔，经 syncApi 持久化；
    - 清除本地数据：confirm 后删除全部 `anotherviewer-` 前缀的 localStorage
      条目（保留 token / username，避免意外登出）。

  2026-09-07 去重（plan F1/F4/F5）：导出/导入数据与「备份与还原」页同
  API 重复，已删（含 restorePending 横幅）；界面语言/保存解析错误日志为
  无消费方的 localStorage 半成品，已删；内容打码模式迁往偏好「隐私」页。
-->
<template>
  <div class="advanced">
    <div class="advanced__column">
      <header class="advanced__header">
        <h1 class="advanced__title">高级</h1>
      </header>

      <!-- ═══ 同步策略（Wave-2 / ADR-0003）═══════════════════════════════ -->
      <section>
        <SectionHeader title="同步策略" />
        <PrefCard>
          <PrefRow
            icon="settings-dark"
            title="冲突仲裁策略"
            summary="App 为权威：WebUI 的修改将被下次 App 同步覆盖（D2）"
          >
            <AppSelect
              :model-value="policy?.conflictStrategy ?? 'device_priority'"
              :options="STRATEGY_OPTIONS"
              aria-label="冲突仲裁策略"
              @update:model-value="onStrategyChange"
            />
          </PrefRow>
          <PrefRow
            icon="refresh-dark"
            title="自动同步间隔（秒）"
            summary="App 进入本网络后的周期同步间隔；0=仅网络变化时同步"
          >
            <AppSelect
              :model-value="policy?.autoSyncIntervalSec ?? 900"
              :options="INTERVAL_OPTIONS"
              aria-label="自动同步间隔"
              @update:model-value="onIntervalChange"
            />
          </PrefRow>
        </PrefCard>
      </section>

      <!-- ═══ 数据 ══════════════════════════════════════════════════════ -->
      <section>
        <SectionHeader title="数据" />
        <PrefCard>
          <PrefRow icon="clear-all-dark" title="清除本地数据" summary="删除此浏览器中存储的全部本地数据">
            <button type="button" class="advanced__action" aria-label="清除本地数据" @click="confirmClearLocal">
              <AppIcon name="delete-dark" size="20px" />
            </button>
          </PrefRow>
        </PrefCard>
      </section>
    </div>

    <!-- Confirm dialog. -->
    <Transition name="dialog">
      <div v-if="confirmOpen" class="dialog-scrim" @click.self="confirmOpen = false">
        <div class="dialog" role="dialog" aria-modal="true" aria-label="清除本地数据">
          <h2 class="dialog__title">清除本地数据</h2>
          <p class="dialog__message">
            删除此浏览器中所有本地的设置、缓存与搜索历史？登录状态不受影响。
          </p>
          <div class="dialog__actions">
            <button type="button" class="btn-text" @click="confirmOpen = false">取消</button>
            <button type="button" class="btn-primary btn-primary--danger" @click="clearLocalData">
              清除
            </button>
          </div>
        </div>
      </div>
    </Transition>

    <!-- Snackbar. -->
    <Transition name="snack">
      <div v-if="snack" class="advanced__snackbar" role="status">{{ snack }}</div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import AppIcon from '@/components/atoms/AppIcon.vue'
import { AppSelect, PrefCard, PrefRow, SectionHeader } from '@/components/form'
import { syncApi, type SyncPolicy } from '@/api/sync'

/** 登录凭证——清除本地数据时保留，避免意外登出。 */
const AUTH_KEYS = new Set(['token', 'username'])

/* ------------------------- sync policy (ADR-0003) ------------------------- */

const STRATEGY_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'device_priority', label: 'Android 优先（默认）' },
  { value: 'lww', label: '最后写入胜出' },
  { value: 'web_priority', label: 'WebUI 优先' },
]

const INTERVAL_OPTIONS: Array<{ value: number; label: string }> = [
  { value: 0, label: '仅网络变化时' },
  { value: 300, label: '5 分钟' },
  { value: 900, label: '15 分钟（默认）' },
  { value: 1800, label: '30 分钟' },
  { value: 3600, label: '1 小时' },
]

const policy = ref<SyncPolicy | null>(null)

onMounted(() => {
  syncApi
    .getPolicy()
    .then((p) => {
      policy.value = p
    })
    .catch(() => {
      // Legacy/unreachable server — the panel keeps contract defaults.
    })
})

async function persistPolicy(next: SyncPolicy): Promise<void> {
  try {
    policy.value = await syncApi.updatePolicy(next)
    showSnack('同步策略已保存')
  } catch {
    showSnack('同步策略保存失败')
  }
}

function onStrategyChange(value: string | number): void {
  const current = policy.value ?? {
    conflictStrategy: 'device_priority' as const,
    clientTier: 1 as const,
    autoSyncIntervalSec: 900,
  }
  void persistPolicy({ ...current, conflictStrategy: String(value) as SyncPolicy['conflictStrategy'] })
}

function onIntervalChange(value: string | number): void {
  const current = policy.value ?? {
    conflictStrategy: 'device_priority' as const,
    clientTier: 1 as const,
    autoSyncIntervalSec: 900,
  }
  void persistPolicy({ ...current, autoSyncIntervalSec: Number(value) })
}

/* --------------------------------- data ops ------------------------------- */

const confirmOpen = ref(false)

function confirmClearLocal(): void {
  confirmOpen.value = true
}

function clearLocalData(): void {
  confirmOpen.value = false
  let cleared = 0
  for (let i = localStorage.length - 1; i >= 0; i--) {
    const key = localStorage.key(i)
    if (key?.startsWith('anotherviewer-') && !AUTH_KEYS.has(key)) {
      localStorage.removeItem(key)
      cleared++
    }
  }
  showSnack(cleared > 0 ? `已清除 ${cleared} 项本地数据` : '本地数据已为空')
}

/* --------------------------------- chrome --------------------------------- */

const snack = ref('')
let snackTimer: number | undefined

function showSnack(message: string, duration = 2600): void {
  snack.value = message
  if (snackTimer) window.clearTimeout(snackTimer)
  snackTimer = window.setTimeout(() => {
    snack.value = ''
  }, duration)
}

onBeforeUnmount(() => {
  if (snackTimer) window.clearTimeout(snackTimer)
})
</script>

<style scoped>
.advanced {
  min-height: 100%;
  background: var(--color-bg);
}

.advanced__column {
  max-width: 760px;
  margin: 0 auto;
  padding: 4px var(--keyline-margin) calc(56px + var(--safe-area-bottom));
}

/* ---------------------------------- header --------------------------------- */

.advanced__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 4px 4px;
}

.advanced__title {
  margin: 0;
  font-size: clamp(17px, 20px, 24px);
  font-weight: 600;
  letter-spacing: 0.01em;
  color: var(--text-color-primary);
}

/* ----------------------------- preference group --------------------------- */

.advanced__action {
  display: inline-flex;
  align-items: center;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--text-color-secondary);
  cursor: pointer;
}

.advanced__action:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

/* ---------------------------------- dialogs -------------------------------- */

.dialog-scrim {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--black-overlay);
}

.dialog {
  width: min(420px, 100%);
  padding: 20px 20px 12px;
  border-radius: var(--card-radius);
  background: var(--color-background-floating);
  box-shadow: 0 8px 24px var(--shadow-color);
}

.dialog__title {
  margin: 0 0 12px;
  font-size: clamp(16px, 18px, 22px);
  font-weight: 700;
  color: var(--text-color-primary);
}

.dialog__message {
  margin: 0 0 8px;
  font-size: clamp(13px, 14px, 16px);
  line-height: 1.5;
  color: var(--text-color-secondary);
}

.dialog__actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 16px;
  padding-top: 8px;
  border-top: 1px solid var(--color-divider);
}

.dialog-enter-active,
.dialog-leave-active {
  transition: opacity var(--duration-scene-opacity) var(--ease-decelerate-quart);
}

.dialog-enter-active .dialog,
.dialog-leave-active .dialog {
  transition:
    transform var(--duration-scene-translate) var(--ease-decelerate-quint),
    opacity var(--duration-scene-opacity) var(--ease-decelerate-quart);
}

.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}

.dialog-enter-from .dialog,
.dialog-leave-to .dialog {
  transform: translateY(16px) scale(0.97);
  opacity: 0;
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

.btn-primary:hover {
  background: var(--color-primary-dark);
}

.btn-primary:active {
  transform: scale(0.97);
}

.btn-primary--danger {
  background: var(--color-red-500);
}

.btn-primary--danger:hover {
  background: var(--color-red-500);
  filter: brightness(0.92);
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

.btn-text:hover {
  background: var(--color-surface);
}

/* --------------------------------- snackbar -------------------------------- */

.advanced__snackbar {
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
  .dialog-enter-active .dialog,
  .dialog-leave-active .dialog,
  .snack-enter-active,
  .snack-leave-active {
    transition: none;
  }
}
</style>
