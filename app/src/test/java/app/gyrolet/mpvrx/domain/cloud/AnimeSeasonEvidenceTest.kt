package app.gyrolet.mpvrx.domain.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release folders often write the season as a trailing roman numeral instead of "Season 2".
 * These helpers are additive evidence: the existing title identity and season parsing stay
 * untouched, so nothing that matched before changes.
 */
class AnimeSeasonEvidenceTest {
  private fun subject(
    id: Long,
    name: String,
    vararg aliases: String,
  ): AnimeSubject = AnimeSubject(id = id, name = name, aliases = aliases.toList(), format = "TV")

  @Test
  fun trailingRomanNumeralsReadAsSeasons() {
    assertEquals(2, animeTrailingSeasonNumber("Ore no Imoto II"))
    assertEquals(3, animeTrailingSeasonNumber("Show III"))
    assertEquals(4, animeTrailingSeasonNumber("Show IV"))
    assertEquals(2, animeTrailingSeasonNumber("【VCB-Studio】 Ore no Imoto II"))
  }

  @Test
  fun ambiguousOrInlineNumeralsAreNotSeasons() {
    assertNull(animeTrailingSeasonNumber("Show I"))
    assertNull(animeTrailingSeasonNumber("Ore no Imoto"))
    assertNull(animeTrailingSeasonNumber("86"))
    assertNull(animeTrailingSeasonNumber("Show 2"))
    assertNull(animeTrailingSeasonNumber("Show II Extra"))
  }

  @Test
  fun seasonDeclarationsCoverTheProviderSpellings() {
    assertTrue(2 in animeSeasonDeclarations("Ore no Imouto ga Konna ni Kawaii Wake ga Nai 2"))
    assertTrue(2 in animeSeasonDeclarations("俺妹2"))
    assertTrue(2 in animeSeasonDeclarations("我的妹妹哪有这么可爱！第2期"))
    assertTrue(2 in animeSeasonDeclarations("Show Season 2"))
    assertTrue(2 in animeSeasonDeclarations("Show II"))
    assertTrue(3 in animeSeasonDeclarations("Show Season 3"))
    assertTrue(animeSeasonDeclarations("Ore no Imoto").isEmpty())
  }

  @Test
  fun subjectDeclarationsReadEveryAlias() {
    val sequel =
      subject(
        37898,
        "俺の妹がこんなに可愛いわけがない。",
        "Ore no Imouto ga Konna ni Kawaii Wake ga Nai 2",
        "俺妹2",
        "我的妹妹哪有这么可爱！第2期",
      )
    assertEquals(setOf(2), animeSeasonDeclarations(sequel))

    val firstSeason = subject(1409, "俺の妹がこんなに可愛いわけがない", "Ore no Imouto ga Konna ni Kawaii Wake ga Nai")
    assertTrue(animeSeasonDeclarations(firstSeason).isEmpty())
  }

  @Test
  fun seasonlessTitlesKeepTheirPlainIdentity() {
    // The new evidence must not change what the existing identity does for ordinary titles.
    assertEquals(animeIdentityKey("Ore no Imoto II"), animeIdentityKey("Ore no Imoto II"))
    assertNull(animeExplicitSeason("Ore no Imoto II"))
  }

  @Test
  fun seasonNumeralStripsBackToTheBaseTitle() {
    assertEquals("Ore no Imoto", animeWithoutTrailingNumeral("Ore no Imoto II"))
    assertEquals("Show", animeWithoutTrailingNumeral("Show III"))
    assertEquals("Show", animeWithoutTrailingNumeral("Show IV"))
    assertEquals("Ore no Imoto", animeWithoutTrailingNumeral("Ore no Imoto"))
  }

  @Test
  fun looseIdentityFoldsVowelLengthSpellings() {
    // The first season's romaji alias uses ō while release folders and sequel aliases use ou
    // (or drop the u entirely): all three must meet on one key.
    assertEquals(
      animeLooseIdentityKey("Ore no Imouto ga Konna ni Kawaii Wake ga Nai."),
      animeLooseIdentityKey("Ore no Imōto ga Konna ni Kawaii Wake ga Nai"),
    )
    assertEquals(animeLooseIdentityKey("Ore no Imoto"), animeLooseIdentityKey("Ore no Imouto"))
  }

  @Test
  fun extendedVariantTitlesStayDistinctUnderLooseIdentity() {
    // An OAD/OVA sibling extends the same base; exactness keeps it out of earlier-season repair.
    assertNotEquals(
      animeLooseIdentityKey("To LOVEる-とらぶる-ダークネス"),
      animeLooseIdentityKey("To LOVEる-とらぶる-ダークネス OAD"),
    )
  }

  @Test
  fun abbreviatedBaseExtendsIntoTheFullTitle() {
    assertTrue(animeTitleExtends("Ore no Imoto", "Ore no Imouto ga Konna ni Kawaii Wake ga Nai 2"))
    assertTrue(animeTitleExtends("俺妹", "俺妹2"))
    assertFalse(animeTitleExtends("Ore no Imoto", "Yahari Ore no Seishun Lovecome wa Machigatte Iru. Zoku"))
    assertFalse(animeTitleExtends("Ore no Imoto", "Ore no Kanojo to Osananajimi ga Shuraba Sugiru"))
    assertFalse(animeTitleExtends("", "Ore no Imoto"))
  }
}
