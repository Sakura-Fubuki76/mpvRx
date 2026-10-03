package app.gyrolet.mpvrx.domain.cloud

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class MoovIndexCacheTest {
  @Test
  fun restoresDecoderConfigurationAndTrackWithoutNetworkAfterEviction() = runBlocking {
    val directory = Files.createTempDirectory("container-index").toFile()
    try {
      MoovIndexCache.init(directory)
      val key = "cloud|connection|movie.mkv|version1"
      val frames = listOf(Mp4KeyframeExtractor.KeyframeEntry(1, 1000, 4096, 256))
      val info = Mp4KeyframeExtractor.MoovInfo(1000, 5000, "avc1", 1920, 1080, 90,
        listOf(byteArrayOf(0, 0, 1, 103)), 4, frames)
      val parsed = Mp4KeyframeExtractor.ParsedMoov(9000, 128, info, 5000, 7)
      MoovIndexCache.put(key, MoovIndexCache.Entry(frames, 9000, 5000, parsed = parsed))
      repeat(40) { MoovIndexCache.put("eviction-$it", MoovIndexCache.Entry(emptyList(), 0, null)) }
      assertNull(MoovIndexCache.get(key))
      val client = OkHttpClient.Builder().addInterceptor { error("Cache hit must avoid network") }.build()
      val restored = MkvKeyframeExtractor(client).loadParsedMkv("http://localhost:1234/new-token", key)!!
      assertEquals(7L, restored.videoTrackNumber)
      assertEquals(90, restored.moovInfo!!.rotation)
      assertArrayEquals(info.codecConfigNalUnits.single(), restored.moovInfo!!.codecConfigNalUnits.single())
      assertEquals(frames, restored.moovInfo!!.keyframes)
      assertEquals(5000L, Mp4KeyframeExtractor(client).loadParsedMoov("http://localhost:5678/token", key)!!.durationMs)
    } finally {
      directory.deleteRecursively()
    }
  }
}
