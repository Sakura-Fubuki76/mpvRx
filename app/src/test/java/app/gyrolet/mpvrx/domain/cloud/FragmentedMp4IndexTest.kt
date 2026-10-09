package app.gyrolet.mpvrx.domain.cloud

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class FragmentedMp4IndexTest {
  private fun words(vararg values: Int) = ByteBuffer.allocate(values.size * 4).apply { values.forEach { putInt(it) } }.array()
  private fun box(type: String, data: ByteArray) = words(data.size + 8) + type.toByteArray() + data
  private val track = FragmentedMp4Index.Track(481, 1000, FragmentedMp4Index.Defaults(1000, 10, 0x10000))
  private fun fragment(time: Int, offset: Int = 128): ByteArray = box("moof", box("traf",
    box("tfhd", words(0x20000, 481)) + box("tfdt", words(0, time)) +
      box("trun", words(0x305, 3, offset, 0, 1000, 10, 1000, 20, 1000, 30))))

  @Test fun firstSampleFlagsAndDefaultNonSyncFlagsSelectOnlyKeyframe() {
    val result = FragmentedMp4Index.fragment(fragment(900000), 4000, track, 0)!!
    assertEquals(listOf(FragmentedMp4Index.Sample(900000, 4128, 10)), result.samples)
    assertEquals(903000, result.nextDecodeTime)
  }

  @Test fun signedCompositionOffsetAndExplicitBaseAreRespected() {
    val data = box("moof", box("traf", box("tfhd", words(1, 481, 0, 8000)) +
      box("tfdt", words(0x1000000, 0, 10000)) + box("trun", words(0x1000f01, 1, 100, 1000, 20, 0, -500))))
    val result = FragmentedMp4Index.fragment(data, 100, track, 0)!!
    assertEquals(FragmentedMp4Index.Sample(9500, 8100, 20), result.samples.single())
  }

  @Test fun scannerSkipsLargeMediaPayloadAndIndexesDistantFragment() {
    val first = fragment(0)
    val payloadSize = 200000
    val secondAt = first.size + payloadSize
    val second = fragment(900000)
    val bytes = first + box("mdat", ByteArray(payloadSize - 8)) + second + box("mdat", ByteArray(1000))
    var downloaded = 0
    val samples = FragmentedMp4Index.scan(bytes.size.toLong(), track) { at, size ->
      downloaded += size
      bytes.copyOfRange(at.toInt(), at.toInt() + size)
    }!!
    assertEquals(2, samples.size)
    assertEquals(900000, samples.last().timeMs)
    assertEquals(secondAt.toLong() + 128, samples.last().offset)
    assertTrue(downloaded < bytes.size / 2)
  }

  @Test fun malformedFragmentNeverProducesPartialIndex() {
    assertNull(FragmentedMp4Index.fragment(fragment(0).dropLast(1).toByteArray(), 0, track, 0))
    assertNull(FragmentedMp4Index.scan(1000, track) { _, _ -> words(1) + "moof".toByteArray() })
  }

  @Test fun unknownImplicitBaseForLaterTrackIsRejected() {
    val audio = box("traf", box("tfhd", words(0x20000, 482)))
    val video = box("traf", box("tfhd", words(0, 481)) + box("trun", words(5, 1, 100, 0)))
    assertNull(FragmentedMp4Index.fragment(box("moof", audio + video), 0, track, 0))
  }
}
