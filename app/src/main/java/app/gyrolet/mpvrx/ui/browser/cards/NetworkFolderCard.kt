/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvContextMenu
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import org.koin.compose.koinInject

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextAlign
import app.gyrolet.mpvrx.preferences.BrowserPreferences

@Composable
fun NetworkFolderCard(
  file: NetworkFile,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  isSelected: Boolean = false,
  isGridMode: Boolean = false,
  useLibraryStyle: Boolean = false,
  connectionId: Long = 0,
) {
  if (useLibraryStyle) {
    val browser = koinInject<BrowserPreferences>()
    val appearance = koinInject<AppearancePreferences>()
    val showFolderImages by browser.showFolderThumbnails.collectAsState()
    val showNetworkImages by appearance.showNetworkThumbnails.collectAsState()
    var preview by remember(connectionId, file.path) { androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(connectionId, file.path, showFolderImages, showNetworkImages, isGridMode) {
      preview = null
      if (isGridMode && showFolderImages && showNetworkImages) {
        preview = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
          val dao = org.koin.java.KoinJavaComponent.get<app.gyrolet.mpvrx.database.dao.CloudMetadataDao>(app.gyrolet.mpvrx.database.dao.CloudMetadataDao::class.java)
          val video = dao.getDirectory(connectionId, file.path).firstOrNull { !it.isDirectory && it.mimeType?.startsWith("video/") == true }
          video?.let {
            org.koin.java.KoinJavaComponent.get<app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository>(app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository::class.java)
              .getThumbnailForNetworkSource(connectionId, it.path, 480, 300)
          }
        }
      }
    }
    FolderCard(
      folder = app.gyrolet.mpvrx.domain.media.model.VideoFolder(
        bucketId = "cloud:$connectionId:${file.path}", name = file.name, path = file.path,
        videoCount = if (file.folderScanComplete) file.videoCount ?: 0 else 0,
        totalSize = if (file.folderScanComplete) file.size else 0,
        totalDuration = if (file.folderScanComplete) file.durationMs else 0,
        lastModified = file.lastModified,
      ),
      onClick = onClick, modifier = modifier, onLongClick = onLongClick,
      isSelected = isSelected, onThumbClick = onClick, isGridMode = isGridMode,
      loadLocalThumbnail = false,
      thumbnail = preview?.asImageBitmap(),
    )
    return
  }
  val appearancePreferences = koinInject<AppearancePreferences>()
  val browserPreferences = koinInject<BrowserPreferences>()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
  val interactionSource = remember { MutableInteractionSource() }
  val maxLines = if (unlimitedNameLines) Int.MAX_VALUE else 2

  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(AppShapeScale.large, focusedScale = 1.03f)
        .clip(AppShapeScale.large)
        .tvContextMenu(onLongClick)
        .combinedClickable(
          interactionSource = interactionSource,
          indication = null,
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = AppShapeScale.large,
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      if (isSelected) {
        Box(
          modifier =
            Modifier
              .matchParentSize()
              .padding(2.dp)
              .clip(AppShapeScale.large)
              .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)),
        )
      }

      if (isGridMode) {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 4.dp, vertical = 6.dp),
          horizontalAlignment = if (centerGridTitles) Alignment.CenterHorizontally else Alignment.Start,
        ) {
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .aspectRatio(20f / 17f),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            Icons.RoundedFilled.Folder,
            contentDescription =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_folder),
            modifier = Modifier.matchParentSize(),
            tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
          )
        }
        if (file.videoCount != null && file.folderScanComplete) Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.cloud_video_count, file.videoCount ?: 0), style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          file.name,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = maxLines,
          overflow = TextOverflow.Ellipsis,
          textAlign = if (centerGridTitles) TextAlign.Center else TextAlign.Start,
          modifier = Modifier.fillMaxWidth(),
        )
        }
      } else {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 8.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
        Box(
          modifier =
            Modifier
              .size(72.dp),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            Icons.RoundedFilled.Folder,
            contentDescription =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_folder),
            modifier = Modifier.matchParentSize(),
            tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
          modifier = Modifier.weight(1f),
        ) {
          if (file.videoCount != null && file.folderScanComplete) Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.cloud_video_count, file.videoCount ?: 0), style = MaterialTheme.typography.labelSmall)
          Text(
            file.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
          )
          }
        }
      }
    }
  }
}
