<!--
  SetupHintCard.vue — 首次使用引导卡（PWA 多平台，plan-2026-09-06-pwa C7）。

  仅在 Home 路由且用户从未显式配置过服务器（isServerConfigured() 为假）时
  出现的非阻塞浮层卡片：提示可把后端指向另一台机器，两个动作入口——
  「去配置」（/setup 服务器设置向导）与「进入比例评估」（/eval）。
  关闭（×）后以 localStorage 键 `server-setup-dismissed` 持久化，不再打扰。

  定位为 fixed 浮层，不占据布局空间、不参与 KeepAlive；与 App.vue 的
  汉堡页眉（左上角，z-index 90）和 snackbar（z-index 300）分层互不遮挡。
-->
<template>
  <Transition name="setup-hint">
    <aside v-if="visible" class="setup-hint" role="status">
      <div class="setup-hint__text">
        <p class="setup-hint__title">尚未配置数据服务器</p>
        <p class="setup-hint__desc">当前按同源方式访问。如果后端部署在另一台机器上，请先完成服务器配置。</p>
      </div>
      <div class="setup-hint__actions">
        <button type="button" class="setup-hint__btn setup-hint__btn--primary" @click="goSetup">去配置</button>
        <button type="button" class="setup-hint__btn" @click="goEval">进入比例评估</button>
      </div>
      <button type="button" class="setup-hint__close" aria-label="关闭引导" @click="dismiss">&times;</button>
    </aside>
  </Transition>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isServerConfigured } from '@/stores/server'

/** 关闭状态持久化键（值 '1'）—— dismissing 是一次性决定，无需二次确认。 */
const DISMISS_KEY = 'server-setup-dismissed'

const route = useRoute()
const router = useRouter()

function readDismissed(): boolean {
  try {
    return localStorage.getItem(DISMISS_KEY) === '1'
  } catch {
    // 存储不可用时不持久化，仅本页生命周期内生效。
    return false
  }
}

const dismissed = ref(readDismissed())

function dismiss(): void {
  dismissed.value = true
  try {
    localStorage.setItem(DISMISS_KEY, '1')
  } catch {
    // 同上：存储失败不阻塞关闭。
  }
}

/**
 * 显示条件（冻结契约）：Home 路由 && 用户未显式配置过服务器 && 未关闭。
 * isServerConfigured() 每次路由变化时重读——配置完成必然整页重载，
 * 所以这里不需要更细粒度的响应源。
 */
const visible = computed(() => route.name === 'Home' && !isServerConfigured() && !dismissed.value)

function goSetup(): void {
  router.push('/setup')
}

function goEval(): void {
  router.push('/eval')
}
</script>

<style scoped>
.setup-hint {
  position: fixed;
  left: 50%;
  bottom: calc(16px + var(--safe-area-bottom));
  translate: -50% 0;
  z-index: 85;
  display: flex;
  align-items: center;
  gap: 12px;
  width: min(620px, calc(100vw - 24px));
  padding: 14px 16px;
  border-radius: var(--card-radius);
  background: var(--color-background-floating);
  box-shadow:
    0 6px 24px var(--shadow-color),
    0 0 1px var(--shadow-color);
}

.setup-hint__text {
  flex: 1 1 auto;
  min-width: 0;
}

.setup-hint__title {
  margin: 0;
  font-size: clamp(13px, 14px, 16px);
  font-weight: 700;
  color: var(--text-color-primary);
}

.setup-hint__desc {
  margin: 2px 0 0;
  font-size: clamp(11px, 12px, 14px);
  line-height: 1.4;
  color: var(--text-color-secondary);
}

.setup-hint__actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
}

.setup-hint__btn {
  min-height: 36px;
  padding: 0 14px;
  border: 1px solid var(--color-divider);
  border-radius: 999px;
  background: transparent;
  color: var(--color-primary-text);
  font-size: clamp(12px, 13px, 14px);
  font-weight: 700;
  white-space: nowrap;
  cursor: pointer;
  transition:
    border-color 150ms var(--ease-decelerate-quart),
    background-color 150ms var(--ease-decelerate-quart);
}

.setup-hint__btn:hover {
  border-color: var(--color-primary);
  background: color-mix(in srgb, var(--color-primary) 8%, transparent);
}

.setup-hint__btn--primary {
  border: none;
  background: var(--color-primary);
  color: var(--color-white);
}

.setup-hint__btn--primary:hover {
  background: var(--color-primary-dark);
}

.setup-hint__close {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--text-color-secondary);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
}

.setup-hint__close:hover {
  background: var(--color-surface);
  color: var(--text-color-primary);
}

.setup-hint-enter-active,
.setup-hint-leave-active {
  transition:
    opacity var(--duration-scene-opacity) var(--ease-decelerate-quart),
    translate var(--duration-scene-translate) var(--ease-decelerate-quint);
}

.setup-hint-enter-from,
.setup-hint-leave-to {
  opacity: 0;
  translate: -50% 12px;
}

@media (max-width: 560px) {
  .setup-hint {
    flex-wrap: wrap;
  }

  .setup-hint__actions {
    flex: 1 1 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .setup-hint-enter-active,
  .setup-hint-leave-active {
    transition: none;
  }
}
</style>
