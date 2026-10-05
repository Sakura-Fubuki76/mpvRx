package app.gyrolet.mpvrx.domain.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimeSortTest {
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
