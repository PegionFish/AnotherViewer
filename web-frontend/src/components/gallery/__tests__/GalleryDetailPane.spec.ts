/**
 * GalleryDetailPane — 抽取自 GalleryDetailView 的详情主体（T3 平板对齐）。
 * 本 spec 只锁双宿主契约层：gid/token prop 装载、pane 形态类、back 事件
 * （头部返回箭头不再自带导航语义，由宿主决定）、gid 变化原位重载。
 * 场景交互（下载/收藏/评论/分享/打码）以 GalleryDetailView.spec 为准——
 * 它透过整页宿主驱动同一 pane 实现。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import GalleryDetailPane from '../GalleryDetailPane.vue'
import { galleryApi } from '@/api/gallery'
import { commentApi } from '@/api/comment'
import { preferencesApi } from '@/api/preferences'
import type { GalleryDetail } from '@/types'

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
}))

vi.mock('@/api/gallery', () => ({
  galleryApi: { getDetail: vi.fn() },
}))

vi.mock('@/api/comment', () => ({
  commentApi: { listComments: vi.fn(), postComment: vi.fn(), voteComment: vi.fn() },
}))

vi.mock('@/api/favorite', () => ({
  favoriteApi: { addFavorite: vi.fn(), removeFavorite: vi.fn() },
}))

vi.mock('@/api/download', () => ({
  downloadApi: { add: vi.fn() },
}))

vi.mock('@/api/preferences', () => ({
  preferencesApi: { get: vi.fn(), update: vi.fn() },
}))

vi.mock('@/api/site', () => ({
  siteApi: { getAvailability: vi.fn(), probeAvailability: vi.fn() },
}))

function makeDetail(overrides: Partial<GalleryDetail> = {}): GalleryDetail {
  return {
    gid: 42,
    token: 'tok42',
    title: 'Detail Gallery',
    titleJpn: '',
    thumb: '',
    category: 2,
    posted: '',
    uploader: '',
    rating: 4,
    rated: false,
    simpleLanguage: '',
    simpleTags: [],
    thumbWidth: 0,
    thumbHeight: 0,
    pages: 10,
    favoriteSlot: -2,
    favoriteName: '',
    tags: [],
    imageUrl: '',
    ...overrides,
  }
}

async function mountPane(props: Record<string, unknown> = {}): Promise<VueWrapper> {
  vi.mocked(galleryApi.getDetail).mockResolvedValue(makeDetail())
  vi.mocked(commentApi.listComments).mockResolvedValue({ comments: [] })
  const wrapper = mount(GalleryDetailPane, { props: { gid: '42', ...props } })
  await flushPromises()
  await flushPromises()
  return wrapper
}

describe('GalleryDetailPane（双宿主契约，T3）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    // prefs 未加载态（防闪没兜底）——偏好请求永不落定。
    vi.mocked(preferencesApi.get).mockReturnValue(new Promise(() => {}))
    pushMock.mockClear()
  })

  afterEach(() => {
    wrapper?.unmount()
    vi.clearAllMocks()
  })

  let wrapper: VueWrapper | undefined

  it('按 gid prop 装载详情（无 token）', async () => {
    wrapper = await mountPane()
    expect(galleryApi.getDetail).toHaveBeenCalledWith(42, undefined)
    expect(wrapper.find('.detail-header__title').text()).toBe('Detail Gallery')
  })

  it('token prop 透传给 getDetail（整页深链 ?token= / 双栏行内 token）', async () => {
    wrapper = await mountPane({ token: 'tokEntry' })
    expect(galleryApi.getDetail).toHaveBeenCalledWith(42, 'tokEntry')
  })

  it('pane 形态挂 modifier 类；默认形态不带', async () => {
    wrapper = await mountPane({ pane: true })
    expect(wrapper.find('.gallery-detail-pane--pane').exists()).toBe(true)

    const plain = await mountPane()
    expect(plain.find('.gallery-detail-pane--pane').exists()).toBe(false)
    await plain.unmount()
  })

  it('头部返回箭头不再导航，只 emit back（宿主决定语义）', async () => {
    wrapper = await mountPane()
    await wrapper.find('.detail-header__back').trigger('click')
    expect(wrapper.emitted('back')).toHaveLength(1)
    expect(pushMock).not.toHaveBeenCalled()
  })

  it('gid prop 变化：原位 reset + 重载（双栏选中切换不重挂）', async () => {
    wrapper = await mountPane({ gid: 42 })
    expect(wrapper.find('.detail-header__title').text()).toBe('Detail Gallery')
    expect(galleryApi.getDetail).toHaveBeenCalledTimes(1)

    vi.mocked(galleryApi.getDetail).mockResolvedValue(makeDetail({ gid: 77, title: 'Second' }))
    await wrapper.setProps({ gid: 77 })
    // reset 语义：旧内容先落回加载态。
    expect(wrapper.find('.gallery-detail__state').exists()).toBe(true)

    await flushPromises()
    await flushPromises()
    expect(wrapper.find('.detail-header__title').text()).toBe('Second')
    expect(galleryApi.getDetail).toHaveBeenLastCalledWith(77, undefined)
  })

  it('同值 gid prop 不触发重载（再点同项无闪烁）', async () => {
    wrapper = await mountPane({ gid: 42 })
    expect(galleryApi.getDetail).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ gid: 42 })
    await flushPromises()
    expect(galleryApi.getDetail).toHaveBeenCalledTimes(1)
  })

  it('Read 按钮仍导航到统一阅读器（阅读器绝不进双栏）', async () => {
    wrapper = await mountPane()
    await wrapper.find('.detail-actions__btn--read').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ path: '/reader/42', query: { token: 'tok42' } })
  })
})
