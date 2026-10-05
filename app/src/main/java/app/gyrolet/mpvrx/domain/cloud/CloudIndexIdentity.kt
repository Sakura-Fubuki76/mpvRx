package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.domain.network.NetworkConnection
import java.security.MessageDigest

/** Persisted identities must not include enum/object hash codes, which vary by process. */
internal fun cloudIndexIdentity(connection: NetworkConnection): String {
  val fields = listOf(connection.protocol.name, connection.host, connection.port.toString(),
    connection.useHttps.toString(), connection.path, connection.username)
  return MessageDigest.getInstance("SHA-256").digest(fields.joinToString("\u0000").toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
}
