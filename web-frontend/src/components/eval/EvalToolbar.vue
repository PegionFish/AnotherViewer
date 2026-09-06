<template>
  <div class="eval-toolbar" :style="{ height: `${EVAL_TOOLBAR_H}px` }" role="toolbar" aria-label="评估台工具条">
    <AppSegmented
      class="eval-toolbar__group"
      :model-value="ratio"
      :options="RATIO_OPTIONS"
      aria-label="画面比例"
      @update:model-value="onRatio"
    />
    <AppSegmented
      class="eval-toolbar__group"
      :model-value="orientation"
      :options="ORIENTATION_OPTIONS"
      aria-label="画面方向"
      @update:model-value="onOrientation"
    />
    <AppSegmented
      class="eval-toolbar__group"
      :model-value="scaleMode"
      :options="SCALE_OPTIONS"
      aria-label="缩放档位"
      @update:model-value="onScaleMode"
    />
    <button
      type="button"
      class="eval-toolbar__btn"
      :class="{ 'eval-toolbar__btn--on': locked }"
      :aria-pressed="locked"
      @click="emit('toggle-lock')"
    >
      锁定
    </button>
    <button type="button" class="eval-toolbar__btn" @click="emit('fullscreen')">全屏</button>
    <button type="button" class="eval-toolbar__btn" @click="emit('open-new-tab')">新标签打开</button>
    <button type="button" class="eval-toolbar__btn" @click="emit('exit')">退出</button>
  </div>
</template>

<script setup lang="ts">
/**
 * EvalToolbar (W2 / C8) — 评估台工具条：比例 / 方向 / 缩放三组 segmented
 * + 锁定 / 全屏 / 新标签打开 / 退出四个按钮。纯受控组件：状态由 EvalView
 * （useViewportFrame）持有，这里只回抛事件；锁定态经 `aria-pressed` 与
 * 高亮样式反映。高度固定 EVAL_TOOLBAR_H（与 composable 的可用空间推导
 * 同源），窄屏时控件横向滚动兜底，不在 56px 内换行溢出。
 */
import AppSegmented from '@/components/form/AppSegmented.vue'
import {
  EVAL_TOOLBAR_H,
  type AspectRatio,
  type Orientation,
  type ScaleMode,
} from '@/composables/useViewportFrame'

interface SegmentedOption {
  value: string
  label: string
}

const RATIO_OPTIONS: SegmentedOption[] = [
  { value: '16:9', label: '16:9' },
  { value: '16:10', label: '16:10' },
  { value: '4:3', label: '4:3' },
  { value: '3:2', label: '3:2' },
]

const ORIENTATION_OPTIONS: SegmentedOption[] = [
  { value: 'landscape', label: '横屏' },
  { value: 'portrait', label: '竖屏' },
]

const SCALE_OPTIONS: SegmentedOption[] = [
  { value: 'fit', label: '适应' },
  { value: '100', label: '100%' },
]

defineProps<{
  ratio: AspectRatio
  orientation: Orientation
  scaleMode: ScaleMode
  locked: boolean
}>()

const emit = defineEmits<{
  (e: 'update:ratio', value: AspectRatio): void
  (e: 'update:orientation', value: Orientation): void
  (e: 'update:scaleMode', value: ScaleMode): void
  (e: 'toggle-lock'): void
  (e: 'fullscreen'): void
  (e: 'open-new-tab'): void
  (e: 'exit'): void
}>()

// AppSegmented 以 string 建模，回抛时收窄为对应字面量联合。
function onRatio(value: string): void {
  emit('update:ratio', value as AspectRatio)
}

function onOrientation(value: string): void {
  emit('update:orientation', value as Orientation)
}

function onScaleMode(value: string): void {
  emit('update:scaleMode', value as ScaleMode)
}
</script>

<style scoped>
.eval-toolbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-divider);
  /* 窄屏兜底：整条横向滚动，控件本身不收缩换行。 */
  overflow-x: auto;
  overflow-y: hidden;
  white-space: nowrap;
  scrollbar-width: none;
}

.eval-toolbar::-webkit-scrollbar {
  display: none;
}

.eval-toolbar__group {
  flex: 0 0 auto;
}

.eval-toolbar__btn {
  flex: 0 0 auto;
  padding: 6px 12px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-color-secondary);
  font-size: 13px;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background-color 150ms var(--ease-decelerate-quart),
    color 150ms var(--ease-decelerate-quart);
}

.eval-toolbar__btn:hover {
  color: var(--text-color-primary);
  background: var(--color-surface-activated);
}

/* B4 触屏豁免：粘滞 hover 不再卡底色（与 App.vue 汉堡按钮同一手法）。 */
@media (hover: none) {
  .eval-toolbar__btn:hover {
    color: var(--text-color-secondary);
    background: transparent;
  }
}

.eval-toolbar__btn--on {
  background: var(--content-color-theme-primary);
  color: var(--color-white);
  font-weight: 700;
}
</style>
