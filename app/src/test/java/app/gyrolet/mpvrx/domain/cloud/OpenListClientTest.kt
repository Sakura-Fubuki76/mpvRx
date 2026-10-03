package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.data.network.client.OpenListClient
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class OpenListClientTest {
  private val connection = NetworkConnection(id = 10, name = "test", protocol = NetworkProtocol.OPENLIST,
    host = "example.test", port = 80, username = "alice", password = "secret", path = "/mount")

  @Test fun refreshesRejectedTokenAndKeepsPathsRelative() = runBlocking {
    var logins = 0
    var lists = 0
    val client = OpenListClient(connection, OkHttpClient.Builder().addInterceptor { chain ->
      val request = chain.request()
      val body = when (request.url.encodedPath) {
        "/api/auth/login" -> """{"code":200,"data":{"token":"token${++logins}"}}"""
        "/api/fs/list" -> {
          lists++
          if (request.header("Authorization") == "token1") """{"code":401}"""
          else """{"code":200,"data":{"total":1,"content":[{"name":"a #%.mkv","size":3000000000,"is_dir":false,"modified":"2026-01-01T00:00:00Z"}]}}"""
        }
        else -> error("Unexpected endpoint")
      }
      Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody()).build()
    }.build())
    val file = client.listFiles("/movies").getOrThrow().single()
    assertEquals("/movies/a #%.mkv", file.path)
    assertEquals(3000000000L, file.size)
    assertEquals(2, logins)
    assertEquals(2, lists)
    assertTrue(file.lastModified > 0)
  }

  @Test fun rotatesSignedAddressesWithoutForwardingApiToken() = runBlocking {
    var resolves = 0
    val client = OpenListClient(connection.copy(isAnonymous = true), OkHttpClient.Builder().addInterceptor { chain ->
      val request = chain.request()
      var code = 200
      val body = if (request.url.host == "example.test") {
        """{"code":200,"data":{"raw_url":"https://cdn.test/video?sign=${++resolves}"}}"""
      } else {
        assertNull(request.header("Authorization"))
        if (resolves == 1) { code = 403; "expired" } else "video"
      }
      Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("OK")
        .body(body.toResponseBody()).build()
    }.build())
    assertEquals("video", client.getFileStream("/video.mkv").getOrThrow().use { it.readBytes().toString(Charsets.UTF_8) })
    assertEquals(2, resolves)
  }

  @Test fun rejectsSearchResultsOutsideConfiguredStorage() = runBlocking {
    val client = OpenListClient(connection.copy(isAnonymous = true), OkHttpClient.Builder().addInterceptor { chain ->
      Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body("""{"code":200,"data":{"total":1,"content":[{"parent":"/other","name":"x.mp4"}]}}""".toResponseBody()).build()
    }.build())
    assertTrue(client.searchFiles("/", "x").isFailure)
  }

  @Test fun fileRevisionAndSignedExtensionUseStableIdentity() {
    assertNotEquals(cloudMediaKey(connection, "/movie.mp4", 1, 2), cloudMediaKey(connection, "/movie.mp4", 2, 2))
    assertEquals("mp4", cloudMediaExtension("https://example.test/movie.mp4?sign=123"))
  }
  @Test fun thumbnailRefreshesExpiredCdnAddressWithoutApiCredentials() = runBlocking {
    var resolves = 0
    val client = OpenListClient(connection, OkHttpClient.Builder().addInterceptor { chain ->
      val request = chain.request()
      var code = 200
      val body = when {
        request.url.encodedPath == "/api/auth/login" -> """{"code":200,"data":{"token":"private-token"}}"""
        request.url.host == "example.test" -> {
          assertEquals("private-token", request.header("Authorization"))
          """{"code":200,"data":{"thumb_512":"https://cdn.test/cover?sign=${++resolves}"}}"""
        }
        else -> {
          assertNull(request.header("Authorization"))
          if (resolves == 1) { code = 403; "expired" } else "image"
        }
      }
      Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("OK")
        .body(body.toResponseBody()).build()
    }.build())
    assertEquals("image", client.getThumbnailBytes("/video.mp4").getOrThrow()!!.toString(Charsets.UTF_8))
    assertEquals(2, resolves)
  }

}
