package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import org.junit.Assert.*
import org.junit.Test

class SettingsConnectionRestoreTest {
  private fun connection(id: Long, host: String = "example.test", password: String = "") =
    NetworkConnection(id, "WebDAV", NetworkProtocol.WEBDAV, host, 443,
      username = "user", password = password, autoConnect = true, useHttps = true)

  @Test fun sameEndpointReusesLocalIdentityAndCredentials() {
    val local = connection(7, password = "device-bound")
    val restored = restoredSettingsConnection(connection(3), listOf(local))
    assertEquals(7L, restored.id)
    assertEquals("device-bound", restored.password)
    assertTrue(restored.autoConnect)
  }

  @Test fun idCollisionNeverBorrowsAnotherServersPassword() {
    val restored = restoredSettingsConnection(connection(7), listOf(connection(7, "other.test", "secret")))
    assertEquals(0L, restored.id)
    assertEquals("", restored.password)
    assertFalse(restored.autoConnect)
  }

  @Test fun newInstallationPreservesFreeIdButRequiresLogin() {
    val restored = restoredSettingsConnection(connection(3), emptyList())
    assertEquals(3L, restored.id)
    assertFalse(restored.autoConnect)
    assertTrue(restoredSettingsConnection(connection(3).copy(isAnonymous = true), emptyList()).autoConnect)
  }

  @Test fun selectionRemapsWithoutTurningMissingSelectionIntoAllStorages() {
    assertEquals("7,9", remapCloudStorageSelection("3,4,3", mapOf(3L to 7L, 4L to 9L)))
    assertEquals("-1", remapCloudStorageSelection("3", emptyMap()))
    assertEquals("", remapCloudStorageSelection("", emptyMap()))
    assertEquals("-1", remapCloudStorageSelection("-1", emptyMap()))
  }
}
