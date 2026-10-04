package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SpriteSheetMetadataTest {
  @Test fun failedCellsNeverShowAnEarlierOrLaterPreview() {
    val meta = SpriteSheetMetadata(cellWidth = 480, cellHeight = 270,
      timesMs = listOf(0, 1000, 2000), durationMs = 3000, intervalMs = 1000.0, validCells = listOf(0, 2))
    val restored = Json.decodeFromString(SpriteSheetMetadata.serializer(), Json.encodeToString(SpriteSheetMetadata.serializer(), meta))
    assertTrue(restored.isValid())
    assertTrue(restored.hasFrame(999))
    assertFalse(restored.hasFrame(1000))
    assertFalse(restored.hasFrame(1999))
    assertTrue(restored.hasFrame(2000))
    assertFalse(restored.copy(validCells = listOf(3)).isValid())
  }

  @Test fun persistentGeneratorMetadataKeepsSuccessfulGridIndices() {
    val meta = YumeSpriteMetadata(10, 10, 3, 480, 270, 1000.0, 3000, listOf(0, 2))
    assertEquals(meta, YumeSpriteMetadata.fromJson(meta.toJson()))
    assertFalse(meta.isComplete())
    assertTrue(meta.copy(validCells = listOf(0, 1, 2)).isComplete())
    assertFalse(meta.copy(validCells = listOf(0, 0, 2)).isComplete())
  }
  @Test fun timestampsRoundTripAndSeekClampsToAvailableFrames() {
    val meta = SpriteSheetMetadata(timesMs = listOf(0, 1000, 3000), durationMs = 4000)
    val restored = Json.decodeFromString(SpriteSheetMetadata.serializer(), Json.encodeToString(SpriteSheetMetadata.serializer(), meta))
    assertTrue(restored.isValid())
    assertEquals(0, restored.frameIndex(-1000))
    assertEquals(1, restored.frameIndex(1300))
    assertEquals(2, restored.frameIndex(100000))
    assertFalse(restored.copy(timesMs = listOf(3000, 1000)).isValid())
    assertFalse(restored.copy(columns = 1000).isValid())
  }
  @Test fun yumeGridUsesIntervalsRatherThanNearestFutureFrame() {
    val meta = SpriteSheetMetadata(timesMs = listOf(0, 1000, 2000, 3000), durationMs = 4000, intervalMs = 1000.0)
    assertEquals(0, meta.frameIndex(999))
    assertEquals(1, meta.frameIndex(1000))
    assertEquals(1, meta.frameIndex(1999))
    assertEquals(3, meta.frameIndex(4000))
    val restored = Json.decodeFromString(SpriteSheetMetadata.serializer(), Json.encodeToString(SpriteSheetMetadata.serializer(), meta))
    assertEquals(1, restored.frameIndex(1999))
  }
}
