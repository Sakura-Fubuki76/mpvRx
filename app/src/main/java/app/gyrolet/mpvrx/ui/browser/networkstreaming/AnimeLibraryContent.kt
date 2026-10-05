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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.cloud.*
import app.gyrolet.mpvrx.domain.network.*
import app.gyrolet.mpvrx.repository.*
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.browser.cards.NetworkVideoCard
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
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
  onEditPoster: () -> Unit = {},
  onEditLogo: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val preferences = koinInject<app.gyrolet.mpvrx.preferences.BrowserPreferences>()
  val fields = preferences.animeFields
  val fullName by fields.unlimitedNameLines.collectAsState()
  val showPoster by fields.showFolderThumbnails.collectAsState()
  val centerTitles by fields.centerGridTitles.collectAsState()
  val showCount by fields.showTotalVideosChip.collectAsState()
  val showProgress by fields.showProgressBar.collectAsState()
  val showPath by fields.showFolderPath.collectAsState()
  val showTotalSize by fields.showTotalSizeChip.collectAsState()
  val showTotalDuration by fields.showTotalDurationChip.collectAsState()
  val sortType by preferences.animeSortType.collectAsState()
  val sortOrder by preferences.animeSortOrder.collectAsState()
  val cloud = koinInject<CloudMetadataRepository>()
  val anime = koinInject<AnimeRepository>()
  val library = remember(connection.id, currentPath) {
    anime.observeLibrary(connection.id, currentPath, cloud.observeLibraryData(connection.id, currentPath, videosOnly = true))
  }
  val snapshot by library.collectAsState()
  LaunchedEffect(snapshot.loaded) {
    if (snapshot.loaded) CloudTrace.event("anime.library.ready", connection.id, currentPath, "videos=${snapshot.files.size} groups=${snapshot.groups.size}")
  }
  val allFiles = snapshot.files
  val libraryGroups = snapshot.groups
  val catalog by remember(connection.id) { anime.observe(connection.id) }.collectAsState()
  val artworkIds = remember(libraryGroups, catalog.folders) {
    libraryGroups.keys.mapNotNull { catalog.folders[it]?.subjectId }.distinct().sorted()
  }
  val advanced = koinInject<app.gyrolet.mpvrx.preferences.AdvancedPreferences>()
  val artworkToken by advanced.tmdbArtworkToken.collectAsState()
  val artworkKey by advanced.tmdbArtworkApiKey.collectAsState()
  LaunchedEffect(artworkIds, artworkToken, artworkKey, detailKey, showPoster) {
    if (detailKey == null && showPoster) anime.scheduleLibraryArtwork(artworkIds)
  }
  val matchProgress by remember(connection.id) { anime.observeProgress(connection.id) }.collectAsState()
  val playbackDao = koinInject<app.gyrolet.mpvrx.database.MpvRxDatabase>().videoDataDao()
  val playback by remember(connection.id, currentPath) {
    anime.observePlayback(connection.id, currentPath, library, playbackDao)
  }.collectAsState()
  val groups = remember(libraryGroups) { libraryGroups.mapValues { it.value.files } }
  val cloudMetadata = koinInject<app.gyrolet.mpvrx.repository.CloudMetadataRepository>()
  val showVideoThumbnails by fields.showVideoThumbnails.collectAsState()
  val detailFiles = libraryGroups.values.firstOrNull { it.key == detailKey || detailKey in it.sourceKeys }?.files.orEmpty()
  val detailVersions = remember(detailFiles) { detailFiles.map { Triple(it.path, it.size, it.lastModified) } }
  LaunchedEffect(connection.id, detailKey, detailVersions, showVideoThumbnails) {
    if (detailKey != null && detailFiles.isNotEmpty()) {
      cloudMetadata.cacheMissingMetadata(connection, detailFiles,
        app.gyrolet.mpvrx.domain.cloud.MetadataRequestPriority.FOREGROUND, showVideoThumbnails)
    }
  }
  LaunchedEffect(connection.id, snapshot.playbackIdentities) {
    kotlinx.coroutines.delay(750)
    if (detailKey == null && snapshot.loaded && allFiles.isNotEmpty()) anime.schedule(connection.id, allFiles)
  }

  val visible by produceState(initialValue = emptyList<Map.Entry<String, List<NetworkFile>>>(), snapshot, catalog, searchQuery, detailKey, sortType, sortOrder) {
    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
      val sortItems = groups.keys.associateWith { key ->
        val subject = catalog.subjects[catalog.folders[key]?.subjectId]
        AnimeSortItem(key, subject?.title ?: libraryGroups[key]?.query ?: key, subject?.date.orEmpty(), subject?.score ?: 0.0)
      }
      if (detailKey != null || !snapshot.loaded || !catalog.loaded) emptyList() else groups.entries.filter { (path, files) ->
        searchQuery.isBlank() || path.contains(searchQuery, true) || files.any { it.name.contains(searchQuery, true) } ||
          catalog.subjects[catalog.folders[path]?.subjectId]?.title?.contains(searchQuery, true) == true
      }.sortedWith(Comparator { left, right ->
        compareAnimeSort(
          sortItems.getValue(left.key), sortItems.getValue(right.key),
          sortType, sortOrder.isAscending,
        )
      })
    }
  }
  // Structure work is independent of artwork/metadata updates. Never render an empty
  // asynchronous count map when this composition is recreated after a details page.
  val episodeIdentities = remember(libraryGroups, snapshot.playbackIdentities) {
    libraryGroups.mapValues { (_, group) -> group.files.filterNot(::isAnimeExtraVideo)
      .map { snapshot.playbackIdentities[it.path] } }
  }
  val watchedCounts = remember(episodeIdentities, playback) {
    episodeIdentities.mapValues { (_, identities) ->
      identities.count { playback[it]?.hasBeenWatched == true } to identities.size
    }
  }
  val retainedGridState = libraryGridState ?: rememberLazyGridState()
  if (detailKey == null && visible.isNotEmpty()) PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { onRefresh(); anime.schedule(connection.id, allFiles, retryUnmatched = true) }, modifier = modifier.fillMaxSize()) {
  val featured = visible.firstOrNull { it.key == snapshot.featuredKey }
    ?: visible.firstOrNull { catalog.subjects[catalog.folders[it.key]?.subjectId]?.cover?.isNotBlank() == true }
    ?: visible.firstOrNull()
  val scrollLabels = remember(visible, catalog, libraryGroups, featured?.key, sortType) {
    fun sortItem(key: String): AnimeSortItem {
      val subject = catalog.subjects[catalog.folders[key]?.subjectId]
      return AnimeSortItem(key, subject?.title ?: libraryGroups[key]?.query ?: key, subject?.date.orEmpty(), subject?.score ?: 0.0)
    }
    animeFastScrollLabels(visible.map { sortItem(it.key) }, featured?.let { sortItem(it.key) }, sortType)
  }
  LazyVerticalGrid(state = retainedGridState, columns = GridCells.Adaptive(145.dp), modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    if (featured != null) item(key = "anime-featured", span = { GridItemSpan(maxLineSpan) }) {
      val subject = catalog.subjects[catalog.folders[featured.key]?.subjectId]
      val featuredTitle = subject?.title ?: libraryGroups[featured.key]?.query.orEmpty()
      Card(onClick = { onOpenDetails(featured.key, libraryGroups.getValue(featured.key).directory, featuredTitle) }, shape = RoundedCornerShape(24.dp)) {
        Box(Modifier.fillMaxWidth().height(280.dp)) {
            if (showPoster && subject != null) RemoteImage(subject.libraryCover, null, Modifier.fillMaxSize(), ContentScale.Crop, alpha = .25f)
          Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surfaceContainer, Color.Transparent))))
          Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Text(featuredTitle, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
              val featuredFields = listOfNotNull(
                subject?.date?.take(4)?.takeIf { it.isNotBlank() },
                subject?.score?.takeIf { it > 0 }?.let { String.format(java.util.Locale.ROOT, "%.1f", it) },
              ).joinToString("  ·  ")
              if (featuredFields.isNotEmpty()) Text(featuredFields, style = MaterialTheme.typography.labelLarge)
              Text(subject?.summary.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (showPoster && subject != null) RemoteImage(subject.libraryCover, featuredTitle, Modifier.width(135.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)), ContentScale.Crop)
          }
        }
      }
    }
    items(visible, key = { it.key }) { (path, files) ->
      val binding = catalog.folders[path]
      val subject = catalog.subjects[binding?.subjectId]
      val fallback = libraryGroups[path]?.query.orEmpty()
      val (watched, episodeCount) = watchedCounts[path] ?: (0 to 0)
      Column(horizontalAlignment = if (centerTitles) Alignment.CenterHorizontally else Alignment.Start) {
        Card(onClick = { onOpenDetails(path, libraryGroups.getValue(path).directory, subject?.title ?: fallback) }, shape = RoundedCornerShape(20.dp)) {
          Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            if (showPoster && subject?.cover?.isNotBlank() == true) {
              RemoteImage(subject.libraryCover, subject.title, Modifier.fillMaxSize(), ContentScale.Crop)
            } else {
              Text(fallback, Modifier.align(Alignment.Center).padding(20.dp), style = MaterialTheme.typography.titleMedium)
            }
            Box(Modifier.fillMaxWidth().height(70.dp).align(Alignment.BottomCenter)
              .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f)))))
            if (subject != null && subject.score > 0) {
              Surface(Modifier.align(Alignment.TopEnd).padding(10.dp), color = Color.Black.copy(alpha = .55f), shape = RoundedCornerShape(10.dp)) {
                Text(String.format(java.util.Locale.ROOT, "%.1f", subject.score), Modifier.padding(8.dp, 5.dp), color = Color.White,
                  style = MaterialTheme.typography.labelLarge)
              }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showCount) Surface(color = Color.Black.copy(alpha = .55f), shape = RoundedCornerShape(10.dp)) {
                  Text(stringResource(R.string.anime_available_count, files.size), Modifier.padding(8.dp, 5.dp),
                    color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                if (showProgress && watched > 0) Surface(color = Color.Black.copy(alpha = .55f), shape = RoundedCornerShape(10.dp)) {
                  Text("$watched / $episodeCount", Modifier.padding(8.dp, 5.dp), color = Color.White,
                    style = MaterialTheme.typography.labelMedium)
                }
              }
              if (showProgress && watched > 0) LinearProgressIndicator(
                progress = { (watched.toFloat() / episodeCount.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp), color = Color.White,
                trackColor = Color.White.copy(alpha = .28f), gapSize = 0.dp, drawStopIndicator = {},
              )
            }
          }
        }
        Text(subject?.title ?: fallback, Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall,
          maxLines = if (fullName) Int.MAX_VALUE else 2, textAlign = if (centerTitles) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start, overflow = TextOverflow.Ellipsis)
        val matchLabel = when {
          subject != null -> subject.date.take(4)
          matchProgress.running && matchProgress.current == path -> stringResource(R.string.anime_matching)
          binding?.query?.endsWith(" | request-failed") == true -> stringResource(R.string.anime_match_failed)
          binding?.attemptedAt != null -> stringResource(R.string.anime_no_match)
          else -> stringResource(R.string.anime_unmatched)
        }
        if (matchLabel.isNotBlank()) Text(matchLabel,
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showPath) Text(libraryGroups[path]?.directory.orEmpty(), style = MaterialTheme.typography.bodySmall,
          maxLines = if (fullName) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
        val totals = buildList {
          if (showTotalSize) add(android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, files.sumOf { it.size.coerceAtLeast(0) }))
          if (showTotalDuration) {
            val seconds = files.sumOf { it.durationMs.coerceAtLeast(0) } / 1000
            if (seconds > 0) add(String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60))
          }
        }
        if (totals.isNotEmpty()) Text(totals.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
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
  if (visible.size >= 20) ExpressiveScrollBar(
    gridState = retainedGridState,
    dragLabelProvider = { index -> scrollLabels.getOrNull(index) },
    dragLabelSize = if (sortType == AnimeSortType.Year) 64.dp else 48.dp,
    modifier = Modifier.align(Alignment.CenterEnd).padding(top = 8.dp, bottom = 96.dp, end = 4.dp),
  )
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

  detailKey?.let { requested ->
    val path = libraryGroups.entries.firstOrNull { it.key == requested || requested in it.value.sourceKeys }?.key ?: requested
    val files = groups[path].orEmpty()
    val binding = catalog.folders[path]
    val subject = catalog.subjects[binding?.subjectId]
    val title = subject?.title ?: libraryGroups[path]?.query.orEmpty()
    val detailCover = subject?.detailCover.orEmpty()
    var coverRatio by remember(detailCover) { mutableFloatStateOf(2f / 3f) }
      LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 40.dp)) {
        item {
          Column {
            if (showPoster && detailCover.isNotBlank()) Box(Modifier.fillMaxWidth().aspectRatio(coverRatio)) {
              RemoteImage(detailCover, title, Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                  drawContent()
                  drawRect(Brush.verticalGradient(0f to Color.White, .45f to Color.White, .95f to Color.Transparent),
                    blendMode = BlendMode.DstIn)
                }, ContentScale.Fit, Alignment.TopCenter, onAspectRatio = { coverRatio = it })
              AnimeDetailsTitle(title, subject?.displayLogo.orEmpty(), showPoster,
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 16.dp))
            }
            Column(Modifier.padding(24.dp)) {
              if (!showPoster || detailCover.isBlank()) AnimeDetailsTitle(title, "", false)
              Text(listOfNotNull(subject?.date?.take(4), subject?.score?.takeIf { it > 0 }?.let { String.format(java.util.Locale.ROOT, "%.1f", it) },
                subject?.tags?.take(3)?.joinToString(" · ")).joinToString("  ·  "), Modifier.fillMaxWidth().padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
          }
        }

        item {
          val directory = libraryGroups[path]?.directory ?: currentPath
          val regularFiles = files.filter { (it.path in libraryGroups[path]?.regularPaths.orEmpty() || animeSectionPath(directory, it).isEmpty()) && !parseAnimeFilename(it.name).special }.ifEmpty { files }
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
          FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
              val directory = libraryGroups[path]?.directory ?: currentPath
              onBrowse(NetworkFile(directory.substringAfterLast('/'), directory, 0, true))
            }) { Text(stringResource(R.string.anime_browse_files)) }
            TextButton(onClick = onEditMatch) { Text(stringResource(R.string.anime_match_edit)) }
            if (subject != null) {
              TextButton(onClick = onEditPoster) { Text(stringResource(R.string.anime_poster_match)) }
              TextButton(onClick = onEditLogo) { Text(stringResource(R.string.anime_logo_match)) }
            }
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
            val offset = libraryGroups[path]?.episodeOffsets?.get(file.path) ?: binding?.episodeOffset ?: 0
            val episode = subject?.let { animeMappedEpisode(it, file.name, offset, section.isEmpty() || file.path in libraryGroups[path]?.regularPaths.orEmpty()) }
            val number = episode?.number ?: parsed.episode?.minus(offset)
            val episodeTitle = episode?.title?.takeIf { it.isNotBlank() } ?: subject?.takeIf { episode != null && it.episodes.count { row -> row.type == episode.type } == 1 }?.title
            val episodeLabel = number?.let { if (it % 1.0 == 0.0) it.toInt().toString().padStart(2, '0') else it.toString() }
            NetworkVideoCard(file, connection, modifier = Modifier.padding(horizontal = 16.dp),
              titleOverride = episodeTitle, episodeLabel = episodeLabel.takeIf { episode != null || section.isEmpty() },
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
private fun AnimeDetailsTitle(title: String, logo: String, showLogo: Boolean, modifier: Modifier = Modifier) {
  var logoReady by remember(logo) { mutableStateOf(false) }
  Box(modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.Center) {
    if (showLogo && logo.isNotBlank()) RemoteImage(logo, title,
      Modifier.fillMaxWidth(.8f).height(112.dp), ContentScale.Fit,
      onAspectRatio = { logoReady = true })
    if (!showLogo || !logoReady) Text(title, Modifier.fillMaxWidth(),
      style = MaterialTheme.typography.headlineMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
