package app.gyrolet.mpvrx.ui.browser.networkstreaming

import android.util.LruCache
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import app.gyrolet.mpvrx.presentation.components.RemoteImageLoader
import app.gyrolet.mpvrx.ui.theme.CustomThemeDefinition
import app.gyrolet.mpvrx.ui.theme.extractThemeFromWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

private val coverThemes = LruCache<String, CustomThemeDefinition>(64)

/** Reuse the cover image cache and generate colours off the UI thread. */
@Composable
internal fun AnimeCoverTheme(cover: String?, enabled: Boolean, content: @Composable () -> Unit) {
  val context = LocalContext.current.applicationContext
  val client = koinInject<OkHttpClient>()
  var definition by remember(cover, enabled) { mutableStateOf(if (enabled && cover != null) coverThemes.get(cover) else null) }
  LaunchedEffect(cover, enabled) {
    if (enabled && !cover.isNullOrBlank() && definition == null) {
      definition = withContext(Dispatchers.IO) {
        RemoteImageLoader.load(context, client, cover)?.let { bitmap ->
          extractThemeFromWallpaper(bitmap)?.also { coverThemes.put(cover, it) }
        }
      }
    }
  }
  val base = MaterialTheme.colorScheme
  val dark = base.background.luminance() < .5f
  val colors = remember(definition, base, dark) {
    definition?.let { if (dark) it.darkColorScheme() else it.lightColorScheme() } ?: base
  }
  MaterialTheme(colorScheme = colors, content = content)
}
