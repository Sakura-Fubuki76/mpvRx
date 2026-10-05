/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.cards

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.browser.components.MediaTypeBadge
import app.gyrolet.mpvrx.ui.browser.components.NetworkMediaType
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvContextMenu
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.text.style.TextAlign

@Composable
fun NetworkVideoCard(
  file: NetworkFile,
  connection: NetworkConnection,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  isSelected: Boolean = false,
  isGridMode: Boolean = false,
  /** Corner label naming the media type; null hides it (shown only when images are mixed in). */
  mediaType: NetworkMediaType? = null,
  titleOverride: String? = null,
  episodeLabel: String? = null,
) {
  val appearancePreferences = koinInject<AppearancePreferences>()
  val browserPreferences = koinInject<BrowserPreferences>()
  val fields = browserPreferences.animeFields.takeIf { connection.isAnime }
  val thumbnailRepository = koinInject<ThumbnailRepository>()

  val unlimitedNameLines by (fields?.unlimitedNameLines ?: appearancePreferences.unlimitedNameLines).collectAsState()
  val newLabelDays by appearancePreferences.unplayedOldVideoDays.collectAsState()
  val watchedThreshold by browserPreferences.watchedThreshold.collectAsState()
  val showSizeChip by (fields?.showSizeChip ?: browserPreferences.showSizeChip).collectAsState()
  val showDateChip by (fields?.showDateChip ?: browserPreferences.showDateChip).collectAsState()
  val showDuration by (fields?.showDurationField ?: browserPreferences.showDurationField).collectAsState()
  val showExtensionField by (fields?.showExtensionField ?: browserPreferences.showExtensionField).collectAsState()
  val showSubtitleIndicator by (fields?.showSubtitleIndicator ?: browserPreferences.showSubtitleIndicator).collectAsState()
  val showVideoThumbnails by (fields?.showVideoThumbnails ?: browserPreferences.showVideoThumbnails).collectAsState()
  val showNetworkThumbs by appearancePreferences.showNetworkThumbnails.collectAsState()
  val showPath by (fields?.showFolderPath ?: browserPreferences.showFolderPath).collectAsState()
  val centerGridTitles by (fields?.centerGridTitles ?: browserPreferences.centerGridTitles).collectAsState()

  val gestures = koinInject<app.gyrolet.mpvrx.preferences.GesturePreferences>()
  val tapThumbnailToSelect by gestures.tapThumbnailToSelect.collectAsState()
  val onThumbnailClick = if (tapThumbnailToSelect && onLongClick != null) onLongClick else onClick

  val displayThumb = showVideoThumbnails && (connection.isAnime || showNetworkThumbs)
  val maxLines = if (unlimitedNameLines) Int.MAX_VALUE else 2

  val thumbSizeDp = 160.dp
  val density = LocalDensity.current
  val thumbSizePx = with(density) { thumbSizeDp.roundToPx() }

  val thumbnailKey =
    remember(file.path, file.size, file.lastModified, connection, thumbSizePx, displayThumb) {
      if (displayThumb) {
        thumbnailRepository.thumbnailKeyForNetworkPath(
          path = file.path,
          widthPx = thumbSizePx,
          heightPx = thumbSizePx,
          connection = connection,
          fileSize = file.size,
          lastModified = file.lastModified,
        )
      } else {
        null
      }
    }
  // Seeded during composition so returning to a card does not flash the placeholder first.
  var thumbnail by remember(thumbnailKey) {
    mutableStateOf<Bitmap?>(
      if (displayThumb) {
        thumbnailRepository.peekNetworkThumbnail(
          path = file.path,
          widthPx = thumbSizePx,
          heightPx = thumbSizePx,
          connection = connection,
          fileSize = file.size,
          lastModified = file.lastModified,
        )
      } else {
        null
      },
    )
  }

  // Subscribe to ready-keys so folder-level prefetch also updates this card
  LaunchedEffect(thumbnailKey) {
    if (thumbnailKey == null) return@LaunchedEffect
    thumbnailRepository.thumbnailReadyKeys
      .collect { key ->
        if (key == thumbnailKey) {
          thumbnail =
            withContext(Dispatchers.IO) {
              thumbnailRepository.getThumbnailForNetworkPath(
                path = file.path,
                widthPx = thumbSizePx,
                heightPx = thumbSizePx,
                connection = connection,
                fileSize = file.size,
                mimeType = file.mimeType,
                lastModified = file.lastModified,
              )
            }
        }
      }
  }

  // On-demand generation
  LaunchedEffect(thumbnailKey, displayThumb) {
    if (thumbnailKey == null || !displayThumb) return@LaunchedEffect
    if (thumbnail != null) return@LaunchedEffect
    repeat(2) { attempt ->
      thumbnail =
        withContext(Dispatchers.IO) {
          thumbnailRepository.getThumbnailForNetworkPath(
            path = file.path,
            widthPx = thumbSizePx,
            heightPx = thumbSizePx,
            connection = connection,
            fileSize = file.size,
            mimeType = file.mimeType,
                lastModified = file.lastModified,
          )
        }
      if (thumbnail != null) return@LaunchedEffect
      if (attempt == 0) delay(30_000L)
    }
  }

  val displayName = titleOverride ?: if (showExtensionField) file.name else file.name.substringBeforeLast('.', file.name)

  if (connection.protocol in setOf(app.gyrolet.mpvrx.domain.network.NetworkProtocol.WEBDAV, app.gyrolet.mpvrx.domain.network.NetworkProtocol.OPENLIST)) {
    val playbackDao = koinInject<app.gyrolet.mpvrx.database.MpvRxDatabase>().videoDataDao()
    val playbackKey = remember(connection.id, file.path) { app.gyrolet.mpvrx.ui.player.PlaybackIdentity.forNetwork(connection.id, file.path) }
    val playback by remember(playbackKey) { playbackDao.observeVideoData(playbackKey) }
      .collectAsState(initial = null)
    val positionMs = (playback?.lastPosition ?: 0).coerceAtLeast(0).toLong() * 1000
    val durationMs = file.durationMs.takeIf { it > 0 } ?: playback?.let { (it.lastPosition.toLong() + it.timeRemaining).coerceAtLeast(0) * 1000 } ?: 0
    val progress = if (durationMs > 0 && positionMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
    val codec = remember(file.videoCodec) {
      app.gyrolet.mpvrx.utils.media.VideoCodecSupportInspector.descriptor(file.videoCodec, file.videoCodec)
    }
    val video = app.gyrolet.mpvrx.domain.media.model.Video(
      id = ("${connection.id}:${file.path}").hashCode().toLong(), title = displayName, displayName = file.name,
      path = file.path, uri = android.net.Uri.parse(file.path), duration = durationMs,
      durationFormatted = if (durationMs > 0) {
        if (positionMs > 0) "${formatCloudDuration(positionMs.coerceAtMost(durationMs))} / ${formatCloudDuration(durationMs)}"
        else formatCloudDuration(durationMs)
      } else "--",
      size = file.size, sizeFormatted = formatCardFileSize(file.size), dateModified = file.lastModified / 1000,
      dateAdded = 0, mimeType = file.mimeType ?: "video/*", bucketId = "cloud:${connection.id}",
      bucketDisplayName = connection.name, width = file.width, height = file.height, fps = file.fps,
      resolution = app.gyrolet.mpvrx.utils.storage.VideoScanUtils.formatResolutionWithFps(file.width, file.height, file.fps),
      videoCodec = file.videoCodec.takeIf { it.isNotBlank() }?.let { codec.label }.orEmpty(), videoCodecMimeType = codec.mimeType,
      hasEmbeddedSubtitles = file.hasEmbeddedSubtitles, subtitleCodec = file.subtitleCodec,
    )
    val playbackInfo = app.gyrolet.mpvrx.ui.browser.videolist.buildVideoWithPlaybackInfo(
      video, playback, System.currentTimeMillis(), newLabelDays, watchedThreshold)
    VideoCard(video = video, onClick = onClick, modifier = modifier, onLongClick = onLongClick,
      isSelected = isSelected, onThumbClick = onThumbnailClick, isGridMode = isGridMode,
      progressPercentage = progress, isWatched = playbackInfo.isWatched, isOldAndUnplayed = playbackInfo.isOldAndUnplayed, playbackIdentity = playbackKey,
      allowThumbnailGeneration = false, allowThumbnailLoading = false,
      externalThumbnail = thumbnail, uiConfig = rememberVideoCardUiConfig(fields).copy(showThumbnails = displayThumb),
      showSubtitleIndicator = showSubtitleIndicator, titleOverride = if (showExtensionField && titleOverride != null) "$titleOverride.${file.name.substringAfterLast('.', "")}" else titleOverride,
      sourceSubtitle = file.path.takeIf { fields != null && showPath },
      titleLeading = episodeLabel?.let { label -> {
        androidx.compose.material3.Surface(
          modifier = Modifier.padding(end = 8.dp),
          shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
          Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
      } })
    return
  }

  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(AppShapeScale.large, focusedScale = 1.03f)
        .clip(AppShapeScale.large)
        .tvContextMenu(onLongClick)
        .combinedClickable(
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
              .aspectRatio(16f / 10f)
              .clip(AppShapeScale.medium)
              .background(MaterialTheme.colorScheme.surfaceContainerHigh)
              .combinedClickable(onClick = onThumbnailClick, onLongClick = onLongClick),
          contentAlignment = Alignment.Center,
        ) {
          val thumbnailBitmap = remember(thumbnail) { thumbnail?.asImageBitmap() }
          if (thumbnailBitmap != null) {
            Image(
              bitmap = thumbnailBitmap,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_thumbnail),
              modifier = Modifier.matchParentSize(),
              contentScale = ContentScale.Crop,
            )
          } else {
            Icon(
              Icons.RoundedFilled.PlayArrow,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_play),
              modifier = Modifier.size(48.dp),
              tint = MaterialTheme.colorScheme.secondary,
            )
          }
          if (mediaType != null) {
            MediaTypeBadge(
              type = mediaType,
              modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
          }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          displayName,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = maxLines,
          overflow = TextOverflow.Ellipsis,
          textAlign = if (centerGridTitles) TextAlign.Center else TextAlign.Start,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
          horizontalArrangement =
            if (centerGridTitles) androidx.compose.foundation.layout.Arrangement.Center
            else androidx.compose.foundation.layout.Arrangement.Start,
          verticalArrangement =
            androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        ) {
          if (showDuration && file.durationMs > 0) {
            Text(formatCloudDuration(file.durationMs), style = MaterialTheme.typography.labelSmall)
          }
          if (showSizeChip && file.size > 0) {
            Text(
              formatCardFileSize(file.size),
              style = MaterialTheme.typography.labelSmall,
              modifier =
                Modifier
                  .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    AppShapeScale.small,
                  ).padding(horizontal = 8.dp, vertical = 4.dp),
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
          if (showDateChip && file.lastModified > 0) {
            Text(
              formatCardDate(file.lastModified),
              style = MaterialTheme.typography.labelSmall,
              modifier =
                Modifier
                  .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    AppShapeScale.small,
                  ).padding(horizontal = 8.dp, vertical = 4.dp),
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
        }
      } else {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 8.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
        // Match the normal video list thumbnail footprint.
        Box(
          modifier =
            Modifier
              .width(thumbSizeDp)
              .aspectRatio(16f / 9f)
              .clip(AppShapeScale.medium)
              .background(MaterialTheme.colorScheme.surfaceContainerHigh)
              .combinedClickable(
                onClick = onThumbnailClick,
                onLongClick = onLongClick,
              ),
          contentAlignment = Alignment.Center,
        ) {
          val listThumbnailBitmap = remember(thumbnail) { thumbnail?.asImageBitmap() }
          if (listThumbnailBitmap != null) {
            Image(
              bitmap = listThumbnailBitmap,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_thumbnail),
              modifier = Modifier.matchParentSize(),
              contentScale = ContentScale.Crop,
            )
          } else {
            Icon(
              Icons.RoundedFilled.PlayArrow,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_play),
              modifier = Modifier.size(48.dp),
              tint = MaterialTheme.colorScheme.secondary,
            )
          }
          if (mediaType != null) {
            MediaTypeBadge(
              type = mediaType,
              modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
          }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
          modifier = Modifier.weight(1f),
        ) {
          Text(
            displayName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(modifier = Modifier.height(4.dp))
          FlowRow(
            horizontalArrangement =
              androidx.compose.foundation.layout.Arrangement
                .spacedBy(4.dp),
            verticalArrangement =
              androidx.compose.foundation.layout.Arrangement
                .spacedBy(4.dp),
          ) {
            if (showDuration && file.durationMs > 0) {
              Text(formatCloudDuration(file.durationMs), style = MaterialTheme.typography.labelSmall)
            }
            if (showSizeChip && file.size > 0) {
              Text(
                formatCardFileSize(file.size),
                style = MaterialTheme.typography.labelSmall,
                modifier =
                  Modifier
                    .background(
                      MaterialTheme.colorScheme.surfaceContainerHigh,
                      AppShapeScale.small,
                    ).padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
            if (showDateChip && file.lastModified > 0) {
              Text(
                formatCardDate(file.lastModified),
                style = MaterialTheme.typography.labelSmall,
                modifier =
                  Modifier
                    .background(
                      MaterialTheme.colorScheme.surfaceContainerHigh,
                      AppShapeScale.small,
                    ).padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
          }
        }
      }
    }
  }
}

private fun formatCloudDuration(durationMs: Long): String {
  val seconds = durationMs / 1000
  val hours = seconds / 3600
  val minutes = (seconds / 60) % 60
  return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds % 60)
  else "%d:%02d".format(minutes, seconds % 60)
}
