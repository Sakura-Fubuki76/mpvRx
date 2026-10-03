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
) {
  fun isValid(): Boolean = columns in 1..10 && rows in 1..10 && cellWidth in 1..320 && cellHeight in 1..320 &&
    timesMs.isNotEmpty() && timesMs.size <= columns * rows && durationMs > 0 &&
    timesMs.all { it in 0..durationMs } && timesMs.zipWithNext().all { (a, b) -> a <= b }
  fun frameIndex(timeMs: Long): Int = timesMs.indices.minByOrNull { abs(timesMs[it] - timeMs.coerceIn(0, durationMs)) } ?: 0
}
