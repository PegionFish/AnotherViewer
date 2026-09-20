import { describe, it, expect, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import PageJumpDialog from '../PageJumpDialog.vue'

/**
 * A5 跳页对话框组件级 spec：页号 1-based、越界钳制、非法输入不跳、
 * 百分比联动、Esc 关闭。父级（ImageReader）侧的 G 键触发与 seek 通路
 * 复用在 ImageReader.spec.ts。
 */

function factory(props: Partial<InstanceProps> = {}, attach = false): VueWrapper {
  return mount(PageJumpDialog, {
    props: {
      visible: true,
      currentPage: 3,
      totalPages: 10,
      ...props,
    },
    // happy-dom 的 focus() 只对已接入文档的元素生效（VTU 默认脱离文档挂载）。
    attachTo: attach ? document.body : undefined,
  })
}

interface InstanceProps {
  visible: boolean
  currentPage: number
  totalPages: number
}

const pageField = (w: VueWrapper) => w.find<HTMLInputElement>('.page-jump__field input')
const percentField = (w: VueWrapper) => w.find<HTMLInputElement>('.page-jump__field:nth-of-type(2) input')
const confirmBtn = (w: VueWrapper) => w.find('.page-jump__btn--primary')

async function typePage(w: VueWrapper, value: string): Promise<void> {
  pageField(w).element.value = value
  await pageField(w).trigger('input')
}

async function typePercent(w: VueWrapper, value: string): Promise<void> {
  percentField(w).element.value = value
  await percentField(w).trigger('input')
}

describe('PageJumpDialog (A5) — 打开与初始态', () => {
  let wrapper: VueWrapper | undefined

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    document.body.innerHTML = ''
  })

  it('does not render while visible=false', () => {
    wrapper = factory({ visible: false })
    expect(wrapper.find('.page-jump').exists()).toBe(false)
  })

  it('prefills the page field with the clamped current page on open', async () => {
    wrapper = factory({ visible: false, currentPage: 4 })
    expect(wrapper.find('.page-jump').exists()).toBe(false) // 关闭态不渲染

    await wrapper.setProps({ visible: true })
    await wrapper.vm.$nextTick()
    expect(pageField(wrapper).element.value).toBe('4')
    expect(percentField(wrapper).element.value).toBe('') // 百分比可选，默认空
    // 确定钮可用（当前页本身合法），但尚未发生任何跳转。
    expect(confirmBtn(wrapper).attributes('disabled')).toBeUndefined()
    expect(wrapper.emitted('jump')).toBeUndefined()
  })

  it('focuses the page field when opened (Esc/Enter 语义随焦点就位)', async () => {
    wrapper = factory({ visible: false }, true)
    await wrapper.setProps({ visible: true })
    await wrapper.vm.$nextTick()
    expect(document.activeElement).toBe(pageField(wrapper).element)
  })
})

describe('PageJumpDialog (A5) — 页号校验：clamp 与非法输入', () => {
  let wrapper: VueWrapper | undefined

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    document.body.innerHTML = ''
  })

  it('emits the 1-based page on confirm', async () => {
    wrapper = factory()
    await typePage(wrapper, '7')
    await confirmBtn(wrapper).trigger('submit')
    expect(wrapper.emitted('jump')).toEqual([[7]])
  })

  it('clamps an out-of-range page into [1, totalPages]', async () => {
    wrapper = factory()
    await typePage(wrapper, '999')
    await confirmBtn(wrapper).trigger('submit')
    expect(wrapper.emitted('jump')).toEqual([[10]])

    await typePage(wrapper, '0')
    await confirmBtn(wrapper).trigger('submit')
    expect(wrapper.emitted('jump')).toEqual([[10], [1]])

    await typePage(wrapper, '-3') // 带符号非法 → 不跳
    expect(confirmBtn(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.emitted('jump')).toEqual([[10], [1]])
  })

  it('refuses invalid input: no jump, button disabled, dialog stays open', async () => {
    wrapper = factory()
    for (const bad of ['abc', '', ' ', '1.5', '1e2']) {
      await typePage(wrapper, bad)
      expect(confirmBtn(wrapper).attributes('disabled')).toBeDefined()
      await confirmBtn(wrapper).trigger('submit')
      expect(wrapper.emitted('jump')).toBeUndefined()
      expect(wrapper.emitted('close')).toBeUndefined()
    }
  })

  it('guards confirm() directly even when invoked with invalid state', async () => {
    wrapper = factory()
    await typePage(wrapper, 'xyz')
    await wrapper.find('form').trigger('submit')
    expect(wrapper.emitted('jump')).toBeUndefined()
  })
})

describe('PageJumpDialog (A5) — 百分比联动', () => {
  let wrapper: VueWrapper | undefined

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    document.body.innerHTML = ''
  })

  it('mirrors page → percent and percent → page', async () => {
    wrapper = factory()
    await typePage(wrapper, '5')
    expect(percentField(wrapper).element.value).toBe('50')

    await typePercent(wrapper, '25')
    expect(pageField(wrapper).element.value).toBe('3') // round(0.25*10)
  })

  it('jumps by percentage: 50% of 10 → page 5, 100% → last page', async () => {
    wrapper = factory()
    await typePercent(wrapper, '50')
    await confirmBtn(wrapper).trigger('submit')
    expect(wrapper.emitted('jump')).toEqual([[5]])

    await typePercent(wrapper, '100')
    await confirmBtn(wrapper).trigger('submit')
    expect(wrapper.emitted('jump')).toEqual([[5], [10]])
  })

  it('clamps percent overshoot (250% → last page) and rounds fractions', async () => {
    wrapper = factory()
    await typePercent(wrapper, '250')
    expect(pageField(wrapper).element.value).toBe('10')
    await confirmBtn(wrapper).trigger('submit')
    expect(wrapper.emitted('jump')).toEqual([[10]])

    // 37.5% of 50 → round(18.75) = 19（小数百分比合法）。
    await wrapper.setProps({ totalPages: 50 })
    await typePercent(wrapper, '37.5')
    expect(pageField(wrapper).element.value).toBe('19')
    await confirmBtn(wrapper).trigger('submit')
    expect(wrapper.emitted('jump')).toEqual([[10], [19]])
  })

  it('blocks the jump while the percent field holds garbage (explicit but unparseable)', async () => {
    wrapper = factory()
    await typePage(wrapper, '5')
    await typePercent(wrapper, '2x')
    expect(confirmBtn(wrapper).attributes('disabled')).toBeDefined()
    await confirmBtn(wrapper).trigger('submit')
    expect(wrapper.emitted('jump')).toBeUndefined()
  })
})

describe('PageJumpDialog (A5) — 关闭语义', () => {
  let wrapper: VueWrapper | undefined

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    document.body.innerHTML = ''
  })

  it('closes on Escape (keydown bubbles from the page input to the root)', async () => {
    wrapper = factory()
    await typePage(wrapper, '5')
    await pageField(wrapper).trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
    // Esc 只关闭、不跳转。
    expect(wrapper.emitted('jump')).toBeUndefined()
  })

  it('closes via the scrim and the cancel button without jumping', async () => {
    wrapper = factory()
    await wrapper.find('.page-jump__scrim').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)

    await wrapper.find('.page-jump__btn:not(.page-jump__btn--primary)').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(2)
    expect(wrapper.emitted('jump')).toBeUndefined()
  })

  it('resets the fields to the current page on reopen', async () => {
    wrapper = factory({ currentPage: 2 })
    await typePage(wrapper, '9')
    await typePercent(wrapper, '90')

    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true, currentPage: 6 })
    await wrapper.vm.$nextTick()
    expect(pageField(wrapper).element.value).toBe('6')
    expect(percentField(wrapper).element.value).toBe('')
  })
})
