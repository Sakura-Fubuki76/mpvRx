package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.cloud.CloudSpriteRepository
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import org.koin.compose.koinInject

/** Crops directly from the sheet instead of allocating a Bitmap on every seek tick. */
@Composable
internal fun CloudSeekPreview(position: Float, modifier: Modifier = Modifier) {
  val repository = koinInject<CloudSpriteRepository>()
  val sheet by repository.current.collectAsState()
  val session by PlaybackSession.state.collectAsState()
  val current = sheet?.takeIf { it.mediaId == session.currentItem?.stableId } ?: return
  val meta = current.metadata
  val index = meta.frameIndex((position * 1000).toLong())
  val image = remember(current.bitmap) { current.bitmap.asImageBitmap() }
  Column(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainer),
    horizontalAlignment = Alignment.CenterHorizontally) {
    Canvas(Modifier.requiredSize(160.dp, 90.dp)) {
      drawImage(image, srcOffset = IntOffset(index % meta.columns * meta.cellWidth, index / meta.columns * meta.cellHeight),
        srcSize = IntSize(meta.cellWidth, meta.cellHeight), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    }
    val seconds = position.toInt().coerceAtLeast(0)
    Text("%d:%02d".format(seconds / 60, seconds % 60), modifier = Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall)
  }
}
