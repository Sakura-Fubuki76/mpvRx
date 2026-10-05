package app.gyrolet.mpvrx.domain.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimeSortTest {
  @Test fun fastScrollLabelsFollowActiveSortAndKeepFullYearsAndScores() {
    val item = AnimeSortItem("a", " 动画第二季", "2024-04-01", 8.6)
    assertEquals("动", animeFastScrollLabel(item, AnimeSortType.Title))
    assertEquals("2024", animeFastScrollLabel(item, AnimeSortType.Year))
    assertEquals("8.6", animeFastScrollLabel(item, AnimeSortType.Score))
    assertEquals("1", animeFastScrollLabel(item.copy(title = "123"), AnimeSortType.Title))
    assertEquals("A", animeFastScrollLabel(item.copy(title = "anime"), AnimeSortType.Title))
    assertEquals("—", animeFastScrollLabel(item.copy(date = ""), AnimeSortType.Year))
    assertEquals("—", animeFastScrollLabel(item.copy(score = 0.0), AnimeSortType.Score))
  }

  @Test fun fastScrollMappingIncludesRecentCardAndFooterInBothDirections() {
    val older = AnimeSortItem("old", "A", "2018-04-01", 8.0)
    val newer = AnimeSortItem("new", "B", "2024-04-01", 9.0)
    assertEquals(listOf("2024", "2018", "2024", null), animeFastScrollLabels(listOf(older, newer), newer, AnimeSortType.Year))
    assertEquals(listOf("9.0", "9.0", "8.0", null), animeFastScrollLabels(listOf(newer, older), newer, AnimeSortType.Score))
    assertEquals(listOf("A", "B", null), animeFastScrollLabels(listOf(older, newer), null, AnimeSortType.Title))
  }

  private fun sorted(items: List<AnimeSortItem>, type: AnimeSortType, ascending: Boolean = true) =
    items.sortedWith { a, b -> compareAnimeSort(a, b, type, ascending) }.map { it.key }

  @Test fun displayNamesUseNaturalOrderingRatherThanPaths() {
    assertEquals(listOf("z", "a"), sorted(listOf(AnimeSortItem("a", "动画10"), AnimeSortItem("z", "动画2")), AnimeSortType.Title))
  }

  @Test fun unknownYearsStayLastInEitherDirection() {
    val items = listOf(AnimeSortItem("unknown", "A"), AnimeSortItem("old", "B", "2018-04-01"), AnimeSortItem("new", "C", "2024-01-01"))
    assertEquals(listOf("old", "new", "unknown"), sorted(items, AnimeSortType.Year))
    assertEquals(listOf("new", "old", "unknown"), sorted(items, AnimeSortType.Year, false))
  }

  @Test fun scoreTiesUseNamesAndInvalidScoresStayLast() {
    val items = listOf(AnimeSortItem("b", "B", score = 8.0), AnimeSortItem("a", "A", score = 8.0), AnimeSortItem("unknown", "C", score = Double.NaN))
    assertEquals(listOf("a", "b", "unknown"), sorted(items, AnimeSortType.Score, false))
  }
}
