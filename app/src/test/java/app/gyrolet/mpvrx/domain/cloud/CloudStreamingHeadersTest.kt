package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.network.CloudStreamingHeaders
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class CloudStreamingHeadersTest {
  @Test fun matchesBaiduHostsWithoutMatchingSpoofedNamesOrQueryStrings() {
    for (host in listOf("pan.baidu.com", "a.baidupcs.com", "b.baidupcs.net"))
      assertEquals("pan.baidu.com", CloudStreamingHeaders.userAgent("https://$host/video"))
    for (url in listOf("https://baidupcs.com.evil.test/video", "https://example.test/?next=pan.baidu.com", "http://127.0.0.1:5244/dav/video"))
      assertNull(CloudStreamingHeaders.userAgent(url))
  }
  @Test fun actualRedirectTargetGetsBaiduUaAndUncompressedRanges() {
    val request = Request.Builder().url("https://a.baidupcs.com/video").header("Range", "bytes=10-").header("User-Agent", "original").build()
    val modified = CloudStreamingHeaders.apply(request)
    assertEquals("pan.baidu.com", modified.header("User-Agent"))
    assertEquals("identity", modified.header("Accept-Encoding"))
    assertEquals("bytes=10-", modified.header("Range"))
    assertEquals("original", CloudStreamingHeaders.apply(request.newBuilder().url("https://example.test/video").build()).header("User-Agent"))
  }
}
