package app.gyrolet.mpvrx.data.network.client

import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class OpenListRefreshTest {
  private fun list(refresh: Boolean): List<JsonObject> = runBlocking {
    val requests = mutableListOf<JsonObject>()
    val http = OkHttpClient.Builder().addInterceptor { chain ->
      val buffer = Buffer()
      chain.request().body!!.writeTo(buffer)
      val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
      requests += body
      val page = body.getValue("page").jsonPrimitive.int
      val content = buildJsonArray {
        repeat(if (page == 1) 200 else 1) { index ->
          add(buildJsonObject { put("name", "$page-$index"); put("is_dir", true) })
        }
      }
      val response = buildJsonObject {
        put("code", 200)
        put("data", buildJsonObject { put("total", 201); put("content", content) })
      }
      Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK").body(response.toString().toResponseBody()).build()
    }.build()
    val connection = NetworkConnection(name = "test", protocol = NetworkProtocol.OPENLIST,
      host = "localhost", port = 5244, path = "/library", isAnonymous = true)
    val files = OpenListClient(connection, http).listFiles("/", refresh).getOrThrow()
    assertEquals(201, files.size)
    assertEquals("/library", requests.first().getValue("path").jsonPrimitive.content)
    requests
  }

  @Test fun explicitRefreshInvalidatesOnlyTheFirstPage() {
    val requests = list(true)
    assertEquals(2, requests.size)
    assertTrue(requests[0].getValue("refresh").jsonPrimitive.boolean)
    assertFalse(requests[1].getValue("refresh").jsonPrimitive.boolean)
  }

  @Test fun ordinaryListingsKeepServerCacheEnabled() {
    assertTrue(list(false).all { !it.getValue("refresh").jsonPrimitive.boolean })
  }
}
