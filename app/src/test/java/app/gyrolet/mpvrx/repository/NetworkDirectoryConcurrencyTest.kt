package app.gyrolet.mpvrx.repository

import android.net.Uri
import app.gyrolet.mpvrx.data.network.client.NetworkClient
import app.gyrolet.mpvrx.data.network.credentials.NetworkCredentialCipher
import app.gyrolet.mpvrx.database.dao.NetworkConnectionDao
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import java.io.InputStream
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class NetworkDirectoryConcurrencyTest {
  private class SlowClient : NetworkClient {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val nextEntered = CompletableDeferred<Boolean>()
    @Volatile var connected = false
    var failSlow = false
    override suspend fun connect(): Result<Unit> { connected = true; return Result.success(Unit) }
    override suspend fun disconnect() { connected = false }
    override fun isConnected() = connected
    override suspend fun listFiles(path: String) = listFiles(path, false)
    override suspend fun listFiles(path: String, forceRefresh: Boolean): Result<List<NetworkFile>> {
      if (path == "/slow") {
        entered.complete(Unit)
        // Model synchronous DAV I/O that has not finished even after its caller is cancelled.
        withContext(NonCancellable) { release.await() }
        if (failSlow) throw IllegalStateException("old request failed")
      } else {
        nextEntered.complete(forceRefresh)
      }
      return Result.success(listOf(NetworkFile("child", "$path/child", 0, true)))
    }
    override suspend fun getFileStream(path: String, offset: Long): Result<InputStream> = error("unused")
    override suspend fun getFileUri(path: String): Result<Uri> = error("unused")
  }

  private fun connection(protocol: NetworkProtocol) = NetworkConnection(1, "test", protocol, "example.test", 80)

  private fun repository(connection: NetworkConnection, factory: () -> NetworkClient): NetworkRepository {
    val dao = Proxy.newProxyInstance(
      NetworkConnectionDao::class.java.classLoader, arrayOf(NetworkConnectionDao::class.java),
    ) { _, method, _ ->
      when (method.name) {
        "getConnectionById" -> connection
        "updateLastConnected" -> Unit
        else -> error("Unexpected DAO call: ${method.name}")
      }
    } as NetworkConnectionDao
    return NetworkRepository(dao, NetworkCredentialCipher { error("No credentials needed") }, { factory() })
  }

  @Test fun slowHttpListingDoesNotBlockAnotherDirectory() = runBlocking {
    for (protocol in listOf(NetworkProtocol.WEBDAV, NetworkProtocol.OPENLIST)) {
      val connection = connection(protocol)
      val client = SlowClient()
      val repository = repository(connection) { client }
      val slow = async { repository.listFiles(connection, "/slow") }
      try {
        withTimeout(3_000) { client.entered.await() }
        val next = withTimeout(3_000) { repository.listFiles(connection, "/next", forceRefresh = true).getOrThrow() }
        assertEquals("/next/child", next.single().path)
        assertTrue(client.nextEntered.await())
        assertFalse(slow.isCompleted)
      } finally {
        client.release.complete(Unit)
        slow.join()
      }
    }
  }

  @Test fun cancelledDavListingDoesNotBlockNextDirectory() = runBlocking {
    val connection = connection(NetworkProtocol.WEBDAV)
    val client = SlowClient()
    val repository = repository(connection) { client }
    val slow = async { repository.listFiles(connection, "/slow") }
    try {
      withTimeout(3_000) { client.entered.await() }
      slow.cancel()
      assertTrue(withTimeout(3_000) { repository.listFiles(connection, "/next").isSuccess })
      assertFalse(slow.isCompleted)
    } finally {
      client.release.complete(Unit)
      slow.join()
    }
  }

  @Test fun disconnectedListingCannotPublishItsOldResult() = runBlocking {
    val connection = connection(NetworkProtocol.WEBDAV)
    val client = SlowClient()
    val repository = repository(connection) { client }
    val slow = async { repository.listFiles(connection, "/slow") }
    try {
      withTimeout(3_000) { client.entered.await() }
      withTimeout(3_000) { repository.disconnect(connection).getOrThrow() }
    } finally {
      client.release.complete(Unit)
      slow.join()
    }
    assertTrue(slow.isCancelled)
    assertFalse(repository.connectionStatuses.value.getValue(connection.id).isConnected)
  }

  @Test fun oldFailureDoesNotOverwriteReplacementStatus() = runBlocking {
    val connection = connection(NetworkProtocol.WEBDAV)
    val old = SlowClient().apply { failSlow = true }
    val replacement = SlowClient()
    var current = old
    val repository = repository(connection) { current }
    val slow = async { repository.listFiles(connection, "/slow") }
    try {
      withTimeout(3_000) { old.entered.await() }
      current = replacement
      withTimeout(3_000) { repository.connect(connection).getOrThrow() }
    } finally {
      old.release.complete(Unit)
      slow.join()
    }
    assertTrue(slow.await().isFailure)
    assertTrue(repository.connectionStatuses.value.getValue(connection.id).isConnected)
    assertNull(repository.connectionStatuses.value.getValue(connection.id).error)
  }

  @Test fun statefulProtocolsStillSerializeDirectoryRequests() = runBlocking {
    for (protocol in listOf(NetworkProtocol.FTP, NetworkProtocol.SFTP, NetworkProtocol.SMB)) {
      val connection = connection(protocol)
      val client = SlowClient()
      val repository = repository(connection) { client }
      val slow = async { repository.listFiles(connection, "/slow") }
      var next: kotlinx.coroutines.Deferred<Result<List<NetworkFile>>>? = null
      try {
        withTimeout(3_000) { client.entered.await() }
        next = async { repository.listFiles(connection, "/next") }
        assertNull(withTimeoutOrNull(150) { client.nextEntered.await() })
      } finally {
        client.release.complete(Unit)
        slow.join()
      }
      assertTrue(withTimeout(3_000) { checkNotNull(next).await().isSuccess })
    }
  }
}
