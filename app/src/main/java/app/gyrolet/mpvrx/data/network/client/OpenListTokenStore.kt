package app.gyrolet.mpvrx.data.network.client

import android.content.Context
import app.gyrolet.mpvrx.data.network.credentials.AndroidNetworkCredentialKey
import app.gyrolet.mpvrx.data.network.credentials.NetworkCredentialCipher
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import java.security.MessageDigest

/** Tokens are encrypted and scoped to both endpoint and account, including credential changes. */
class OpenListTokenStore(context: Context) {
  private val prefs = context.getSharedPreferences("openlist_auth", Context.MODE_PRIVATE)
  private val cipher = NetworkCredentialCipher(AndroidNetworkCredentialKey::getOrCreate)
  private fun key(c: NetworkConnection) = MessageDigest.getInstance("SHA-256")
    .digest("${c.id}|${c.host}|${c.port}|${c.useHttps}|${c.username}|${c.password}".toByteArray())
    .joinToString("") { "%02x".format(it) }
  fun read(c: NetworkConnection): String? = runCatching {
    val value = cipher.decrypt(prefs.getString(key(c), null) ?: return null)
    val time = value.substringBefore('\n').toLong()
    if (System.currentTimeMillis() - time in 0..30 * 60 * 1000L) value.substringAfter('\n') else null
  }.getOrNull()
  fun write(c: NetworkConnection, token: String) {
    runCatching { prefs.edit().putString(key(c), cipher.encrypt("${System.currentTimeMillis()}\n$token")).apply() }
  }
  fun remove(c: NetworkConnection) { prefs.edit().remove(key(c)).apply() }
}
