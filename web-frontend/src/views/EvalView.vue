<template>
  <div class="eval-view">
    <EvalToolbar
      :ratio="ratio"
      :orientation="orientation"
      :scale-mode="scaleMode"
      :locked="locked"
      @update:ratio="setRatio"
      @update:orientation="setOrientation"
      @update:scaleMode="setScaleMode"
      @toggle-lock="toggleLock"
      @fullscreen="enterFullscreen"
      @open-new-tab="openInNewTab"
      @exit="exitEval"
    />

    <div class="eval-stage">
      <div ref="frameRef" class="eval-frame" :style="frameStyle">
        <iframe
          ref="iframeRef"
          class="eval-frame__surface"
          :src="'/'"
          :width="width"
          :height="height"
          allow="fullscreen"
        />
      </div>
      <p class="eval-stage__badge" role="status">{{ badgeText }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * EvalView (W2 / C8) — 评估台：在固定比例的取景框里以整页方式检查外壳
 * 自身的响应式布局。
 *
 * - 取景框状态全部来自 useViewportFrame（width/height/scale/locked 等），
 *   进入时以 route.query 为初始值，此后任何状态变化 router.replace 写回
 *   URL（双向：刷新/分享可还原 4×2×2×2 组合）。
 * - iframe `:src="'/'"` 静态不变——外壳与 iframe 同源共享 localStorage 登录
 *   态，比例/缩放只改容器几何（px + transform），绝不触发 iframe 重载；
 *   不加 sandbox（需登录态与全屏），`allow="fullscreen"`。
 * - 全屏 / 屏幕方向锁定均为 best-effort：reject 或不支持时静默降级，
 *   不弹错误。
 * - 路由不进 KeepAlive（CACHED_VIEWS 不含本视图，缺省即生效）。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import EvalToolbar from '@/components/eval/EvalToolbar.vue'
import { parseFrameQuery, useViewportFrame } from '@/composables/useViewportFrame'

const route = useRoute()
const router = useRouter()

const {
  ratio,
  orientation,
  scaleMode,
  locked,
  width,
  height,
  scale,
  setRatio,
  setOrientation,
  setScaleMode,
  toggleLock,
  toQuery,
} = useViewportFrame(parseFrameQuery(route.query))

// 状态 → URL：toQuery() 只依赖四个状态 ref，任一变化即整体 replace。
// 不 immediate——进入时状态本就来自 query，无需回写。
watch(toQuery, (query) => {
  void router.replace({ query })
})

const frameRef = ref<HTMLElement | null>(null)
const iframeRef = ref<HTMLIFrameElement | null>(null)

// 取景框几何：布局 px 定宽高，整体 transform 缩放（origin top center，
// fit 档下缩放后恰好铺满工具条以下的评估区，视觉水平垂直皆居中）。
const frameStyle = computed(() => ({
  width: `${width.value}px`,
  height: `${height.value}px`,
  transform: `scale(${scale.value})`,
}))

const badgeText = computed(() => {
  const orientText = orientation.value === 'landscape' ? '横屏' : '竖屏'
  const scaleText = scaleMode.value === '100' ? '100%' : `适应 ${Math.round(scale.value * 100)}%`
  return `${width.value}×${height.value} · ${ratio.value} · ${orientText} · ${scaleText}`
})

/** 全屏 + 屏幕方向锁定，两步都 best-effort、失败静默降级。 */
async function enterFullscreen(): Promise<void> {
  const el = frameRef.value
  try {
    if (el && typeof el.requestFullscreen === 'function') {
      await el.requestFullscreen()
    }
  } catch {
    /* 全屏被拒/不支持（如非用户手势）——静默降级 */
  }
  try {
    const orientationLike = screen.orientation as
      | (ScreenOrientation & { lock?: (o: string) => Promise<void> })
      | undefined
    if (typeof orientationLike?.lock === 'function') {
      await orientationLike.lock(orientation.value === 'landscape' ? 'landscape' : 'portrait')
    }
  } catch {
    /* 方向锁定不支持/被拒（桌面端常态）——静默降级 */
  }
}

/**
 * 新标签打开 iframe 当前页面：同源部署下直读 iframe 的 location（路径
 * + 查询串），读不到（跨域/未加载）回退根路径 '/'。
 */
function openInNewTab(): void {
  let target = '/'
  try {
    const location = iframeRef.value?.contentWindow?.location
    const path = location?.pathname ?? ''
    if (path && path !== 'about:blank') {
      target = path + (location?.search ?? '')
    }
  } catch {
    /* 取不到真实路径——兜底根路径 */
  }
  window.open(target)
}

/** 退出评估台：有历史则返回上一页，否则回首页。 */
function exitEval(): void {
  const position = (window.history.state as { position?: number } | null)?.position ?? 0
  if (position > 0) {
    router.back()
  } else {
    router.push('/')
  }
}
</script>

<style scoped>
.eval-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

/* 评估区：恒暗色（与主题无关），取景框在其内绝对定位、水平居中。 */
.eval-stage {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
  background: var(--color-black);
}

.eval-frame {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  margin: 0 auto; /* 左右绝对居中；垂直方向 origin top center + fit 档恰好铺满 */
  transform-origin: top center;
  background: var(--color-black);
}

.eval-frame__surface {
  display: block;
  border: 0;
  background: var(--color-white);
}

/* 帧信息角标：悬浮于评估区左上角（容器外层，不随取景框缩放）。 */
.eval-stage__badge {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 2;
  margin: 0;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.65);
  color: rgba(255, 255, 255, 0.85);
  font-size: var(--text-small);
  white-space: nowrap;
  pointer-events: none;
}
</style>
