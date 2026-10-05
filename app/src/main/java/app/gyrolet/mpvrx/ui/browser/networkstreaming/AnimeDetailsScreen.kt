package app.gyrolet.mpvrx.ui.browser.networkstreaming

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.repository.AnimeCatalog
import app.gyrolet.mpvrx.repository.AnimeRepository
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
data class AnimeDetailsScreen(
  val connectionId: Long,
  val connectionName: String,
  val groupKey: String,
  val directory: String,
  val initialTitle: String,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val model: NetworkBrowserViewModel = viewModel(
      key = "AnimeDetails_${connectionId}_$groupKey",
      factory = NetworkBrowserViewModel.factory(context.applicationContext as Application, connectionId, directory),
    )
    LaunchedEffect(connectionId, directory) { model.loadAnimeConnection() }
    DisposableEffect(model) { onDispose { model.pauseBackgroundWork() } }
    val connection by model.connection.collectAsState()
    val repository = koinInject<AnimeRepository>()
    val catalog by remember(connectionId) { repository.observe(connectionId) }
      .collectAsState()
    val subjectId = catalog.folders[groupKey]?.subjectId
    LaunchedEffect(subjectId) {
      subjectId?.let { kotlinx.coroutines.delay(500); repository.scheduleCredits(it) }
    }
    val title = catalog.subjects[catalog.folders[groupKey]?.subjectId]?.title ?: initialTitle
    var editMatch by remember { mutableStateOf(false) }
    val useCoverColors by koinInject<AppearancePreferences>().animeCoverColors.collectAsState()
    val cover = catalog.subjects[catalog.folders[groupKey]?.subjectId]?.cover
    val showPoster by koinInject<app.gyrolet.mpvrx.preferences.BrowserPreferences>().animeFields.showFolderThumbnails.collectAsState()
    AnimeCoverTheme(cover, useCoverColors) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
      // A stationary layer reuses the cover cache; scrolling never recomputes a bitmap blur.
      if (showPoster && !cover.isNullOrBlank()) {
        RemoteImage(cover, null, Modifier.matchParentSize().scale(1.45f).blur(120.dp), ContentScale.Crop)
        val surface = MaterialTheme.colorScheme.surface
        val dark = surface.luminance() < .5f
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
          surface.copy(alpha = if (dark) .58f else .62f),
          surface.copy(alpha = if (dark) .78f else .76f),
        ))))
      }
      connection?.let { server ->
        AnimeLibraryContent(server, "/", "",
          onPlay = { file, queue -> model.playAnimeVideo(file, queue) },
          onBrowse = { folder -> backstack.navigateTo(NetworkBrowserScreen(connectionId, connectionName, folder.path, showAnimeLibrary = false)) },
          onRefresh = { model.loadFiles(forceStorageScan = true) },
          detailKey = groupKey, onEditMatch = { editMatch = true })
      }
    IconButton(onClick = { backstack.popSafely() }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp).background(Color.Black.copy(alpha = .45f), CircleShape)) {
      Icon(Icons.RoundedFilled.ArrowBack, contentDescription = null, tint = Color.White)
    }
    }
    if (editMatch) AnimeMatchDialog(connectionId, groupKey, initialTitle,
      catalog.folders[groupKey]?.episodeOffset ?: 0, repository) { editMatch = false }
    }
  }
}
