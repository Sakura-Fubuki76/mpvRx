package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import org.junit.Assert.*
import org.junit.Test

class CloudIndexIdentityTest {
  private val connection = NetworkConnection(name = "Library", protocol = NetworkProtocol.WEBDAV,
    host = "example.com", port = 80, path = "/anime", username = "user")
  @Test fun storedIdentityUsesOnlyStableEndpointFields() {
    assertEquals("7e3127d84f97d926ff77b326639bdb7d9e4ba5142d9eeb027d5ab6c86ca0f9a0", cloudIndexIdentity(connection))
    assertEquals(cloudIndexIdentity(connection), cloudIndexIdentity(connection.copy(name = "Renamed", lastConnected = 123,
      password = "changed", autoConnect = true, isAnime = true)))
  }
  @Test fun differentEndpointsCannotReuseAnIndex() {
    for (other in listOf(connection.copy(host = "other"), connection.copy(port = 443), connection.copy(useHttps = true),
      connection.copy(path = "/other"), connection.copy(username = "other"), connection.copy(protocol = NetworkProtocol.OPENLIST)))
      assertNotEquals(cloudIndexIdentity(connection), cloudIndexIdentity(other))
  }
}
