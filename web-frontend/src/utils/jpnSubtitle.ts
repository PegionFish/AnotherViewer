/**
 * 日文标题副题的可见性判定（详情页偏好接线批次 T2）。
 *
 * 语义决策（定案）：`general.showJpnTitle` 控制「日文标题副题是否显示」，
 * 尊重协议默认 false（Android 语义）。但 prefs 尚未加载完成（undefined）
 * 时按「显示」渲染防闪失——Web 存量行为是「打码关 → 无条件显示」，未加载
 * 就按协议默认 false 收起会让副题先显示后被隐藏（或反向闪烁）。加载完成后
 * 按服务器真实值（默认 false → 隐藏）。
 *
 * 「打码开启时无论何值都隐藏」这一条不在本函数内——由调用方与
 * `privacyMaskEnabled` 一起判，本函数只负责偏好维度。
 */
import type { GeneralPreferences } from '@/api/preferences'

export function isJpnSubtitleVisible(general: GeneralPreferences | undefined): boolean {
  // prefs 未加载 → 按显示兜底（防闪失，见上）。
  if (general === undefined) return true
  // 严格 `=== true`：旧服务器缺键时与协议默认 false 同判（隐藏），开关不失效。
  return general.showJpnTitle === true
}
