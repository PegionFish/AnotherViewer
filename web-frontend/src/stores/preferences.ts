import { defineStore } from 'pinia'
import { ref } from 'vue'
import { preferencesApi, type Preferences, type GeneralPreferences, type ReaderPreferences, type PrivacyPreferences } from '@/api/preferences'
// 循环依赖说明：theme.ts 也 import 本模块（setTheme 写穿偏好）。两边都只在
// 函数体内调用对方的 useXxxStore()，模块顶层不触碰对方导出——ESM 循环加载
// 下安全（见 theme.ts 顶部同款注释）。
import { useThemeStore, isTheme } from '@/stores/theme'

export const usePreferencesStore = defineStore('preferences', () => {
  const prefs = ref<Preferences | null>(null)
  const loading = ref(false)
  const loadError = ref(false)
  const saveSeq = ref(0)
  const saveError = ref<string | null>(null)

  let saveTimer: ReturnType<typeof setTimeout> | null = null

  let loadSeq = 0
  let dirty = false

  async function load() {
    const seq = ++loadSeq
    loading.value = true
    loadError.value = false
    try {
      const next = await preferencesApi.get()
      if (seq !== loadSeq || dirty) return
      prefs.value = next
      // T3-1 主题回灌：服务器 theme 是唯一权威，加载成功后写回 themeStore
      //（连带 localStorage / data-theme）。仅当值合法且与本地不同才 setTheme；
      // setTheme 的写穿守卫看到「偏好值 === 回灌值」会跳过 updateGeneral，
      // 所以这里不会形成 load → setTheme → PUT 的回环。
      const serverTheme = next.general?.theme
      if (isTheme(serverTheme)) {
        const themeStore = useThemeStore()
        if (themeStore.currentTheme !== serverTheme) themeStore.setTheme(serverTheme)
      }
    } catch (e) {
      if (seq !== loadSeq) return
      loadError.value = true
      console.error('Failed to load preferences', e)
    } finally {
      if (seq === loadSeq) loading.value = false
    }
  }

  function updateGeneral(patch: Partial<GeneralPreferences>) {
    if (!prefs.value) return
    prefs.value.general = { ...prefs.value.general, ...patch }
    dirty = true
    scheduleSave({ general: prefs.value.general })
  }

  function updateReader(patch: Partial<ReaderPreferences>) {
    if (!prefs.value) return
    prefs.value.reader = { ...prefs.value.reader, ...patch }
    dirty = true
    scheduleSave({ reader: prefs.value.reader })
  }

  function updatePrivacy(patch: Partial<PrivacyPreferences>) {
    if (!prefs.value) return
    prefs.value.privacy = { ...prefs.value.privacy, ...patch }
    dirty = true
    scheduleSave({ privacy: prefs.value.privacy })
  }

  function scheduleSave(payload: Partial<Preferences>) {
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(async () => {
      try {
        await preferencesApi.update(payload)
        saveError.value = null
        saveSeq.value += 1
        dirty = false
      } catch (e) {
        saveError.value = e instanceof Error ? e.message : String(e)
        console.error('Failed to save preferences', e)
        // audit P2：保存失败后 dirty 永真会把后续所有 load() 静默挡在门外
        // （load 的 `|| dirty` 丢弃分支），本地与服务器永久失联。失败即复位
        // dirty——本地乐观改动允许被下一次 load() 的服务器状态覆盖；失败本身
        // 已经 saveError 外露（设置页 watch → snackbar「无法在服务器上保存
        // 设置」），不是无声丢弃。之后的新编辑会照常重新置 dirty 并重排保存。
        dirty = false
      }
    }, 600)
  }

  return { prefs, loading, loadError, saveSeq, saveError, load, updateGeneral, updateReader, updatePrivacy }
})
