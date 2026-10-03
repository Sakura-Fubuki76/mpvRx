package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.domain.network.NetworkConnection

/** Reuse local identity/credentials only for the same endpoint and account. */
internal fun restoredSettingsConnection(
  imported: NetworkConnection,
  existing: List<NetworkConnection>,
): NetworkConnection {
  fun matches(candidate: NetworkConnection): Boolean =
    candidate.protocol == imported.protocol &&
      candidate.host.equals(imported.host, ignoreCase = true) &&
      candidate.port == imported.port &&
      candidate.username == imported.username &&
      candidate.path.trimEnd('/') == imported.path.trimEnd('/') &&
      candidate.useHttps == imported.useHttps &&
      candidate.isAnonymous == imported.isAnonymous

  val local = existing.firstOrNull { it.id == imported.id && matches(it) }
    ?: existing.firstOrNull(::matches)
  val password = local?.password.orEmpty()
  return imported.copy(
    id = local?.id ?: imported.id.takeIf { it > 0 && existing.none { row -> row.id == it } } ?: 0,
    password = password,
    autoConnect = imported.autoConnect && (imported.isAnonymous || password.isNotEmpty()),
    isDeleted = false,
  )
}

internal fun remapCloudStorageSelection(selection: String, ids: Map<Long, Long>): String {
  if (selection.isBlank() || selection == "-1") return selection
  return selection.split(',').mapNotNull { it.toLongOrNull()?.let(ids::get) }
    .distinct().joinToString(",").ifEmpty { "-1" }
}
