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
  private class Fake(private val available: Boolean, private val size: Long = 100L) : NetworkClient {
    var failedDirectory: String? = null
    var lists = 0
    var streams = 0
    var sizes = 0
    override suspend fun getFileSize(path: String): Result<Long> {
      sizes++
      return if (available) Result.success(size) else Result.failure(IllegalStateException("No API"))
    }
    override suspend fun connect() = Result.success(Unit)
    override suspend fun disconnect() {}
    override fun isConnected() = true
    override suspend fun listFiles(path: String): Result<List<NetworkFile>> {
      lists++
      return if (available && path != failedDirectory) Result.success(listOf(NetworkFile("video.mp4", "$path/video.mp4", 100, false))) else Result.failure(IllegalStateException("No API"))
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
  @Test fun sizeUsesApiEvenBeforeDirectoryDiscovery() = runBlocking {
    val dav = Fake(false); val api = Fake(true, 1246971922L)
    val client = AlistWebDavClient(connection(74), dav, api)
    assertEquals(1246971922L, client.getFileSize("/video.mkv").getOrThrow())
    assertEquals(1, api.sizes); assertEquals(0, dav.sizes)
  }
  @Test fun failedAndInvalidApiSizesFallBackToDav() = runBlocking {
    for ((id, api) in listOf(75L to Fake(false), 76L to Fake(true, -1L))) {
      val dav = Fake(true, 500L)
      assertEquals(500L, AlistWebDavClient(connection(id), dav, api).getFileSize("/video.mkv").getOrThrow())
      assertEquals(1, api.sizes); assertEquals(1, dav.sizes)
    }
  }
  @Test fun knownNonAlistServerSkipsApiSizeProbe() = runBlocking {
    val dav = Fake(true); val api = Fake(false)
    val client = AlistWebDavClient(connection(77), dav, api)
    client.listFiles("/").getOrThrow()
    assertEquals(100L, client.getFileSize("/video.mkv").getOrThrow())
    assertEquals(0, api.sizes); assertEquals(1, dav.sizes)
  }
  @Test fun failedChildListingDoesNotDisableIdentifiedApiAcrossSessions() = runBlocking {
    val dav = Fake(true); val api = Fake(true)
    val connection = connection(78)
    val client = AlistWebDavClient(connection, dav, api)
    client.listFiles("/").getOrThrow()
    api.failedDirectory = "/deleted-child"
    client.listFiles("/deleted-child", true).getOrThrow()
    assertEquals(1, dav.lists)
    val reopened = AlistWebDavClient(connection, dav, api)
    assertEquals(100L, reopened.getFileSize("/video.mp4").getOrThrow())
    reopened.getFileStream("/video.mp4", 0).getOrThrow().close()
    reopened.listFiles("/next").getOrThrow()
    assertEquals(1, api.sizes); assertEquals(0, dav.sizes)
    assertEquals(1, api.streams); assertEquals(0, dav.streams)
    assertEquals(3, api.lists)
  }
}
