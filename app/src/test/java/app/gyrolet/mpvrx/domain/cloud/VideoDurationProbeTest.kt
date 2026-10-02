package app.gyrolet.mpvrx.domain.cloud

import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class VideoDurationProbeTest {
    @Test
    fun serverIgnoringRange_isReadOnlyUpToProbeLimit() = runBlocking {
        val bytes = ByteArray(1024 * 1024)
        ByteBuffer.wrap(bytes).apply {
            putInt(16)
            put("ftyp".toByteArray())
            position(16)
            putInt(40)
            put("moov".toByteArray())
            putInt(32)
            put("mvhd".toByteArray())
            putInt(0) // version and flags
            putInt(0) // creation time
            putInt(0) // modification time
            putInt(1000) // timescale
            putInt(42000) // duration
        }
        val buffer = Buffer().write(bytes)
        var closed = false
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = bytes.size.toLong()
            override fun source(): BufferedSource = buffer
            override fun close() {
                closed = true
            }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("bytes=0-16383", chain.request().header("Range"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }.build()
        assertEquals(42000L, probeVideoDurationMs("https://example.com/video.mp4", client))
        assertEquals(bytes.size.toLong() - 16384, buffer.size)
        assertEquals(true, closed)
    }

    @Test
    fun rejectedFile_doesNotThrowOrCancelBatch() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(404).message("Missing").body(ResponseBody.create(null, ByteArray(0))).build()
        }.build()
        assertNull(probeVideoDurationMs("https://example.com/missing.mp4", client))
    }

    @Test
    fun cancellation_isNotSwallowed() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw CancellationException("cancelled") }.build()
        try {
            probeVideoDurationMs("https://example.com/video.mp4", client)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: navigation cancellation must stop pending network work.
        }
    }

    @Test
    fun webDavCredentials_areSentAsHeaderAndRemovedFromRequestUrl() {
        val request = videoMetadataRequest("https://alice:p%40ss@example.com/video.mp4").build()
        assertEquals(Credentials.basic("alice", "p@ss"), request.header("Authorization"))
        assertEquals("https://example.com/video.mp4", request.url.toString())
        val signed = videoMetadataRequest("https://cdn.example.com/video.mp4?sign=abc").build()
        assertNull(signed.header("Authorization"))
        assertFalse(signed.url.query.isNullOrEmpty())
    }
}
