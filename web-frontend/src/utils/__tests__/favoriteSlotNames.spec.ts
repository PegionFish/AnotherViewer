import { describe, expect, it } from 'vitest'
import { DEFAULT_FAVORITE_SLOT_NAMES, parseFavoriteSlotNames } from '../favoriteSlotNames'

/** 用例自已删除的 GalleryCard.spec.ts（W3-F5）原样迁移。 */
describe('parseFavoriteSlotNames (pure helper)', () => {
  it('returns all defaults for non-string input', () => {
    expect(parseFavoriteSlotNames(undefined)).toEqual([...DEFAULT_FAVORITE_SLOT_NAMES])
    expect(parseFavoriteSlotNames(null)).toEqual([...DEFAULT_FAVORITE_SLOT_NAMES])
    expect(parseFavoriteSlotNames(42)).toEqual([...DEFAULT_FAVORITE_SLOT_NAMES])
  })

  it('returns all defaults for an empty string', () => {
    expect(parseFavoriteSlotNames('')).toEqual([...DEFAULT_FAVORITE_SLOT_NAMES])
  })

  it('keeps the FavoriteView default naming for unfilled slots', () => {
    // FavoriteView has always used "Favorites 0" … "Favorites 9".
    expect(DEFAULT_FAVORITE_SLOT_NAMES[0]).toBe('Favorites 0')
    expect(DEFAULT_FAVORITE_SLOT_NAMES[9]).toBe('Favorites 9')
  })
})
