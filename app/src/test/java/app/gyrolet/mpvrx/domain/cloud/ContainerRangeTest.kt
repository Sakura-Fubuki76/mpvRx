package app.gyrolet.mpvrx.domain.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContainerRangeTest {
  private fun response(code: Int, range: String = "bytes 10-12/100") = Response.Builder()
    .request(Request.Builder().url("https://example.com/movie.mkv").build())
    .protocol(Protocol.HTTP_1_1).code(code).message("test")
    .header("Content-Range", range).body(byteArrayOf(1, 2, 3, 4, 5).toResponseBody()).build()

  @Test fun validatesOffsetAndBoundsRead() {
    response(206).use { assertArrayEquals(byteArrayOf(1, 2, 3), it.readContainerRange(10, 3)) }
    response(206, "bytes 0-2/100").use { assertNull(it.readContainerRange(10, 3)) }
    response(206, "bytes 10-14/100").use { assertNull(it.readContainerRange(10, 3)) }
  }

  @Test fun rejectsIgnoredRangeAndOversizedAllocation() {
    response(200).use { assertNull(it.readContainerRange(10, 3)) }
    response(206).use { assertNull(it.readContainerRange(10, Int.MAX_VALUE)) }
  }

  @Test fun bothExtractorsRejectAFullFileBody() = runBlocking {
    val client = OkHttpClient.Builder().addInterceptor { response(200).newBuilder().request(it.request()).build() }.build()
    assertNull(Mp4KeyframeExtractor(client).httpRange("https://example.com/movie.mp4", 10, 3))
    assertNull(MkvKeyframeExtractor(client).httpRange("https://example.com/movie.mkv", 10, 3))
  }
}
