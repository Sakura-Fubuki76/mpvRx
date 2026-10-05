package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.utils.sort.SortUtils

enum class AnimeSortType { Title, Year, Score }

data class AnimeSortItem(val key: String, val title: String, val date: String = "", val score: Double = 0.0)

fun animeFastScrollLabel(item: AnimeSortItem, type: AnimeSortType): String = when (type) {
  AnimeSortType.Title -> item.title.trim().let { title ->
    if (title.isEmpty()) "—" else String(Character.toChars(title.codePointAt(0))).uppercase(java.util.Locale.ROOT)
  }
  AnimeSortType.Year -> item.date.take(4).takeIf { it.toIntOrNull()?.let { year -> year > 0 } == true } ?: "—"
  AnimeSortType.Score -> if (item.score > 0 && item.score.isFinite()) String.format(java.util.Locale.ROOT, "%.1f", item.score) else "—"
}

/** The featured card occupies one full grid span; the attribution footer is not an anime. */
fun animeFastScrollLabels(items: List<AnimeSortItem>, featured: AnimeSortItem?, type: AnimeSortType): List<String?> =
  buildList {
    featured?.let { add(animeFastScrollLabel(it, type)) }
    items.forEach { add(animeFastScrollLabel(it, type)) }
    add(null)
  }

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
