package app.gyrolet.mpvrx.data.network.client

import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import java.util.concurrent.ConcurrentHashMap

/** AList/OpenList is a capability of a WebDAV storage, not a separate user-facing protocol. */
class AlistWebDavClient(
  private val connection: NetworkConnection,
  private val dav: NetworkClient = WebDavClient(connection),
  private val api: NetworkClient = createApi(connection),
) : NetworkClient by dav {
  private data class Capability(val revision: Int, val supported: Boolean, val checkedAt: Long)
  companion object {
    private val capabilities = ConcurrentHashMap<Long, Capability>()
    internal fun apiConnection(connection: NetworkConnection): NetworkConnection {
      val segments = NetworkPath.from(connection.path).segments
      val index = segments.indexOf("dav")
      return connection.copy(protocol = NetworkProtocol.OPENLIST,
        path = if (index >= 0) "/" + segments.drop(index + 1).joinToString("/") else connection.path)
    }
    private fun createApi(connection: NetworkConnection): NetworkClient {
      val segments = NetworkPath.from(connection.path).segments
      val index = segments.indexOf("dav")
      val prefix = if (index >= 0) segments.take(index).joinToString("/") else ""
      return OpenListClient(apiConnection(connection), apiPrefix = prefix, tokenStore = OpenListTokenStore(
        org.koin.java.KoinJavaComponent.get(android.content.Context::class.java)))
    }
  }
  private fun supported(): Boolean? = capabilities[connection.id]?.takeIf {
    it.revision == connection.copy(lastConnected = 0, name = "", autoConnect = false).hashCode() && (it.supported || System.currentTimeMillis() - it.checkedAt < 600_000)
  }?.supported
  override suspend fun listFiles(path: String): Result<List<app.gyrolet.mpvrx.domain.network.NetworkFile>> {
    if (supported() != false) {
      val result = api.listFiles(path)
      if (capabilities.size > 128) capabilities.clear()
      capabilities[connection.id] = Capability(connection.copy(lastConnected = 0, name = "", autoConnect = false).hashCode(), result.isSuccess, System.currentTimeMillis())
      app.gyrolet.mpvrx.domain.cloud.CloudTrace.event("alist.list", connection.id, path, "success=${result.isSuccess} items=${result.getOrNull()?.size ?: 0}")
      if (result.isSuccess) return result
    }
    app.gyrolet.mpvrx.domain.cloud.CloudTrace.event("dav.list.fallback", connection.id, path)
    return dav.listFiles(path)
  }
  override val supportsSearch: Boolean get() = supported() != false
  override suspend fun searchFiles(path: String, query: String) = api.searchFiles(path, query)
  override suspend fun getThumbnailBytes(path: String) = if (supported() != false) api.getThumbnailBytes(path) else Result.success(null)
  override suspend fun getFileStream(path: String, offset: Long): Result<java.io.InputStream> {
    if (supported() == true) api.getFileStream(path, offset).let {
      app.gyrolet.mpvrx.domain.cloud.CloudTrace.event("stream.api", connection.id, path, "success=${it.isSuccess} offset=$offset")
      if (it.isSuccess) return it
    }
    app.gyrolet.mpvrx.domain.cloud.CloudTrace.event("stream.dav", connection.id, path, "offset=$offset")
    return dav.getFileStream(path, offset)
  }
  override suspend fun disconnect() { try { api.disconnect() } finally { dav.disconnect() } }
}
