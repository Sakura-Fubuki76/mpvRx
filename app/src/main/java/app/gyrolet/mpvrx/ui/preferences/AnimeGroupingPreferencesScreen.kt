package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.cloud.*
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.repository.*
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject

@Serializable
object AnimeGroupingPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val back = LocalBackStack.current
    val network = koinInject<NetworkRepository>()
    val anime = koinInject<AnimeRepository>()
    val cloud = koinInject<CloudMetadataRepository>()
    val servers by remember { network.getAllConnections() }.collectAsState(emptyList())
    val animeServers = servers.filter { it.isAnime }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val selected = animeServers.firstOrNull { it.id == selectedId } ?: animeServers.firstOrNull()
    val scope = rememberCoroutineScope()
    var source by remember(selected?.id) { mutableStateOf<String?>(null) }
    var filter by remember(selected?.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val files by remember(selected?.id) {
      selected?.let { cloud.observeLibrary(it.id, "/") } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(emptyList())
    val catalog by remember(selected?.id) {
      selected?.let { anime.observe(it.id) } ?: kotlinx.coroutines.flow.flowOf(AnimeCatalog(emptyMap(), emptyMap()))
    }.collectAsState(AnimeCatalog(emptyMap(), emptyMap()))
    val groups = remember(files) { animeVideoGroups(files) }
    val paths = remember(files) {
      (files.map { it.path } + files.flatMap { file ->
        val segments = file.path.split('/').filter { it.isNotBlank() }.dropLast(1)
        (1..segments.size).map { "/" + segments.take(it).joinToString("/") }
      }).distinct().sorted()
    }
    val assignments = catalog.folders.values.filter { it.query.startsWith("#group:") }
    Scaffold(topBar = {
      TopAppBar(title = { Text(stringResource(R.string.anime_grouping)) }, navigationIcon = {
        IconButton(onClick = { back.popSafely() }) { Icon(Icons.RoundedFilled.ArrowBack, contentDescription = null) }
      })
    }) { padding ->
      LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.anime_grouping_hint)) }
        items(animeServers, key = { "server:${it.id}" }) { server ->
          FilterChip(selected = selected?.id == server.id, onClick = { selectedId = server.id }, label = { Text(server.name) })
        }
        if (selected == null || paths.isEmpty()) item { Text(stringResource(R.string.anime_group_empty)) }
        if (assignments.isNotEmpty()) item { Text(stringResource(R.string.anime_group_saved), style = MaterialTheme.typography.titleMedium) }
        items(assignments, key = { "mapping:${it.path}" }) { assignment ->
          val target = assignment.query.removePrefix("#group:")
          val title = catalog.subjects[catalog.folders[target]?.subjectId]?.title ?: groups.firstOrNull { it.key == target }?.query ?: target
          Column {
            Text(assignment.path.removePrefix("/#assignment") + " → " + title)
            TextButton(enabled = !busy, onClick = {
              scope.launch {
                busy = true
                try { anime.assign(requireNotNull(selected).id, assignment.path.removePrefix("/#assignment"), null) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { error = e.message }
                finally { busy = false }
              }
            }) { Text(stringResource(R.string.anime_group_restore)) }
          }
        }
        item {
          OutlinedTextField(filter, { filter = it }, label = { Text(stringResource(R.string.anime_group_source)) }, modifier = Modifier.fillMaxWidth())
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        items(paths.filter { filter.isBlank() || it.contains(filter, true) }, key = { "path:$it" }) { path ->
          TextButton(onClick = { source = path }, modifier = Modifier.fillMaxWidth()) { Text(path) }
        }
      }
    }
    source?.let { path ->
      AlertDialog(onDismissRequest = { if (!busy) source = null }, title = { Text(stringResource(R.string.anime_group_target)) },
        text = {
          LazyColumn(Modifier.heightIn(max = 400.dp)) {
            item { Text(path) }
            items(groups.sortedBy { catalog.subjects[catalog.folders[it.key]?.subjectId]?.title ?: it.query }, key = { it.key }) { group ->
              TextButton(enabled = !busy, onClick = {
                scope.launch {
                  busy = true
                  try { anime.assign(requireNotNull(selected).id, path, group.key); source = null }
                  catch (cancelled: CancellationException) { throw cancelled }
                  catch (e: Exception) { error = e.message }
                  finally { busy = false }
                }
              }) { Text(catalog.subjects[catalog.folders[group.key]?.subjectId]?.title ?: group.query) }
            }
          }
        }, confirmButton = {}, dismissButton = {
          TextButton(enabled = !busy, onClick = { source = null }) { Text(stringResource(R.string.anime_close)) }
        })
    }
  }
}
