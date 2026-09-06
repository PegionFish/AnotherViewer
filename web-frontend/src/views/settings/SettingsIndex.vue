<!--
  SettingsIndex.vue — 设置 · 分组索引页（/settings exact，A5-3 Android 式层级导航）.

  Narrow viewports (<960px): 按「偏好 / 服务器」分组的大行列表，44px 触控行高，
  复用抽屉行形态（icon + label + hover/触屏豁免）。
  Wide viewports (≥960px): 双栏布局不需要索引页 — setup 内 matchMedia
  一次性检查后 router.replace 到默认子页（不做 CSS 双渲染取巧）。
-->
<script setup lang="ts">
import { useRouter } from 'vue-router'
import AppIcon from '@/components/atoms/AppIcon.vue'
import { SETTINGS_DEFAULT_SUBPATH, SETTINGS_GROUPS, SETTINGS_WIDE_QUERY } from './settingsSections'

const router = useRouter()
const groups = SETTINGS_GROUPS

// 宽屏一次性检查（不挂 resize 监听）：宽屏落到 /settings exact 时直接替换到
// 默认子页；索引页本体只在窄屏渲染（v-if，避免宽屏闪一帧列表）。
const isWideViewport =
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia(SETTINGS_WIDE_QUERY).matches

if (isWideViewport) {
  void router.replace(SETTINGS_DEFAULT_SUBPATH)
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
