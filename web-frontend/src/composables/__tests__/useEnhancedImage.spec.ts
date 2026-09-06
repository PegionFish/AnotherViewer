import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref } from 'vue'
import { useEnhancedImage } from '../useEnhancedImage'

const { wsConnect, wsDisconnect, wsSubscribe, wsUnsubscribe } = vi.hoisted(() => ({
  wsConnect: vi.fn(),
  wsDisconnect: vi.fn(),
  wsSubscribe: vi.fn(),
  wsUnsubscribe: vi.fn(),
}))

vi.mock('@/composables/useWebSocket', () => ({
  useWebSocket: () => ({ connect: wsConnect, disconnect: wsDisconnect, subscribe: wsSubscribe }),
}))

/** Image double capturing preload outcomes for manual resolution. */
class FakeImage {
  static instances: FakeImage[] = []
  src = ''
  onload: (() => void) | null = null
  onerror: (() => void) | null = null

  constructor() {
    FakeImage.instances.push(this)
  }

  succeed() {
    this.onload?.()
  }

  fail() {
    this.onerror?.()
  }
}

/** Host exposing the composable bound to a changeable gid ref. */
function mountEnhanced(initialGid: number) {
  const gid = ref(initialGid)
  let api!: ReturnType<typeof useEnhancedImage>
  const Host = defineComponent({
    setup() {
      api = useEnhancedImage(gid)
      return () => h('div')
    },
  })
  const wrapper = mount(Host)
  return { wrapper, gid, api }
}

/** The handler registered for the most recent subscribe() call. */
function lastHandler(): (envelope: { type: string; payload: unknown }) => void {
  const call = wsSubscribe.mock.calls.at(-1)
  return call![1] as (envelope: { type: string; payload: unknown }) => void
}

function readyPayload(overrides: Record<string, unknown> = {}) {
  return {
    type: 'image.enhanced.ready',
    payload: {
      galleryId: 7,
      page: 3, // 1-based per WS protocol
      enhancedUrl: 'https://cdn.example/enhanced-3.jpg',
      originalUrl: '/api/v1/image/7/2',
      processingType: 'UPSCALE',
      width: 800,
      height: 1200,
      fileSize: 12345,
      ...overrides,
    },
  }
}

describe('useEnhancedImage (T-F2)', () => {
  beforeEach(() => {
    wsConnect.mockClear()
    wsDisconnect.mockClear()
    wsSubscribe.mockClear()
    wsSubscribe.mockImplementation(() => wsUnsubscribe)
    wsUnsubscribe.mockClear()
    FakeImage.instances = []
    vi.stubGlobal('Image', FakeImage)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('connect acquires the shared socket and subscribes to the gallery topic once', () => {
    const { api } = mountEnhanced(7)

    expect(api.enhancing.value).toBe(false)

    api.connect()
    api.connect() // idempotent — one reference, one subscription
    expect(wsConnect).toHaveBeenCalledTimes(1)
    expect(wsSubscribe).toHaveBeenCalledTimes(1)
    expect(wsSubscribe).toHaveBeenCalledWith('/topic/gallery/7/enhanced', expect.any(Function))
  })

  it('hot-swaps a page only after its preload succeeds (1-based → 0-based)', async () => {
    const { api } = mountEnhanced(7)
    api.connect()

    lastHandler()(readyPayload())
    // Preload in flight → enhancing flag on, map untouched.
    expect(api.enhancing.value).toBe(true)
    expect(api.getImageUrl(2, 'original.jpg')).toBe('original.jpg')

    const img = FakeImage.instances.at(-1)!
    expect(img.src).toBe('/api/v1/image/7/2?w=800&enhanced=1')
    img.succeed()
    await Promise.resolve()

    expect(api.getImageUrl(2, 'original.jpg')).toBe('/api/v1/image/7/2?w=800&enhanced=1')
    expect(api.enhancing.value).toBe(false)
    expect(api.enhancedPages.value).toEqual([2])
  })

  it('silently keeps the original when the preload fails', async () => {
    const { api } = mountEnhanced(7)
    api.connect()

    lastHandler()(readyPayload({ page: 5 }))
    FakeImage.instances.at(-1)!.fail()
    await Promise.resolve()

    expect(api.getImageUrl(4, 'original.jpg')).toBe('original.jpg')
    expect(api.enhancedPages.value).toEqual([])
    // Preload accounting unwinds even on failure.
    expect(api.enhancing.value).toBe(false)
  })

  it('ignores envelopes that are not image.enhanced.ready', () => {
    const { api } = mountEnhanced(7)
    api.connect()

    lastHandler()({ type: 'job.progress', payload: {} })
    expect(FakeImage.instances).toHaveLength(0)
    expect(api.enhancing.value).toBe(false)
  })

  it('re-subscribes and clears cached URLs when the gid changes', async () => {
    const { api, gid } = mountEnhanced(7)
    api.connect()
    lastHandler()(readyPayload())
    FakeImage.instances.at(-1)!.succeed()
    expect(api.getImageUrl(2, 'x')).not.toBe('x')

    gid.value = 9
    await nextTick() // pre-flush watcher runs on the tick boundary
    // Old topic released (the watch + re-subscribe path releases the same
    // handle twice — a registry-miss no-op in useWebSocket, hence ≥1).
    expect(wsUnsubscribe.mock.calls.length).toBeGreaterThanOrEqual(1)
    expect(api.getImageUrl(2, 'x')).toBe('x') // cache cleared
    expect(wsSubscribe).toHaveBeenLastCalledWith('/topic/gallery/9/enhanced', expect.any(Function))
  })

  it('drops an in-flight preload that resolves after the gid changed (no map pollution)', async () => {
    const { api, gid } = mountEnhanced(7)
    api.connect()

    // Gallery 7 preload for page 3 (1-based) → index 2, still in flight.
    lastHandler()(readyPayload())
    const staleImg = FakeImage.instances.at(-1)!
    expect(api.enhancing.value).toBe(true)

    // Switch galleries while the preload is in flight → resetState runs.
    gid.value = 9
    await nextTick()
    expect(api.getImageUrl(2, 'x')).toBe('x') // map was cleared

    // Gallery 9 starts its own preload while the stale one is still pending.
    lastHandler()(readyPayload({ galleryId: 9, page: 1 }))
    const freshImg = FakeImage.instances.at(-1)!
    expect(freshImg.src).toBe('/api/v1/image/9/0?w=800&enhanced=1')

    // Stale preload resolves after the switch — must not write the new
    // gallery's map (audit P1-4).
    staleImg.succeed()
    await Promise.resolve()
    expect(api.getImageUrl(2, 'x')).toBe('x')
    expect(api.enhancedPages.value).toEqual([])

    // Fresh preload is unaffected: `enhancing` was not prematurely cleared by
    // the dropped stale callback, and its own hot-swap still lands.
    expect(api.enhancing.value).toBe(true)
    freshImg.succeed()
    await Promise.resolve()
    expect(api.getImageUrl(0, 'x')).toBe('/api/v1/image/9/0?w=800&enhanced=1')
    expect(api.enhancing.value).toBe(false)
    expect(api.enhancedPages.value).toEqual([0])
  })

  it('drops an in-flight preload that fails after the gid changed (accounting intact)', async () => {
    const { api, gid } = mountEnhanced(7)
    api.connect()

    lastHandler()(readyPayload())
    const staleImg = FakeImage.instances.at(-1)!

    gid.value = 9
    await nextTick()

    staleImg.fail()
    await Promise.resolve()
    expect(api.enhancedPages.value).toEqual([])

    // The stale failure must not corrupt the new gallery's preload counter.
    lastHandler()(readyPayload({ galleryId: 9, page: 2 }))
    expect(api.enhancing.value).toBe(true)
    FakeImage.instances.at(-1)!.succeed()
    await Promise.resolve()
    expect(api.getImageUrl(1, 'x')).toBe('/api/v1/image/9/1?w=800&enhanced=1')
    expect(api.enhancing.value).toBe(false)
  })

  it('disconnect releases the reference, unsubscribes and resets state; repeat calls are safe', async () => {
    const { api } = mountEnhanced(7)
    api.connect()
    lastHandler()(readyPayload())
    FakeImage.instances.at(-1)!.succeed()

    api.disconnect()
    api.disconnect()
    expect(wsUnsubscribe).toHaveBeenCalled()
    expect(wsDisconnect).toHaveBeenCalledTimes(1) // single release despite double call
    expect(api.getImageUrl(2, 'fallback')).toBe('fallback')
    expect(api.enhancing.value).toBe(false)
    expect(api.enhancedPages.value).toEqual([])

    // Reconnecting after a disconnect re-acquires + re-subscribes cleanly.
    api.connect()
    expect(wsConnect).toHaveBeenCalledTimes(2)
    expect(wsSubscribe).toHaveBeenCalledTimes(2)
  })

  it('无 serverBase：增强图 URL 与旧字面量逐字节一致（零回归锚点，PWA C2）', () => {
    localStorage.removeItem('server-base')
    const { api } = mountEnhanced(7)
    api.connect()

    lastHandler()(readyPayload())
    expect(FakeImage.instances.at(-1)!.src).toBe('/api/v1/image/7/2?w=800&enhanced=1')
  })

  it('配置 serverBase：增强图 URL 带 `${base}/api/v1` 绝对前缀（PWA C2）', () => {
    localStorage.setItem('server-base', 'http://x:1')
    try {
      const { api } = mountEnhanced(7)
      api.connect()

      lastHandler()(readyPayload())
      expect(FakeImage.instances.at(-1)!.src).toBe('http://x:1/api/v1/image/7/2?w=800&enhanced=1')
    } finally {
      localStorage.removeItem('server-base')
    }
  })
})
