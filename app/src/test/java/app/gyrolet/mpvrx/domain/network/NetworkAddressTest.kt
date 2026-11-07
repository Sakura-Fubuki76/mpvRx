package app.gyrolet.mpvrx.domain.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAddressTest {
  @Test
  fun httpAddressRetainsOpenListProtocolAndLibraryPath() {
    val address = NetworkAddress.parse(
      "https://example.com:5244/dav/Blue-Ray Anime",
      NetworkProtocol.OPENLIST,
    )
    assertEquals(NetworkProtocol.OPENLIST, address.protocol)
    assertEquals("example.com", address.host)
    assertEquals(5244, address.port)
    assertEquals("/dav/Blue-Ray Anime", address.path)
    assertTrue(address.useHttps)
  }

  @Test fun httpAddressRetainsDlnaProtocolAndDescriptionPath() {
    val address = NetworkAddress.parse("https://media.example:8443/upnp/description.xml", NetworkProtocol.DLNA)
    assertEquals(NetworkProtocol.DLNA, address.protocol)
    assertEquals("/upnp/description.xml", address.path)
    assertEquals(8443, address.port)
    assertTrue(address.useHttps)
  }

  @Test fun bareHttpsAddressesKeepAllThreeHttpBackedProtocols() {
    for (protocol in listOf(NetworkProtocol.WEBDAV, NetworkProtocol.OPENLIST, NetworkProtocol.DLNA)) {
      val connection = NetworkConnection(name = "server", protocol = protocol, host = "media.example",
        port = 443, path = "/library", useHttps = true).normalizedAddress()
      assertEquals(protocol, connection.protocol)
      assertEquals(443, connection.port)
      assertEquals("/library", connection.path)
      assertTrue(connection.useHttps)
    }
  }

  @Test fun davAliasesRemainWebDavExceptForExplicitOpenListConnections() {
    assertEquals(NetworkProtocol.OPENLIST, NetworkAddress.parse("davs://media.example/dav", NetworkProtocol.OPENLIST).protocol)
    assertEquals(NetworkProtocol.WEBDAV, NetworkAddress.parse("davs://media.example/dav", NetworkProtocol.DLNA).protocol)
  }
}
