package app.gyrolet.mpvrx.ui.browser.networkstreaming

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.cloud.*
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.repository.*
import app.gyrolet.mpvrx.ui.browser.cards.NetworkVideoCard
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.sort.SortUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

private data class RelatedAnimeVideos(val connection: NetworkConnection, val group: AnimeVideoGroup, val subject: AnimeSubject, val offset: Int)
private data class RelatedVideosState(val groups: List<RelatedAnimeVideos> = emptyList(), val loaded: Boolean = false)

/** Joins provider identities with persisted libraries; never starts scraping or directory scans. */
@Serializable
data class AnimeRelatedVideosScreen(val credit: AnimeCreditSelection) : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
  @Composable
  override fun Content() {
    val anime = koinInject<AnimeRepository>()
    val cloud = koinInject<CloudMetadataRepository>()
    val network = koinInject<NetworkRepository>()
    val backstack = LocalBackStack.current
    var relatedIds by remember(credit) { mutableStateOf(emptySet<Long>()) }
    var loading by remember(credit) { mutableStateOf(true) }
    var failed by remember(credit) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(credit, retry) {
      loading = true
      failed = false
      relatedIds = anime.cachedCreditSubjects(credit)
      try { relatedIds = anime.creditSubjects(credit, force = retry > 0) }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { failed = true }
      finally { loading = false }
    }
    val library by produceState(RelatedVideosState(), credit, relatedIds) {
      network.getAllConnections().flatMapLatest { connections ->
        val sources = connections.filter { it.isAnime }.map { connection ->
          combine(anime.observe(connection.id), anime.observeLibrary(connection.id, "/",
            cloud.observeLibraryData(connection.id, "/", videosOnly = true))) { catalog, snapshot ->
            RelatedVideosState(snapshot.groups.values.mapNotNull { group ->
              val binding = catalog.folders[group.key] ?: return@mapNotNull null
              val subject = catalog.subjects[binding.subjectId] ?: return@mapNotNull null
              if (subject.id !in relatedIds && !animeCreditMatches(subject, credit)) return@mapNotNull null
              RelatedAnimeVideos(connection, group, subject, binding.episodeOffset)
            }, catalog.loaded && snapshot.loaded)
          }
        }
        if (sources.isEmpty()) flowOf(RelatedVideosState(loaded = true))
        else combine(sources) { states ->
          RelatedVideosState(states.flatMap { it.groups }.sortedWith(
            compareBy<RelatedAnimeVideos, String>(SortUtils.NaturalOrderComparator.DEFAULT) { it.subject.title }
              .thenBy { it.connection.id }.thenBy { it.group.key }), states.all { it.loaded })
        }
      }.flowOn(kotlinx.coroutines.Dispatchers.Default).collect { value = it }
    }
    Scaffold(topBar = {
      TopAppBar(title = { Text(credit.name) }, navigationIcon = {
        IconButton(onClick = { backstack.popSafely() }) { Icon(Icons.RoundedFilled.ArrowBack, contentDescription = null) }
      })
    }) { padding ->
      LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
          Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.anime_related_videos), style = MaterialTheme.typography.titleMedium)
            if (loading || !library.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) {
              Text(stringResource(R.string.anime_related_retry), style = MaterialTheme.typography.bodyMedium)
              TextButton(onClick = { retry++ }) { Text(stringResource(R.string.anime_related_refresh)) }
            }
            if (!loading && library.loaded && library.groups.isEmpty()) Text(stringResource(R.string.anime_related_empty))
          }
        }
        library.groups.forEach { related ->
          item(key = "${related.connection.id}:${related.group.key}:header") {
            Surface(onClick = { backstack.navigateTo(AnimeDetailsScreen(related.connection.id, related.connection.name,
              related.group.key, related.group.directory, related.subject.title)) }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
              Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteImage(related.subject.cover, null, Modifier.size(48.dp, 72.dp), ContentScale.Crop)
                Column {
                  Text(related.subject.title, style = MaterialTheme.typography.titleMedium)
                  Text(related.connection.name, style = MaterialTheme.typography.bodySmall)
                }
              }
            }
          }
          items(related.group.files, key = { "${related.connection.id}:${it.path}" }) { file ->
            val application = LocalContext.current.applicationContext as Application
            val model: NetworkBrowserViewModel = viewModel(key = "AnimeRelatedPlayer_${related.connection.id}",
              factory = NetworkBrowserViewModel.factory(application, related.connection.id, "/"))
            val episode = remember(file.name, related.group.episodeOffsets, related.subject, related.offset) {
              animeMappedEpisode(related.subject, file.name, related.group.episodeOffsets[file.path] ?: related.offset,
                file.path in related.group.regularPaths || animeSectionPath(related.group.directory, file).isEmpty())
            }
            val label = episode?.number?.let { if (it % 1.0 == 0.0) it.toInt().toString().padStart(2, '0') else it.toString() }
            val title = episode?.title?.takeIf { it.isNotBlank() }?.let { "$label · $it" }
            NetworkVideoCard(file, related.connection, modifier = Modifier.padding(horizontal = 16.dp), titleOverride = title,
              onClick = { model.playAnimeVideo(file, related.group.files) })
          }
        }
      }
    }
  }
}
