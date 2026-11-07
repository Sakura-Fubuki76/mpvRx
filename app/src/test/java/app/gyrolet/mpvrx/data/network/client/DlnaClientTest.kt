package app.gyrolet.mpvrx.data.network.client

import app.gyrolet.mpvrx.domain.network.DlnaPath
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class DlnaClientTest {
  private val description = """<root><device><friendlyName>Test NAS</friendlyName><serviceList><service>
    <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/control</controlURL>
    </service></serviceList></device></root>"""
  private val metadata = """<BrowseResponse><Result><DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
    <item id="track"><dc:title>No extension</dc:title><upnp:class>object.item.audioItem</upnp:class>
    <res protocolInfo="http-get:*:audio/flac:*" size="6">http://media.example/resource</res></item>
    </DIDL-Lite></Result><NumberReturned>1</NumberReturned><TotalMatches>1</TotalMatches></BrowseResponse>"""
  private val path = DlnaPath.child(NetworkPath.from("/"), "track", 'a').value

  private fun client(resource: (Request) -> Response): DlnaClient {
    val http = OkHttpClient.Builder().addInterceptor { chain ->
      val request = chain.request()
      when (request.url.encodedPath) {
        "/description.xml" -> reply(request, description)
        "/control" -> reply(request, metadata)
        "/resource" -> resource(request)
        else -> error("Unexpected DLNA endpoint")
      }
    }.build()
    return DlnaClient(NetworkConnection(name = "test", protocol = NetworkProtocol.DLNA,
      host = "media.example", port = 80, path = "/description.xml"), http)
  }

  private fun reply(request: Request, body: String, code: Int = 200, range: String? = null): Response =
    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
      .body(body.toResponseBody()).apply { if (range != null) header("Content-Range", range) }.build()

  @Test fun listsOpaqueAudioIdsAndResolvesAdvertisedSize() = runBlocking {
    val client = client { error("Listing must not download media") }
    client.connect().getOrThrow()
    val listed = client.listFiles("/").getOrThrow().single()
    assertEquals("No extension", listed.name)
    assertEquals("audio/flac", listed.mimeType)
    assertEquals("track", DlnaPath.objectId(NetworkPath.from(listed.path)))
    assertEquals(6L, client.getFileSize(listed.path).getOrThrow())
    client.disconnect()
    assertFalse(client.isConnected())
  }

  @Test fun continuesChunkedRangesAtTheCorrectOffset() = runBlocking {
    val ranges = mutableListOf<String?>()
    val client = client { request ->
      ranges += request.header("Range")
      when (request.header("Range")) {
        "bytes=2-" -> reply(request, "cd", 206, "bytes 2-3/6")
        "bytes=4-" -> reply(request, "ef", 206, "bytes 4-5/6")
        else -> error("Wrong range")
      }
    }
    client.connect().getOrThrow()
    val bytes = client.getFileStream(path, 2).getOrThrow().use { it.readBytes() }
    assertEquals("cdef", bytes.toString(Charsets.UTF_8))
    assertEquals(listOf("bytes=2-", "bytes=4-"), ranges)
  }

  @Test fun rejectsRangesStartingAtADifferentOffset() = runBlocking {
    val client = client { reply(it, "ab", 206, "bytes 0-1/6") }
    client.connect().getOrThrow()
    assertTrue(client.getFileStream(path, 2).isFailure)
  }

  @Test fun skipsToTheRequestedOffsetWhenServerIgnoresRange() = runBlocking {
    val client = client { reply(it, "abcdef") }
    client.connect().getOrThrow()
    assertEquals("cdef", client.getFileStream(path, 2).getOrThrow().use { it.readBytes() }.toString(Charsets.UTF_8))
  }
}
