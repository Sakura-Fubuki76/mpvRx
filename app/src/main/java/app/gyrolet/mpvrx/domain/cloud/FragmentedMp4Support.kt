package app.gyrolet.mpvrx.domain.cloud

import android.content.Context
import app.gyrolet.mpvrx.network.SharedHttpClient
import okhttp3.Request
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Bounded container inspection; no frame decoding and no credentials in cache identities. */
internal object FragmentedMp4Support {
  internal data class Layout(val fragmented: Boolean, val videoTrack: Int = 0)
  private const val MAX_PREFIX = 16 * 1024 * 1024
  private val probeHttp by lazy { SharedHttpClient.derive { callTimeout(3, TimeUnit.SECONDS) } }
  private val clipHttp by lazy { SharedHttpClient.derive { callTimeout(20, TimeUnit.SECONDS) } }

  fun inspect(url: String, identity: String, context: Context): Layout? {
    val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
      .joinToString("") { "%02x".format(it) }
    val prefs = context.getSharedPreferences("cloud_mp4_layout_v1", Context.MODE_PRIVATE)
    if (prefs.contains(key)) return prefs.getInt(key, -1).let { Layout(it > 0, it.coerceAtLeast(0)) }
    return try {
      probeHttp.newCall(Request.Builder().url(url).header("Range", "bytes=0-65535")
        .header("Accept-Encoding", "identity").build()).execute().use { response ->
        if (!response.isSuccessful) return null
        val layout = parseLayout(response.body.byteStream().readNBytes(65536)) ?: return null
        if (!layout.fragmented || layout.videoTrack > 0) prefs.edit().putInt(key, if (layout.fragmented) layout.videoTrack else -1).apply()
        layout
      }
    } catch (_: IOException) { null }
  }

  /** Keep original offsets, with a few complete video fragments for the solid-frame retry. */
  fun previewClip(url: String, layout: Layout, directory: File, client: OkHttpClient = clipHttp): File? {
    if (!layout.fragmented || layout.videoTrack <= 0) return null
    if (!directory.mkdirs() && !directory.isDirectory) return null
    val file = File.createTempFile("fragment-", ".mp4", directory)
    var complete = false
    var lastVideoEnd = 0L
    var videoFragments = 0
    try {
      client.newCall(Request.Builder().url(url).header("Range", "bytes=0-${MAX_PREFIX - 1}")
        .header("Accept-Encoding", "identity").build()).execute().use { response ->
        if (!response.isSuccessful) return null
        response.body.byteStream().use { input ->
          file.outputStream().use { output ->
            var total = 0L
            var videoFragment = false
            val buffer = ByteArray(32 * 1024)
            while (total < MAX_PREFIX) {
              val header = input.readNBytes(8)
              if (header.size != 8) break
              var size = uint32(header, 0)
              val type = type(header, 4)
              val extra = if (size == 1L) input.readNBytes(8) else byteArrayOf()
              val headerSize = 8 + extra.size
              if (size == 1L) {
                if (extra.size != 8) break
                size = uint32(extra, 0) * 0x100000000L + uint32(extra, 4)
              }
              if (size < headerSize || size > MAX_PREFIX - total) break
              output.write(header); output.write(extra)
              var remaining = size - headerSize
              if (type == "moof") {
                if (remaining > 256 * 1024) break
                val body = input.readNBytes(remaining.toInt())
                if (body.size.toLong() != remaining) break
                output.write(body)
                videoFragment = fragmentHasTrack(body, layout.videoTrack)
                remaining = 0
              }
              while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (read < 0) throw IOException("Incomplete fragment")
                output.write(buffer, 0, read); remaining -= read
              }
              total += size
              if (type == "mdat" && videoFragment) {
                lastVideoEnd = total
                videoFragments++
                if (videoFragments >= 3) break
              }
            }
          }
        }
      }
      if (lastVideoEnd <= 0L) return null
      java.io.RandomAccessFile(file, "rw").use { it.setLength(lastVideoEnd) }
      complete = true
      return file
    } catch (_: IOException) { return null }
    finally { if (!complete) file.delete() }
  }

  internal fun parseLayout(bytes: ByteArray): Layout? {
    val moov = boxes(bytes).firstOrNull { it.type == "moov" } ?: return null
    val children = boxes(bytes, moov.start, moov.end)
    if (children.none { it.type == "mvex" }) return Layout(false)
    for (track in children.filter { it.type == "trak" }) {
      val parts = boxes(bytes, track.start, track.end)
      val tkhd = parts.firstOrNull { it.type == "tkhd" } ?: continue
      if (tkhd.end - tkhd.start < 4) continue
      val mdia = parts.firstOrNull { it.type == "mdia" } ?: continue
      val hdlr = boxes(bytes, mdia.start, mdia.end).firstOrNull { it.type == "hdlr" } ?: continue
      if (hdlr.end - hdlr.start < 12 || type(bytes, hdlr.start + 8) != "vide") continue
      val idOffset = tkhd.start + if (bytes[tkhd.start].toInt() == 1) 20 else 12
      if (idOffset + 4 <= tkhd.end) return Layout(true, uint32(bytes, idOffset).toInt())
    }
    return Layout(true)
  }

  internal fun fragmentHasTrack(body: ByteArray, track: Int): Boolean = boxes(body).filter { it.type == "traf" }.any { traf ->
    boxes(body, traf.start, traf.end).any { it.type == "tfhd" && it.end - it.start >= 8 && uint32(body, it.start + 4).toInt() == track }
  }

  private data class Box(val type: String, val start: Int, val end: Int)
  private fun boxes(data: ByteArray, start: Int = 0, end: Int = data.size): List<Box> {
    val result = mutableListOf<Box>()
    var cursor = start
    while (cursor <= end - 8) {
      val size = uint32(data, cursor)
      if (size < 8 || size > end - cursor) break
      result += Box(type(data, cursor + 4), cursor + 8, cursor + size.toInt())
      cursor += size.toInt()
    }
    return result
  }
  private fun uint32(bytes: ByteArray, at: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 255) }
  private fun type(bytes: ByteArray, at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)
}
