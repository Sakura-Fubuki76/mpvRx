package app.gyrolet.mpvrx.domain.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDirectoryCachePolicyTest {
  @Test fun persistedTimestampSurvivesRestartWithoutRenewingAge() {
    val scannedAt = 1_000_000L
    assertTrue(isCloudDirectoryFresh(scannedAt, scannedAt + 29 * 60_000L))
    assertFalse(isCloudDirectoryFresh(scannedAt, scannedAt + 30 * 60_000L))
  }

  @Test fun missingOrFutureTimestampRequiresRefresh() {
    assertFalse(isCloudDirectoryFresh(0, 1_000_000L))
    assertFalse(isCloudDirectoryFresh(-1, 1_000_000L))
    assertFalse(isCloudDirectoryFresh(1_000_001L, 1_000_000L))
  }
}
