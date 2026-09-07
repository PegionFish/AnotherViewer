<!--
  SettingsIndex.vue — 设置 · 分组索引页（/settings exact，A5-3 Android 式层级导航）.

  Narrow viewports (<960px): 按「偏好 / 服务器」分组的大行列表，44px 触控行高，
  复用抽屉行形态（icon + label + hover/触屏豁免）。
  Wide viewports (≥960px): 双栏布局不需要索引页 — matchMedia 断点监听
  （响应式，不做 CSS 双渲染取巧）：宽屏落到 /settings exact 时 replace 到
  默认子页；窄挂载后跨过断点（窗口拉宽/旋转）时由 change 补发同一跳转，
  索引列表随 ref 即时隐藏（一次性判定会过期，线上出现过错成双列表）。
-->
<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/atoms/AppIcon.vue'
import { SETTINGS_DEFAULT_SUBPATH, SETTINGS_GROUPS, SETTINGS_WIDE_QUERY } from './settingsSections'

const router = useRouter()
const route = useRoute()
const groups = SETTINGS_GROUPS

// 宽屏判定必须与视口同寿命：CSS 侧栏断点是实时的，JS 判定若只查一次，
// 跨 960px 后两者失步。索引页本体只在窄屏渲染（v-if 跟随判定）。
const wideQuery =
  typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(SETTINGS_WIDE_QUERY)
    : null

const isWideViewport = ref(wideQuery?.matches ?? false)

function onWideChange(event: MediaQueryListEvent): void {
  isWideViewport.value = event.matches
  // 本组件只挂在 /settings exact；路径核对是防御（若日后入 KeepAlive，
  // 停用实例的监听不得替其他路由发导航）。
  if (event.matches && route.path === '/settings') {
    void router.replace(SETTINGS_DEFAULT_SUBPATH)
  }
}

if (wideQuery) {
  wideQuery.addEventListener('change', onWideChange)
  onUnmounted(() => wideQuery.removeEventListener('change', onWideChange))
  if (isWideViewport.value) {
    void router.replace(SETTINGS_DEFAULT_SUBPATH)
  }
}
</script>

<template>
  <div v-if="!isWideViewport" class="settings-index" data-testid="settings-index">
    <header class="settings-index__header">
      <h1 class="settings-index__title">设置</h1>
    </header>
    <nav class="settings-index__nav">
      <section v-for="group in groups" :key="group.label" class="settings-index__group">
        <h2 class="settings-index__group-label">{{ group.label }}</h2>
        <router-link
          v-for="item in group.items"
          :key="item.path"
          :to="item.path"
          class="settings-index__row"
          data-testid="settings-index-row"
        >
          <AppIcon :name="item.icon" size="20px" class="settings-index__row-icon" />
          <span class="settings-index__row-label">{{ item.label }}</span>
        </router-link>
      </section>
    </nav>
  </div>
</template>

<style scoped>
.settings-index {
  min-height: 100%;
  background: var(--color-bg);
  padding-bottom: var(--safe-area-bottom);
}

.settings-index__header {
  padding: 16px var(--keyline-margin) 8px;
}

.settings-index__title {
  margin: 0;
  font-size: clamp(17px, 20px, 24px);
  font-weight: 600;
  letter-spacing: 0.01em;
  color: var(--text-color-primary);
}

.settings-index__nav {
  padding: 0 8px;
}

.settings-index__group {
  margin-bottom: 16px;
}

.settings-index__group-label {
  margin: 0 12px 4px;
  font-size: var(--text-super-small);
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-color-secondary);
}

/* 抽屉行形态：icon + label，44px 触控行高。 */
.settings-index__row {
  display: flex;
  align-items: center;
  gap: 20px;
  height: 44px;
  padding: 0 12px;
  border-radius: var(--card-radius);
  font-size: var(--text-small);
  color: var(--text-color-primary);
  text-decoration: none;
  white-space: nowrap;
  transition: background-color 150ms var(--ease-decelerate-quart);
}

.settings-index__row:hover {
  background: var(--color-surface);
}

/* B4 触屏豁免：粘滞 hover 会在点按后把灰底永久卡住。 */
@media (hover: none) {
  .settings-index__row:hover {
    background: transparent;
  }
}

.settings-index__row-icon {
  color: var(--drawable-color-primary);
}

/* Registry icons may carry hardcoded fills — force them to follow the row. */
.settings-index__row-icon :deep(svg path) {
  fill: currentColor;
}

.settings-index__row-label {
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 汉堡可见视口（<720px，或横屏矮视口）：页头避让浮动汉堡
   （--hamburger-clearance 契约消费方）。 */
@media (max-width: 719px), (min-width: 720px) and (max-height: 479.98px) {
  .settings-index__header {
    padding-left: var(--hamburger-clearance);
  }
}
</style>
