package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class SpriteSheetMetadata(
  val columns: Int = 10,
  val rows: Int = 10,
  val cellWidth: Int = 160,
  val cellHeight: Int = 90,
  val timesMs: List<Long>,
  val durationMs: Long,
  val imageHash: String = "",
  val intervalMs: Double = 0.0,
  val validCells: List<Int>? = null,
) {
  fun hasFrame(timeMs: Long): Boolean = validCells?.contains(frameIndex(timeMs)) ?: timesMs.isNotEmpty()
  fun isValid(): Boolean = columns in 1..10 && rows in 1..10 && cellWidth in 1..480 && cellHeight in 1..480 &&
    timesMs.isNotEmpty() && timesMs.size <= columns * rows && durationMs > 0 &&
    timesMs.all { it in 0..durationMs } && timesMs.zipWithNext().all { (a, b) -> a <= b } &&
    (validCells == null || (validCells.isNotEmpty() && validCells.distinct().size == validCells.size && validCells.all { it in timesMs.indices }))
  fun frameIndex(timeMs: Long): Int = if (intervalMs > 0) (timeMs.coerceIn(0, durationMs) / intervalMs).toInt().coerceIn(0, (timesMs.size - 1).coerceAtLeast(0)) else timesMs.indices.minByOrNull { abs(timesMs[it] - timeMs.coerceIn(0, durationMs)) } ?: 0
}
