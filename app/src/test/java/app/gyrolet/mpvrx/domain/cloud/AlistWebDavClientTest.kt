package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.data.network.client.*
import app.gyrolet.mpvrx.domain.network.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class AlistWebDavClientTest {
  private fun connection(id: Long) = NetworkConnection(id, "storage", NetworkProtocol.WEBDAV, "example.test", 80, path = "/prefix/dav/mount")
  private class Fake(private val available: Boolean) : NetworkClient {
    var lists = 0
    var streams = 0
    override suspend fun connect() = Result.success(Unit)
    override suspend fun disconnect() {}
    override fun isConnected() = true
    override suspend fun listFiles(path: String): Result<List<NetworkFile>> {
      lists++
      return if (available) Result.success(listOf(NetworkFile("video.mp4", "$path/video.mp4", 100, false))) else Result.failure(IllegalStateException("No API"))
    }
    override suspend fun getFileStream(path: String, offset: Long): Result<InputStream> {
      streams++
      return if (available) Result.success(ByteArrayInputStream(byteArrayOf(1))) else Result.failure(IllegalStateException("expired"))
    }
    override suspend fun getFileUri(path: String): Result<android.net.Uri> = Result.failure(UnsupportedOperationException())
  }
  @Test fun mapsDavStorageRootToApiRoot() {
    assertEquals("/mount", AlistWebDavClient.apiConnection(connection(71)).path)
    assertEquals("/", AlistWebDavClient.apiConnection(connection(71).copy(path = "/dav")).path)
  }
  @Test fun unavailableApiFallsBackAndDoesNotRepeatProbe() = runBlocking {
    val dav = Fake(true); val api = Fake(false)
    val client = AlistWebDavClient(connection(72), dav, api)
    assertTrue(client.listFiles("/").isSuccess)
    assertTrue(client.listFiles("/next").isSuccess)
    assertEquals(1, api.lists); assertEquals(2, dav.lists)
    assertTrue(client.getFileStream("/video.mp4", 0).isSuccess)
    assertEquals(1, dav.streams)
  }
  @Test fun identifiedAlistStreamsThroughApi() = runBlocking {
    val dav = Fake(true); val api = Fake(true)
    val client = AlistWebDavClient(connection(73), dav, api)
    assertTrue(client.listFiles("/").isSuccess)
    client.getFileStream("/video.mp4", 0).getOrThrow().close()
    assertEquals(1, api.streams); assertEquals(0, dav.streams)
  }
}
