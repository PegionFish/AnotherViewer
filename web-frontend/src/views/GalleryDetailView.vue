<template>
  <div class="gallery-detail">
    <GalleryDetailPane :gid="gid" :token="entryToken" @back="goBack" />
  </div>
</template>

<script setup lang="ts">
/**
 * GalleryDetailView — S2 整页详情宿主（`/gallery/:gid` 深链），web replica of
 * Android `GalleryDetailScene`。
 *
 * T3（平板对齐）抽取：详情主体（色带头部 / 操作卡 / 标签 / 评论 / 场景四态
 * 与 toast）全部移入共享 `GalleryDetailPane`（`@/components/gallery/
 * GalleryDetailPane.vue`），双栏右栏复用同一场景。本视图只剩宿主职责：
 *
 *  - 路由参数 `gid` 与 `?token=` 深链透传（`props: true` + route.query）；
 *  - 头部返回箭头的整页语义：`history.state.back` 存在时 router.back()，
 *    深链直达（无上一页）兜底回首页（plan-2026-09-05 C5）。
 *
 * 场景结构与交互以 GalleryDetailPane 为唯一实现源；样式壳只保留整页视口
 * 高度与 PWA 安全区内边距。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import GalleryDetailPane from '@/components/gallery/GalleryDetailPane.vue'

/** Gallery id from the `/gallery/:gid` route (router `props: true`). */
defineProps<{
  gid: string | number
}>()

const router = useRouter()

/** Token from the entry link (?token=): lets the backend fetch the detail
 *  straight from the site when the gid is not in local history. */
const route = useRoute()
const entryToken = computed(() =>
  typeof route.query.token === 'string' ? route.query.token : undefined,
)

/** 返回：深链直达（history 无上一页，`back` 为空）时 router.back() 是
 *  no-op——兜底回首页，按钮不再无响应（plan-2026-09-05 C5）。 */
function goBack() {
  if (history.state?.back) {
    router.back()
  } else {
    void router.push('/')
  }
}
</script>

<style scoped>
.gallery-detail {
  min-height: 100vh;
  /* Standalone PWA: the whole document clears the status bar / notch at the
     top (header row + detail band shift down together) and the home
     indicator at the bottom (comment post box at the document end keeps its
     clearance). Both resolve to 0 on devices without cutouts. */
  padding: var(--safe-area-top) 0 var(--safe-area-bottom);
  background-color: var(--color-bg);
  color: var(--text-color-primary);
}
</style>
