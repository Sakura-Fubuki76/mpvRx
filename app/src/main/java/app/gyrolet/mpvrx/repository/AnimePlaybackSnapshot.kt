package app.gyrolet.mpvrx.repository

import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Keep Room's last complete result across navigation and identity-query changes. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun animePlaybackSnapshot(
  scope: CoroutineScope,
  library: StateFlow<AnimeLibrarySnapshot>,
  observeStates: (List<String>) -> Flow<List<PlaybackStateEntity>>,
): StateFlow<Map<String, PlaybackStateEntity>> = library
  .map { it.playbackIdentities.values.distinct().sorted() }
  .distinctUntilChanged()
  .flatMapLatest { identities ->
    if (identities.isEmpty()) flowOf(emptyMap())
    else combine(identities.chunked(400).map(observeStates)) { batches ->
      batches.flatMap { it }.associateBy { it.mediaTitle }
    }
  }
  .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())
