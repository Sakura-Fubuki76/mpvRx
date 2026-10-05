package app.gyrolet.mpvrx.ui.browser.networkstreaming

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.gyrolet.mpvrx.presentation.components.RemoteImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

private val backdrops = LruCache<String, Bitmap>(16)

/** Three separable box passes approximate a Gaussian on a small color field, on every API level. */
private fun makeBackdrop(source: Bitmap): Bitmap {
  val scale = 96f / maxOf(source.width, source.height)
  val small = Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1),
    (source.height * scale).toInt().coerceAtLeast(1), true)
  val width = small.width
  val height = small.height
  var pixels = IntArray(width * height)
  small.getPixels(pixels, 0, width, 0, 0, width, height)
  if (small !== source) small.recycle()
  repeat(3) {
    for (horizontal in listOf(true, false)) {
      val result = IntArray(pixels.size)
      for (y in 0 until height) for (x in 0 until width) {
        var red = 0; var green = 0; var blue = 0
        for (delta in -10..10) {
          val color = pixels[(if (horizontal) y else (y + delta).coerceIn(0, height - 1)) * width +
            (if (horizontal) (x + delta).coerceIn(0, width - 1) else x)]
          red += color shr 16 and 255; green += color shr 8 and 255; blue += color and 255
        }
        result[y * width + x] = (255 shl 24) or ((red / 21) shl 16) or ((green / 21) shl 8) or (blue / 21)
      }
      pixels = result
    }
  }
  return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

@Composable
internal fun AnimeBackdrop(url: String, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val client = koinInject<OkHttpClient>()
  var bitmap by remember(url) { mutableStateOf(backdrops.get(url)) }
  LaunchedEffect(url) {
    if (bitmap == null) RemoteImageLoader.load(context, client, url)?.let { source ->
      bitmap = withContext(Dispatchers.Default) { makeBackdrop(source) }.also { backdrops.put(url, it) }
    }
  }
  bitmap?.let {
    Image(remember(it) { it.asImageBitmap() }, null, modifier, contentScale = ContentScale.Crop,
      filterQuality = FilterQuality.High)
  }
}
