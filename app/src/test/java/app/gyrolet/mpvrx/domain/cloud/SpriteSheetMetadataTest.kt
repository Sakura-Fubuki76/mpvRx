package app.gyrolet.mpvrx.domain.cloud

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SpriteSheetMetadataTest {
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
}
