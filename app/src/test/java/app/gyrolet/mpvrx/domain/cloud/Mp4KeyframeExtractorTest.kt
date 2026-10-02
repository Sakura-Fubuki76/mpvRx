package app.gyrolet.mpvrx.domain.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the ported ISO-BMFF parser end to end against a synthetic file served by a fake HTTP
 * server, so the range-probe and `moov`/`mvhd` parsing logic runs on the JVM without a device.
 */
class Mp4KeyframeExtractorTest {

  @Test
  fun duration_isParsedFromTailRangeProbe() = runBlocking {
    val file = syntheticMp4(timescale = 1_000, durationUnits = 42_000)
    val server = FakeRangeServer(file)

    val duration = Mp4KeyframeExtractor(server.client).extractDurationMs(URL)

    assertEquals(42_000L, duration)
    assertTrue("expected a HEAD probe for the content length", server.methods.contains("HEAD"))
    assertTrue("expected a Range probe for the moov atom", server.ranges.any { it != null })
  }

  @Test
  fun versionOneMvhd_usesSixtyFourBitDuration() = runBlocking {
    val file = syntheticMp4(timescale = 90_000, durationUnits = 180_000, version = 1)
    val server = FakeRangeServer(file)

    // 180000 / 90000 = 2 seconds
    assertEquals(2_000L, Mp4KeyframeExtractor(server.client).extractDurationMs(URL))
  }

  @Test
  fun fileWithoutMoov_yieldsNoDuration() = runBlocking {
    val file = ByteArray(4_096).also {
      writeInt(it, 0, 16)
      "ftyp".toByteArray().copyInto(it, 4)
      "isom".toByteArray().copyInto(it, 8)
    }
    val server = FakeRangeServer(file)

    assertNull(Mp4KeyframeExtractor(server.client).extractDurationMs(URL))
  }

  @Test
  fun stableKey_keepsOneIdentityAcrossRotatingProxyUrls() = runBlocking {
    val file = syntheticMp4(timescale = 1_000, durationUnits = 5_000)
    val server = FakeRangeServer(file)
    val extractor = Mp4KeyframeExtractor(server.client)

    // Two different loopback URLs, one durable key: the second call must be served from the
    // in-memory moov cache instead of re-probing the container.
    assertEquals(
      5_000L,
      extractor.extractDurationMs("http://127.0.0.1:1111/token-a/movie.mp4", stableKey = STABLE_KEY),
    )
    val headsAfterFirst = server.methods.count { it == "HEAD" }
    assertEquals(1, headsAfterFirst)

    assertEquals(
      5_000L,
      extractor.extractDurationMs("http://127.0.0.1:2222/token-b/movie.mp4", stableKey = STABLE_KEY),
    )
    assertEquals(
      "a stable key must serve the second, differently-addressed request from cache",
      headsAfterFirst,
      server.methods.count { it == "HEAD" },
    )
  }

  @Test
  fun containerSupport_isLimitedToIndexableExtensions() {
    val extractor = CloudKeyframeExtractor(FakeRangeServer(ByteArray(0)).client)
    assertTrue(extractor.supports("mp4"))
    assertTrue(extractor.supports("MOV"))
    assertTrue(extractor.supports("m4v"))
    assertTrue(extractor.supports("mkv"))
    assertTrue(extractor.supports("webm"))
    assertEquals(false, extractor.supports("avi"))
    assertEquals(false, extractor.supports("ts"))
  }

  @Test
  fun stableWebDavUrl_dropsCredentialsAndSignature() {
    assertEquals(
      "https://dav.example.com/movie.mp4",
      stableWebDavUrl("https://alice:p%40ss@dav.example.com/movie.mp4?sign=rotating#frag"),
    )
    assertEquals("/local/path.mp4", stableWebDavUrl("/local/path.mp4"))
  }

  private companion object {
    const val URL = "https://dav.example.com/movie.mp4"
    const val STABLE_KEY = "cloud|7|/movies/movie.mp4"
  }
}

private fun writeInt(target: ByteArray, offset: Int, value: Int) {
  target[offset] = (value ushr 24).toByte()
  target[offset + 1] = (value ushr 16).toByte()
  target[offset + 2] = (value ushr 8).toByte()
  target[offset + 3] = value.toByte()
}

private fun writeLong(target: ByteArray, offset: Int, value: Long) {
  for (i in 0 until 8) {
    target[offset + i] = (value ushr (56 - 8 * i)).toByte()
  }
}

/**
 * Builds a file with the shape `[ftyp][moov[mvhd][free]]` padded past the extractor's 1 KiB
 * minimum, which is enough for the duration path that only needs `mvhd`.
 */
internal fun syntheticMp4(
  timescale: Int,
  durationUnits: Int,
  version: Int = 0,
): ByteArray {
  val mvhd = ByteArray(if (version == 1) 120 else 108)
  writeInt(mvhd, 0, mvhd.size)
  "mvhd".toByteArray().copyInto(mvhd, 4)
  writeInt(mvhd, 8, version shl 24)
  if (version == 1) {
    writeInt(mvhd, 28, timescale)
    writeLong(mvhd, 32, durationUnits.toLong())
  } else {
    writeInt(mvhd, 20, timescale)
    writeInt(mvhd, 24, durationUnits)
  }

  val moovSize = 512
  val moov = ByteArray(moovSize)
  writeInt(moov, 0, moovSize)
  "moov".toByteArray().copyInto(moov, 4)
  mvhd.copyInto(moov, 8)
  val freeOffset = 8 + mvhd.size
  writeInt(moov, freeOffset, moovSize - freeOffset)
  "free".toByteArray().copyInto(moov, freeOffset + 4)

  val ftyp = ByteArray(16)
  writeInt(ftyp, 0, 16)
  "ftyp".toByteArray().copyInto(ftyp, 4)
  "isom".toByteArray().copyInto(ftyp, 8)

  val header = ftyp + moov
  val trailerSize = (2_048 - header.size).coerceAtLeast(8)
  val trailer = ByteArray(trailerSize)
  writeInt(trailer, 0, trailerSize)
  "free".toByteArray().copyInto(trailer, 4)

  return header + trailer
}

/**
 * Serves a fixed byte array. A request with `Range` is answered with the requested window, which is
 * how a real WebDAV/OpenList server behaves; requests without one return an empty body the way the
 * extractor's HEAD probe expects.
 */
internal class FakeRangeServer(
  private val file: ByteArray,
) {
  val methods = mutableListOf<String>()
  val ranges = mutableListOf<String?>()

  val client: OkHttpClient =
    OkHttpClient.Builder().addInterceptor { chain ->
      val request = chain.request()
      methods += request.method
      val range = request.header("Range")
      ranges += range
      // A HEAD probe carries no bytes but must still advertise the real length; a Range request is
      // answered with just that window, like a WebDAV/OpenList server would.
      val payload = range?.let(::slice)
      val advertisedLength = payload?.size ?: file.size
      Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .header("Content-Length", advertisedLength.toString())
        .body(ByteArrayBody(payload ?: ByteArray(0)))
        .build()
    }.build()

  private fun slice(range: String): ByteArray {
    val spec = range.removePrefix("bytes=")
    val start = spec.substringBefore('-').toLongOrNull() ?: 0L
    val end = spec.substringAfter('-').toLongOrNull() ?: (file.size - 1).toLong()
    val from = start.coerceIn(0L, file.size.toLong()).toInt()
    val to = (end + 1).coerceIn(from.toLong(), file.size.toLong()).toInt()
    return file.copyOfRange(from, to)
  }
}

private class ByteArrayBody(
  private val bytes: ByteArray,
) : ResponseBody() {
  override fun contentType(): MediaType? = null

  override fun contentLength(): Long = bytes.size.toLong()

  override fun source(): BufferedSource = Buffer().write(bytes)
}
