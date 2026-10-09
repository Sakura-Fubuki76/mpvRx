package app.gyrolet.mpvrx.domain.cloud

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Files
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class FragmentedMp4SupportTest {
  private fun box(type: String, vararg parts: ByteArray): ByteArray {
    val body = parts.fold(byteArrayOf()) { all, part -> all + part }
    return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
  }
  private fun track(id: Int, handler: String, version: Int = 0): ByteArray {
    val header = ByteBuffer.allocate(if (version == 1) 28 else 20)
    header.putInt(version shl 24)
    if (version == 1) { header.putLong(0); header.putLong(0) } else { header.putInt(0); header.putInt(0) }
    header.putInt(id); header.putInt(0)
    return box("trak", box("tkhd", header.array()), box("mdia", box("hdlr", ByteArray(8), handler.toByteArray())))
  }
  @Test fun identifiesVideoTrackRatherThanFirstAudioTrack() {
    val data = box("ftyp", ByteArray(16)) + box("moov", track(482, "soun"), track(481, "vide"), box("mvex"))
    assertEquals(FragmentedMp4Support.Layout(true, 481), FragmentedMp4Support.parseLayout(data))
    assertFalse(FragmentedMp4Support.fragmentHasTrack(box("traf", box("tfhd", ByteArray(4), ByteBuffer.allocate(4).putInt(482).array())), 481))
    assertTrue(FragmentedMp4Support.fragmentHasTrack(box("traf", box("tfhd", ByteArray(4), ByteBuffer.allocate(4).putInt(481).array())), 481))
  }
  @Test fun readsVersionOneTrackHeader() {
    assertEquals(FragmentedMp4Support.Layout(true, 7), FragmentedMp4Support.parseLayout(box("moov", track(7, "vide", 1), box("mvex"))))
  }
  @Test fun normalMp4DoesNotEnableFragmentOptimizations() {
    assertEquals(FragmentedMp4Support.Layout(false), FragmentedMp4Support.parseLayout(box("moov", track(1, "vide"))))
  }
  @Test fun truncatedOrMalformedMetadataDoesNotProducePositiveDetection() {
    val data = box("moov", track(1, "vide"), box("mvex"))
    assertNull(FragmentedMp4Support.parseLayout(data.copyOf(data.size - 1)))
    assertNull(FragmentedMp4Support.parseLayout(byteArrayOf(0, 0, 0, 7, 109, 111, 111, 118)))
    assertEquals(FragmentedMp4Support.Layout(true), FragmentedMp4Support.parseLayout(box("moov", box("trak", box("tkhd")), box("mvex"))))
  }
  private fun fragment(id: Int): ByteArray = box("moof", box("traf", box("tfhd", ByteArray(4), ByteBuffer.allocate(4).putInt(id).array()))) + box("mdat", ByteArray(32))
  private fun client(bytes: ByteArray, status: Int = 206) = OkHttpClient.Builder().addInterceptor { chain ->
    assertEquals("bytes=0-16777215", chain.request().header("Range"))
    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
      .body(bytes.toResponseBody()).build()
  }.build()
  @Test fun previewPreservesOffsetsAndStopsAfterThreeVideoFragments() {
    val header = box("ftyp", ByteArray(16)) + box("moov", track(1, "vide"), box("mvex"))
    val expected = header + fragment(2) + fragment(1) + fragment(2) + fragment(1) + fragment(1)
    val directory = Files.createTempDirectory("fragment-test").toFile()
    try {
      val file = FragmentedMp4Support.previewClip("http://example.test/video", FragmentedMp4Support.Layout(true, 1), directory,
        client(expected + fragment(1)))!!
      assertArrayEquals(expected, file.readBytes())
    } finally { directory.deleteRecursively() }
  }
  @Test fun sizeLimitKeepsOnlyTheLastCompleteVideoFragment() {
    val expected = box("moov", track(1, "vide"), box("mvex")) + fragment(1)
    val oversized = ByteBuffer.allocate(8).putInt(20 * 1024 * 1024).put("mdat".toByteArray()).array()
    val directory = Files.createTempDirectory("fragment-test").toFile()
    try {
      val file = FragmentedMp4Support.previewClip("http://example.test/video", FragmentedMp4Support.Layout(true, 1), directory,
        client(expected + oversized))!!
      assertArrayEquals(expected, file.readBytes())
    } finally { directory.deleteRecursively() }
  }
  @Test fun failedDownloadDoesNotLeavePartialPreviewFiles() {
    val directory = Files.createTempDirectory("fragment-test").toFile()
    try {
      assertNull(FragmentedMp4Support.previewClip("http://example.test/video", FragmentedMp4Support.Layout(true, 1), directory,
        client(byteArrayOf(), 404)))
      assertEquals(0, directory.listFiles()!!.size)
    } finally { directory.deleteRecursively() }
  }
}
