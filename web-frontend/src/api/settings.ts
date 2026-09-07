import client from './client'

export interface DownloadSettings {
  path: string
  downloadDelay: number
  downloadTimeout: number
  maxConcurrentGalleries: number
  maxConcurrentImages: number
}

export interface CacheSettings {
  path: string
  sizeMb: number
}

export interface SmbSettings {
  enabled: boolean
}

export interface SecuritySettings {
  requireAuth: boolean
  sessionTimeout: number
}

export interface ProcessingSettings {
  enabled: boolean
  defaultType: string
  outputFormat: string
  outputQuality: number
  /** EntryPoint（图像处理副武器）端点；token 永不回传，只报 entrypointTokenSet。 */
  entrypointUrl: string
  /** True when an EntryPoint API token is stored server-side (GET only). */
  entrypointTokenSet?: boolean
  /** 下载完成后自动处理。 */
  automationEnabled: boolean
  /** 定期补跑未处理页。 */
  periodicEnabled: boolean
  periodicIntervalMinutes: number
}

export interface ProxySettings {
  enabled: boolean
  type: string
  host: string
  port: number
  username: string
  /** Backend echoes "" here — never the stored value; omit on PUT to keep the stored password. */
  password: string
  /** True when a proxy password is stored server-side (GET only). */
  proxyPasswordSet?: boolean
}

export interface Settings {
  download: DownloadSettings
  cache: CacheSettings
  smb: SmbSettings
  security: SecuritySettings
  processing: ProcessingSettings
  proxy: ProxySettings
}

export const settingsApi = {
  async get(): Promise<Settings> {
    const { data } = await client.get('/settings')
    return data
  },

  async update(settings: Partial<Settings>): Promise<boolean> {
    const { data } = await client.put('/settings', settings)
    return data
  },
}
