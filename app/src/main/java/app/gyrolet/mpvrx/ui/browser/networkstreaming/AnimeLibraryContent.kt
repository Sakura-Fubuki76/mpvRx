package app.gyrolet.mpvrx.ui.browser.networkstreaming

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.cloud.*
import app.gyrolet.mpvrx.domain.network.*
import app.gyrolet.mpvrx.repository.*
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.browser.cards.NetworkVideoCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnimeLibraryContent(
  connection: NetworkConnection,
  currentPath: String,
  searchQuery: String,
  onPlay: (NetworkFile, List<NetworkFile>) -> Unit,
  onBrowse: (NetworkFile) -> Unit,
  onRefresh: () -> Unit,
  onOpenDetails: (String, String, String) -> Unit = { _, _, _ -> },
  detailKey: String? = null,
  isRefreshing: Boolean = false,
  libraryGridState: LazyGridState? = null,
  onEditMatch: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val cloud = koinInject<CloudMetadataRepository>()
  val anime = koinInject<AnimeRepository>()
  val snapshot by remember(connection.id, currentPath) {
    anime.observeLibrary(connection.id, currentPath, cloud.observeLibraryData(connection.id, currentPath, videosOnly = true))
  }.collectAsState()
  LaunchedEffect(snapshot.loaded) {
    if (snapshot.loaded) CloudTrace.event("anime.library.ready", connection.id, currentPath, "videos=${snapshot.files.size} groups=${snapshot.groups.size}")
  }
  val allFiles = snapshot.files
  val libraryGroups = snapshot.groups
  val catalog by remember(connection.id) { anime.observe(connection.id) }.collectAsState()
  val matchProgress by remember(connection.id) { anime.observeProgress(connection.id) }.collectAsState()
  val playbackDao = koinInject<app.gyrolet.mpvrx.database.MpvRxDatabase>().videoDataDao()
  val identities = remember(snapshot.playbackIdentities, detailKey) {
    if (detailKey == null) snapshot.playbackIdentities.values.toList()
    else libraryGroups[detailKey]?.files.orEmpty().mapNotNull { snapshot.playbackIdentities[it.path] }
  }
  val playback by remember(identities) {
    if (identities.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyMap<String, app.gyrolet.mpvrx.database.entities.PlaybackStateEntity>())
    else kotlinx.coroutines.flow.combine(identities.chunked(400).map { playbackDao.observeVideoStates(it) }) { rows ->
      rows.flatMap { it }.associateBy { it.mediaTitle }
    }
  }.collectAsState(emptyMap())
  val groups = remember(libraryGroups) { libraryGroups.mapValues { it.value.files } }
  LaunchedEffect(connection.id, snapshot.playbackIdentities) {
    kotlinx.coroutines.delay(750)
    if (detailKey == null && snapshot.loaded && allFiles.isNotEmpty()) anime.schedule(connection.id, allFiles)
  }

  val visible by produceState(initialValue = emptyList<Map.Entry<String, List<NetworkFile>>>(), snapshot, catalog, searchQuery, detailKey) {
    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
      if (detailKey != null || !snapshot.loaded || !catalog.loaded) emptyList() else groups.entries.filter { (path, files) ->
        searchQuery.isBlank() || path.contains(searchQuery, true) || files.any { it.name.contains(searchQuery, true) } ||
          catalog.subjects[catalog.folders[path]?.subjectId]?.title?.contains(searchQuery, true) == true
      }.sortedWith(compareBy<Map.Entry<String, List<NetworkFile>>, String>(app.gyrolet.mpvrx.utils.sort.SortUtils.NaturalOrderComparator.DEFAULT) {
        catalog.subjects[catalog.folders[it.key]?.subjectId]?.title ?: libraryGroups[it.key]?.query ?: it.key
      }.thenBy { libraryGroups[it.key]?.query ?: it.key }.thenBy { it.key })
    }
  }
  val watchedCounts by produceState(initialValue = emptyMap<String, Pair<Int, Int>>(), snapshot, playback) {
    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
      libraryGroups.mapValues { (_, group) ->
        val episodes = group.files.filterNot(::isAnimeExtraVideo)
        episodes.count { playback[snapshot.playbackIdentities[it.path]]?.hasBeenWatched == true } to episodes.size
      }
    }
  }
  val retainedGridState = libraryGridState ?: rememberLazyGridState()
  if (detailKey == null && visible.isNotEmpty()) PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { onRefresh(); anime.schedule(connection.id, allFiles) }, modifier = modifier.fillMaxSize()) {
  LazyVerticalGrid(state = retainedGridState, columns = GridCells.Adaptive(145.dp), modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    val featured = visible.firstOrNull { it.key == snapshot.featuredKey }
      ?: visible.firstOrNull { catalog.subjects[catalog.folders[it.key]?.subjectId]?.cover?.isNotBlank() == true }
      ?: visible.firstOrNull()
    if (featured != null) item(key = "anime-featured", span = { GridItemSpan(maxLineSpan) }) {
      val subject = catalog.subjects[catalog.folders[featured.key]?.subjectId]
      val featuredTitle = subject?.title ?: libraryGroups[featured.key]?.query.orEmpty()
      Card(onClick = { onOpenDetails(featured.key, libraryGroups.getValue(featured.key).directory, featuredTitle) }, shape = RoundedCornerShape(24.dp)) {
        Box(Modifier.fillMaxWidth().height(280.dp)) {
          if (subject != null) RemoteImage(subject.cover, null, Modifier.fillMaxSize(), ContentScale.Crop, alpha = .25f)
          Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surfaceContainer, Color.Transparent))))
          Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Text(featuredTitle, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
              Text(subject?.let { "${it.date.take(4)}  ·  ${it.score}" }.orEmpty(), style = MaterialTheme.typography.labelLarge)
              Text(subject?.summary.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (subject != null) RemoteImage(subject.cover, featuredTitle, Modifier.width(135.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)), ContentScale.Crop)
          }
        }
      }
    }
    items(visible, key = { it.key }) { (path, files) ->
      val binding = catalog.folders[path]
      val subject = catalog.subjects[binding?.subjectId]
      val fallback = libraryGroups[path]?.query.orEmpty()
      val (watched, episodeCount) = watchedCounts[path] ?: (0 to 0)
      Column {
        Card(onClick = { onOpenDetails(path, libraryGroups.getValue(path).directory, subject?.title ?: fallback) }, shape = RoundedCornerShape(20.dp)) {
          Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            if (subject?.cover?.isNotBlank() == true) {
              RemoteImage(subject.cover, subject.title, Modifier.fillMaxSize(), ContentScale.Crop)
            } else {
              Text(fallback, Modifier.align(Alignment.Center).padding(20.dp), style = MaterialTheme.typography.titleMedium)
            }
            Box(Modifier.fillMaxWidth().height(70.dp).align(Alignment.BottomCenter)
              .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f)))))
            if (subject != null && subject.score > 0) {
              Surface(Modifier.align(Alignment.TopEnd).padding(10.dp), color = Color.Black.copy(alpha = .65f), shape = RoundedCornerShape(10.dp)) {
                Text(String.format(java.util.Locale.ROOT, "%.1f", subject.score), Modifier.padding(8.dp, 4.dp), color = Color.White)
              }
            }
            Text(stringResource(R.string.anime_available_count, files.size), Modifier.align(Alignment.BottomStart).padding(12.dp),
              color = Color.White, style = MaterialTheme.typography.labelMedium)
          }
        }
        Text(subject?.title ?: fallback, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall,
          maxLines = 2, overflow = TextOverflow.Ellipsis)
        val matchLabel = when {
          subject != null -> subject.date.take(4).ifBlank { stringResource(R.string.anime_matched) }
          matchProgress.running && matchProgress.current == path -> stringResource(R.string.anime_matching)
          binding?.query?.endsWith(" | request-failed") == true -> stringResource(R.string.anime_match_failed)
          binding?.attemptedAt != null -> stringResource(R.string.anime_no_match)
          else -> stringResource(R.string.anime_unmatched)
        }
        Text(matchLabel,
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (watched > 0) LinearProgressIndicator(progress = { watched.toFloat() / episodeCount.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
      }
    }
    if (visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
      Text(stringResource(R.string.anime_index_loading), Modifier.padding(vertical = 40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
      Text(stringResource(R.string.anime_attribution), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
  if (matchProgress.running) Surface(
    modifier = Modifier.align(Alignment.TopCenter).padding(top = if (isRefreshing) 48.dp else 8.dp),
    shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp,
  ) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
      Text(stringResource(R.string.anime_matching_progress, matchProgress.completed, matchProgress.total), style = MaterialTheme.typography.labelMedium)
    }
  }
  }
  if (detailKey == null && visible.isEmpty()) Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    val readingCache = !snapshot.loaded || (snapshot.groups.isNotEmpty() && searchQuery.isBlank())
    if (readingCache) CircularProgressIndicator(Modifier.size(24.dp))
    Text(stringResource(when {
      readingCache -> R.string.anime_cache_loading
      searchQuery.isNotBlank() -> R.string.ui_no_results_found
      else -> R.string.anime_index_loading
    }))
  }

  detailKey?.let { path ->
    val files = groups[path].orEmpty()
    val binding = catalog.folders[path]
    val subject = catalog.subjects[binding?.subjectId]
    val title = subject?.title ?: libraryGroups[path]?.query.orEmpty()
    var coverRatio by remember(subject?.cover) { mutableFloatStateOf(2f / 3f) }
      LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 40.dp)) {
        item {
          Column {
            if (subject?.cover?.isNotBlank() == true) Box(Modifier.fillMaxWidth().aspectRatio(coverRatio).background(MaterialTheme.colorScheme.surfaceContainer)) {
              RemoteImage(subject.cover, subject.title, Modifier.fillMaxSize(), ContentScale.Fit, Alignment.TopCenter, onAspectRatio = { coverRatio = it })
              Box(Modifier.fillMaxWidth().height(160.dp).align(Alignment.BottomCenter).background(
                Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface))))
            }
            Column(Modifier.padding(24.dp)) {
              Text(title, style = MaterialTheme.typography.headlineMedium)
              Text(listOfNotNull(subject?.date?.take(4), subject?.score?.takeIf { it > 0 }?.let { String.format(java.util.Locale.ROOT, "%.1f", it) },
                subject?.tags?.take(3)?.joinToString(" · ")).joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
            }
          }
        }

        item {
          val directory = libraryGroups[path]?.directory ?: currentPath
          val regularFiles = files.filter { animeSectionPath(directory, it).isEmpty() && !parseAnimeFilename(it.name).special }.ifEmpty { files }
          val resume = regularFiles.firstOrNull { file ->
            playback[app.gyrolet.mpvrx.ui.player.PlaybackIdentity.forNetwork(connection.id, file.path)]?.let {
              it.lastPosition > 0 && !it.hasBeenWatched
            } == true
          } ?: regularFiles.firstOrNull { file -> playback[app.gyrolet.mpvrx.ui.player.PlaybackIdentity.forNetwork(connection.id, file.path)]?.hasBeenWatched != true }
            ?: regularFiles.firstOrNull()
          resume?.let { file ->
            val partial = playback[app.gyrolet.mpvrx.ui.player.PlaybackIdentity.forNetwork(connection.id, file.path)]?.lastPosition?.let { it > 0 } == true
            Button(onClick = { onPlay(file, regularFiles) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
              Text(stringResource(if (partial) R.string.anime_continue else R.string.anime_play))
            }
          }
          Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
              val directory = libraryGroups[path]?.directory ?: currentPath
              onBrowse(NetworkFile(directory.substringAfterLast('/'), directory, 0, true))
            }) { Text(stringResource(R.string.anime_browse_files)) }
            TextButton(onClick = onEditMatch) { Text(stringResource(R.string.anime_match_edit)) }
          }
          subject?.summary?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.padding(24.dp, 12.dp), style = MaterialTheme.typography.bodyMedium)
          }
          Text(stringResource(R.string.anime_episodes), Modifier.padding(24.dp, 12.dp), style = MaterialTheme.typography.titleLarge)
        }
        val directory = libraryGroups[path]?.directory ?: currentPath
        val sections = files.groupBy { animeSectionPath(directory, it) }.toSortedMap()
        sections.forEach { (section, sectionFiles) ->
          item(key = "section:$section") {
            Text(if (section.isEmpty()) stringResource(R.string.anime_regular_episodes) else section.replace("/", " / "),
              Modifier.padding(24.dp, 12.dp), style = MaterialTheme.typography.titleMedium)
          }
          items(sectionFiles, key = { it.path }) { file ->
            val parsed = remember(file.name) { parseAnimeFilename(file.name) }
            val number = parsed.episode?.minus(binding?.episodeOffset ?: 0)
            val episode = subject?.episodes?.firstOrNull { it.number == number && it.type == parsed.episodeType && (section.isEmpty() || it.type != 0) }
            val episodeTitle = episode?.title?.takeIf { it.isNotBlank() } ?: subject?.takeIf { episode != null && it.episodes.count { row -> row.type == episode.type } == 1 }?.title
            val episodeLabel = number?.let { if (it % 1.0 == 0.0) it.toInt().toString().padStart(2, '0') else it.toString() }
            NetworkVideoCard(file, connection, modifier = Modifier.padding(horizontal = 16.dp),
              titleOverride = episodeTitle?.let { "$episodeLabel · $it" },
              onClick = { onPlay(file, sectionFiles) })
          }
        }
        if (subject != null && (subject.cast.isNotEmpty() || subject.staff.isNotEmpty())) {
          item(key = "credits") { AnimeCreditsContent(subject) }
        }

      }
  }

}

@Composable
internal fun AnimeMatchDialog(id: Long, path: String, initialQuery: String, initialOffset: Int, repository: AnimeRepository, onDismiss: () -> Unit) {
  var query by remember(path) { mutableStateOf(initialQuery) }
  var offset by remember(path) { mutableStateOf(initialOffset.toString()) }
  var results by remember(path) { mutableStateOf(emptyList<AnimeSubject>()) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.anime_match_edit)) }, text = {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.anime_search_hint)) }, singleLine = true)
      OutlinedTextField(offset, { offset = it }, label = { Text(stringResource(R.string.anime_offset)) }, singleLine = true)
      Text(stringResource(R.string.anime_offset_hint), style = MaterialTheme.typography.bodySmall)
      if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      LazyColumn(Modifier.heightIn(max = 300.dp)) {
        items(results, key = { it.id }) { subject ->
          TextButton(enabled = !busy && offset.toIntOrNull() != null, onClick = {
            busy = true
            scope.launch {
              try { repository.bind(id, path, subject.id, offset.toInt()); onDismiss() }
              catch (cancelled: CancellationException) { throw cancelled }
              catch (e: Exception) { error = e.message }
              finally { busy = false }
            }
          }) { Text("${subject.title} · ${subject.date.take(4)} · #${subject.id}") }
        }
      }
    }
  }, confirmButton = {
    TextButton(enabled = !busy && query.isNotBlank(), onClick = {
      busy = true; error = null
      scope.launch {
        try { results = query.toLongOrNull()?.takeIf { it > 0 }?.let { listOf(repository.subject(it)) } ?: repository.search(query) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = e.message }
        finally { busy = false }
      }
    }) { Text(stringResource(R.string.anime_search)) }
  }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.anime_close)) } })
}
