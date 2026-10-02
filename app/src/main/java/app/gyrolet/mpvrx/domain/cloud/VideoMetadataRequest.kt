package app.gyrolet.mpvrx.domain.cloud

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Convert WebDAV stream URL credentials into HTTP authentication for metadata requests. */
internal fun videoMetadataRequest(url: String): Request.Builder {
    val parsed = url.toHttpUrl()
    return Request.Builder()
        .url(parsed.newBuilder().username("").password("").build())
        .apply {
            if (parsed.username.isNotEmpty()) {
                header("Authorization", Credentials.basic(parsed.username, parsed.password))
            }
        }
}
