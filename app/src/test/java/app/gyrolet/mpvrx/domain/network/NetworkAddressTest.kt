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
}
