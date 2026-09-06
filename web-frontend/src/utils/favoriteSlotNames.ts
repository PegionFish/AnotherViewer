/**
 * 收藏夹槽位名解析（Wave-1 B-4）——自已删除的 `GalleryCard.vue` 原样迁出
 * （W3-F5）；消费方为 FavoriteView 的收藏夹条。
 */

/**
 * Android `SiteConfig.DEFAULT_FAV_CAT_NAMES` — the built-in favorite folder
 * names, "Favorites 0" … "Favorites 9" (same defaults FavoriteView's folder
 * strip has always used).
 */
export const DEFAULT_FAVORITE_SLOT_NAMES: readonly string[] = Array.from(
  { length: 10 },
  (_, i) => `Favorites ${i}`,
)

/**
 * B-4: parses `general.favoriteSlotNames` — a `|`-separated list of up to 10
 * folder names. Missing/empty entries fall back to
 * {@link DEFAULT_FAVORITE_SLOT_NAMES} slot by slot (`A||C` → `A`, `Favorites 1`,
 * `C`, `Favorites 3` …). Non-string input yields the full default list.
 */
export function parseFavoriteSlotNames(raw: unknown): string[] {
  const parts = typeof raw === 'string' ? raw.split('|') : []
  return Array.from({ length: 10 }, (_, i) => {
    const name = parts[i]?.trim()
    return name ? name : DEFAULT_FAVORITE_SLOT_NAMES[i]
  })
}
