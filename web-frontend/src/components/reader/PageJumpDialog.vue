<template>
  <!--
    A5 跳页对话框 — 精确页号/百分比定位（Android 端无对应控件，Web 增补；
    语义对齐 SeekBarPanel 的 1-based seek 契约）。挂在阅读器根内，z 层与
    ReaderSettings 一致（遮罩之下、chrome 之上）。
  -->
  <Transition name="page-jump">
    <div
      v-if="visible"
      class="page-jump"
      role="dialog"
      aria-modal="true"
      aria-label="跳页"
      @keydown.esc="onEscape"
    >
      <div class="page-jump__scrim" @click="emit('close')" />

      <form class="page-jump__card" @submit.prevent="confirm">
        <h2 class="page-jump__title">跳页</h2>
        <p class="page-jump__meta">当前第 {{ displayCurrent }} / {{ totalPages }} 页</p>

        <label class="page-jump__field">
          <span class="page-jump__field-label">页号（1–{{ totalPages }}）</span>
          <!-- type="text"（非 number）：非法输入必须能留在框里被校验拦截，
               而不是被浏览器静默清空——「非法输入不跳」要可观察。 -->
          <input
            ref="pageFieldRef"
            v-model="pageInput"
            class="page-jump__input"
            type="text"
            inputmode="numeric"
            autocomplete="off"
            placeholder="输入页号"
            @input="onPageInput"
          />
        </label>

        <label class="page-jump__field">
          <span class="page-jump__field-label">百分比（可选，0–100）</span>
          <input
            v-model="percentInput"
            class="page-jump__input"
            type="text"
            inputmode="decimal"
            autocomplete="off"
            placeholder="如 37.5"
            @input="onPercentInput"
          />
        </label>

        <p v-if="!valid" class="page-jump__error" role="alert">请输入有效页号</p>

        <div class="page-jump__actions">
          <button type="button" class="page-jump__btn" @click="emit('close')">取消</button>
          <button type="submit" class="page-jump__btn page-jump__btn--primary" :disabled="!valid">
            跳转
          </button>
        </div>
      </form>
    </div>
  </Transition>
</template>

<script setup lang="ts">
/**
 * PageJumpDialog.vue (A5) — 页码跳转对话框：
 *
 * - 页号输入为 1-based（与 SeekBarPanel 的公开契约一致）；整数一律钳入
 *   [1, totalPages]（0 → 1、越界 → 末页）；非数字/空串视为非法——确定钮
 *   禁用且 confirm 早退，绝不发出 jump（「非法输入不跳」）。
 * - 百分比输入可选：0–100（支持小数），超出范围按 0/100 钳制；两个输入框
 *   双向联动（改页号同步百分比、改百分比反算页号，四舍五入后同样钳制），
   因此 confirm 恒以页号字段为准，两者天然一致。
 * - `jump` 事件载荷是 1-based 页号——父级（ImageReader）走与 SeekBarPanel
 *   `change` 完全相同的通路（update:currentPage(page - 1)），双页模式下
 *   「跳到的页即铺摊主页」由既有渲染换算自动保证。
 * - Esc / 点击遮罩 / 取消 → `close`；Esc 依赖 keydown 从输入框冒泡到根
   （焦点常驻页号输入框；焦点在对话框外时由 ImageReader.handleBack 兜底）。
 */
import { computed, nextTick, ref, watch } from 'vue'

interface PageJumpDialogProps {
  visible: boolean
  /** 0-based 当前页（与 ImageReader 的 currentPage 同约定）。 */
  currentPage: number
  totalPages: number
}

interface PageJumpDialogEmits {
  (e: 'close'): void
  /** 1-based 目标页（已钳入 [1, totalPages]）。 */
  (e: 'jump', page: number): void
}

const props = defineProps<PageJumpDialogProps>()
const emit = defineEmits<PageJumpDialogEmits>()

const pageInput = ref('')
const percentInput = ref('')
const pageFieldRef = ref<HTMLInputElement | null>(null)

/** 有效页数下限 1：对话框只在 totalPages ≥ 1 时被打开，这里再兜一层。 */
const safeTotal = computed(() => Math.max(1, props.totalPages))

const displayCurrent = computed(() => Math.min(Math.max(1, props.currentPage), safeTotal.value))

/** 全数字整数（页号合法域）；空串/带符号/小数/其他字符一律非法。 */
function parsePageIndex(raw: string): number | null {
  const text = raw.trim()
  if (!/^\d+$/.test(text)) return null
  return Number(text)
}

/** 百分比：非负十进制数（支持小数）；空串 = 未填写（可选字段）。 */
function parsePercent(raw: string): number | null | undefined {
  const text = raw.trim()
  if (text === '') return undefined
  if (!/^\d+(\.\d+)?$/.test(text)) return null
  return Number(text)
}

/** 百分比 → 页号：0% 首页、100% 末页，四舍五入后钳入 [1, total]。 */
function pageFromPercent(percent: number): number {
  const clamped = Math.min(100, Math.max(0, percent))
  return Math.min(safeTotal.value, Math.max(1, Math.round((clamped / 100) * safeTotal.value)))
}

/**
 * 确认目标：页号字段恒为权威（联动保证与百分比一致）。非法（含百分比
 * 填了但写坏）→ null，确定钮禁用 + confirm 早退。
 */
const targetPage = computed<number | null>(() => {
  const page = parsePageIndex(pageInput.value)
  if (page === null) return null
  const percent = parsePercent(percentInput.value)
  if (percent === null) return null
  if (percent !== undefined) return pageFromPercent(percent)
  return Math.min(safeTotal.value, Math.max(1, page))
})

const valid = computed(() => targetPage.value !== null)

/** 页号 → 百分比回显（一位小数，整值去尾 .0）。 */
function percentTextFromPage(page: number): string {
  const pct = (page / safeTotal.value) * 100
  const rounded = Math.round(pct * 10) / 10
  return String(rounded)
}

function onPageInput(): void {
  const page = parsePageIndex(pageInput.value)
  if (page !== null) percentInput.value = percentTextFromPage(Math.min(safeTotal.value, Math.max(1, page)))
}

function onPercentInput(): void {
  const percent = parsePercent(percentInput.value)
  if (percent !== null && percent !== undefined) {
    pageInput.value = String(pageFromPercent(percent))
  }
}

function confirm(): void {
  const target = targetPage.value
  if (target === null) return // 非法输入不跳（按钮已禁用，这里再兜一层）
  emit('jump', target)
}

function onEscape(): void {
  emit('close')
}

// 每次打开都回到当前页的干净初始态，并把焦点放进页号输入框（全选便于
// 直接覆写；Esc/Enter 语义随焦点就位）。
watch(
  () => props.visible,
  (visible) => {
    if (!visible) return
    pageInput.value = String(displayCurrent.value)
    percentInput.value = ''
    void nextTick(() => {
      pageFieldRef.value?.focus()
      pageFieldRef.value?.select()
    })
  },
  { immediate: true },
)
</script>

<style scoped>
.page-jump {
  position: absolute;
  inset: 0;
  z-index: 50; /* 与 ReaderSettings 同层：chrome 之上、亮度遮罩之下 */
  display: grid;
  place-items: center;
}

.page-jump__scrim {
  position: absolute;
  inset: 0;
  background: color-mix(in srgb, var(--color-black) 50%, transparent);
}

.page-jump__card {
  position: relative;
  width: min(320px, calc(100vw - 48px));
  padding: 20px;
  border-radius: 12px;
  background: var(--grey-900);
  box-shadow: 0 8px 28px color-mix(in srgb, var(--color-black) 55%, transparent);
}

.page-jump__title {
  margin: 0;
  color: var(--grey-100);
  font-size: var(--text-little-large); /* 20sp */
  font-weight: 500;
}

.page-jump__meta {
  margin: 4px 0 14px;
  color: var(--grey-500);
  font-size: var(--text-super-small); /* 12sp */
  font-variant-numeric: tabular-nums;
}

.page-jump__field {
  display: block;
  margin-bottom: 12px;
}

.page-jump__field-label {
  display: block;
  margin-bottom: 4px;
  color: var(--grey-400);
  font-size: var(--text-super-small); /* 12sp */
}

.page-jump__input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--grey-700);
  border-radius: var(--card-radius); /* 2dp */
  background: var(--grey-850);
  color: var(--color-white);
  font-size: var(--text-medium); /* 18sp */
  font-variant-numeric: tabular-nums;
}

.page-jump__input:focus {
  outline: none;
  border-color: var(--color-primary);
}

.page-jump__input:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}

.page-jump__error {
  margin: 0 0 10px;
  color: var(--color-accent, #e2574c);
  font-size: var(--text-super-small);
}

.page-jump__actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 6px;
}

.page-jump__btn {
  padding: 9px 18px;
  border: none;
  border-radius: var(--card-radius);
  background: transparent;
  color: var(--grey-300);
  font-size: var(--text-small); /* 14sp */
  cursor: pointer;
  transition:
    background 150ms var(--ease-decelerate-quart),
    color 150ms var(--ease-decelerate-quart);
}

.page-jump__btn:hover {
  background: color-mix(in srgb, var(--color-white) 8%, transparent);
  color: var(--grey-100);
}

.page-jump__btn--primary {
  background: var(--color-primary);
  color: var(--color-white);
  font-weight: 500;
}

.page-jump__btn--primary:hover {
  background: var(--color-primary);
  filter: brightness(1.1);
  color: var(--color-white);
}

.page-jump__btn--primary:disabled {
  opacity: 0.4;
  cursor: default;
  filter: none;
}

.page-jump__btn:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}

/* --- Sheet-style fade/scale（比照 reader-settings 的入场） ---------------- */

.page-jump-enter-active,
.page-jump-leave-active {
  transition: opacity var(--duration-scene-opacity) var(--ease-decelerate-quart);
}

.page-jump-enter-active .page-jump__card,
.page-jump-leave-active .page-jump__card {
  transition: transform var(--duration-scene-translate) var(--ease-decelerate-quint);
}

.page-jump-enter-from,
.page-jump-leave-to {
  opacity: 0;
}

.page-jump-enter-from .page-jump__card,
.page-jump-leave-to .page-jump__card {
  transform: scale(0.92);
}

@media (prefers-reduced-motion: reduce) {
  .page-jump-enter-active,
  .page-jump-leave-active,
  .page-jump-enter-active .page-jump__card,
  .page-jump-leave-active .page-jump__card {
    transition-duration: 1ms;
  }
}
</style>
