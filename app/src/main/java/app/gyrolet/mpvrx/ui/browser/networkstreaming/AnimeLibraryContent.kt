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
  val allFiles by remember(connection.id, currentPath) { cloud.observeLibrary(connection.id, currentPath, videosOnly = true) }.collectAsState()
  val catalog by remember(connection.id) { anime.observe(connection.id) }.collectAsState()
  val playbackDao = koinInject<app.gyrolet.mpvrx.database.MpvRxDatabase>().videoDataDao()
  val identities = remember(connection.id, allFiles) { allFiles.filterNot { it.isDirectory }.map {
    app.gyrolet.mpvrx.ui.player.PlaybackIdentity.forNetwork(connection.id, it.path)
  } }
  val playback by remember(identities) {
    if (identities.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyMap<String, app.gyrolet.mpvrx.database.entities.PlaybackStateEntity>())
    else kotlinx.coroutines.flow.combine(identities.chunked(400).map { playbackDao.observeVideoStates(it) }) { rows ->
      rows.flatMap { it }.associateBy { it.mediaTitle }
    }
  }.collectAsState(emptyMap())
  val structureKey = remember(allFiles) { allFiles.map { Triple(it.path, it.name, it.isDirectory) } }
  val structure = remember(structureKey) { animeVideoGroups(allFiles).map { group -> group.copy(files = group.files.sortedWith(
    compareBy<NetworkFile> { animeSectionPath(group.directory, it) }
      .thenBy { parseAnimeFilename(it.name).episode ?: Double.MAX_VALUE }.thenBy { it.name })) } }
  val libraryGroups = remember(allFiles, structure) {
    val latest = allFiles.associateBy { it.path }
    structure.associate { group -> group.key to group.copy(files = group.files.mapNotNull { latest[it.path] }) }
  }
  val recent by koinInject<app.gyrolet.mpvrx.database.MpvRxDatabase>().recentlyPlayedDao().observeRecentlyPlayed(1000).collectAsState(emptyList())
  val groups = remember(libraryGroups) { libraryGroups.mapValues { it.value.files } }
  LaunchedEffect(connection.id, groups.keys, allFiles.map { it.path to it.lastModified }) {
    kotlinx.coroutines.delay(750)
    if (detailKey == null) anime.schedule(connection.id, allFiles)
  }

  val visible = remember(groups, catalog, searchQuery) {
    groups.entries.filter { (path, files) ->
      searchQuery.isBlank() || path.contains(searchQuery, true) || files.any { it.name.contains(searchQuery, true) } ||
        catalog.subjects[catalog.folders[path]?.subjectId]?.title?.contains(searchQuery, true) == true
    }.sortedBy { (path, _) -> catalog.subjects[catalog.folders[path]?.subjectId]?.title ?: path }
  }
  val retainedGridState = libraryGridState ?: rememberLazyGridState()
  if (detailKey == null && visible.isNotEmpty()) PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { onRefresh(); anime.schedule(connection.id, allFiles, force = true) }, modifier = modifier.fillMaxSize()) {
  LazyVerticalGrid(state = retainedGridState, columns = GridCells.Adaptive(145.dp), modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    val recentKey = recent.firstNotNullOfOrNull { row ->
      val reference = NetworkPlaybackUri.parse(row.filePath)
      if (reference?.connectionId == connection.id) visible.firstOrNull { (_, files) -> files.any { it.path == reference.path.value } }?.key else null
    }
    val featured = visible.firstOrNull { it.key == recentKey } ?: visible.firstOrNull { catalog.subjects[catalog.folders[it.key]?.subjectId]?.cover?.isNotBlank() == true }
    if (featured != null) item(span = { GridItemSpan(maxLineSpan) }) {
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
            if (subject != null) RemoteImage(subject.cover, featuredTitle, Modifier.width(135.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)), ContentScale.Fit)
          }
        }
      }
    }
    items(visible, key = { it.key }) { (path, files) ->
      val binding = catalog.folders[path]
      val subject = catalog.subjects[binding?.subjectId]
      val fallback = libraryGroups[path]?.query.orEmpty()
      val watched = files.filterNot(::isAnimeExtraVideo).count { playback[app.gyrolet.mpvrx.ui.player.PlaybackIdentity.forNetwork(connection.id, it.path)]?.hasBeenWatched == true }
      Column {
        Card(onClick = { onOpenDetails(path, libraryGroups.getValue(path).directory, subject?.title ?: fallback) }, shape = RoundedCornerShape(20.dp)) {
          Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            if (subject?.cover?.isNotBlank() == true) {
              RemoteImage(subject.cover, subject.title, Modifier.fillMaxSize(), ContentScale.Fit)
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
        Text(subject?.date?.take(4)?.ifBlank { null } ?: stringResource(R.string.anime_unmatched),
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (watched > 0) LinearProgressIndicator(progress = { watched.toFloat() / files.count { !isAnimeExtraVideo(it) }.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
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
  }
  if (detailKey == null && visible.isEmpty()) Text(stringResource(R.string.anime_index_loading), modifier.padding(24.dp))
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
            val episodeLabel = number?.let { if (it % 1.0 == 0.0) it.toInt().toString().padStart(2, '0') else it.toString() }
            NetworkVideoCard(file, connection, modifier = Modifier.padding(horizontal = 16.dp),
              titleOverride = episode?.title?.takeIf { it.isNotBlank() }?.let { "$episodeLabel · $it" },
              onClick = { onPlay(file, sectionFiles) })
          }
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
