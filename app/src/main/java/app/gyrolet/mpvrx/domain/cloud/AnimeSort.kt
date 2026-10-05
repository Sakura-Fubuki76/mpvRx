package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.utils.sort.SortUtils

enum class AnimeSortType { Title, Year, Score }

data class AnimeSortItem(val key: String, val title: String, val date: String = "", val score: Double = 0.0)

/** Missing provider metadata stays last in either direction; names resolve ties deterministically. */
fun compareAnimeSort(left: AnimeSortItem, right: AnimeSortItem, type: AnimeSortType, ascending: Boolean): Int {
  val names = SortUtils.NaturalOrderComparator.DEFAULT
  val leftYear = left.date.take(4).toIntOrNull()?.takeIf { it > 0 }
  val rightYear = right.date.take(4).toIntOrNull()?.takeIf { it > 0 }
  val leftValue = when (type) { AnimeSortType.Year -> leftYear?.toDouble(); AnimeSortType.Score -> left.score.takeIf { it > 0 && it.isFinite() }; else -> null }
  val rightValue = when (type) { AnimeSortType.Year -> rightYear?.toDouble(); AnimeSortType.Score -> right.score.takeIf { it > 0 && it.isFinite() }; else -> null }
  if (type != AnimeSortType.Title && (leftValue == null) != (rightValue == null)) return if (leftValue == null) 1 else -1
  val primary = when (type) {
    AnimeSortType.Title -> names.compare(left.title, right.title)
    else -> if (leftValue != null && rightValue != null) leftValue.compareTo(rightValue) else 0
  }
  if (primary != 0) return if (ascending) primary else -primary
  return names.compare(left.title, right.title).takeIf { it != 0 } ?: left.key.compareTo(right.key)
}
