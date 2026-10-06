/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.profile

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.framecapture.FrameCapture
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.ui.browser.cards.PlaylistCard
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.rememberSwipePlaybackInfo
import app.gyrolet.mpvrx.ui.browser.networkstreaming.NetworkStreamingScreen
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistDetailScreen
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistScreen
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistViewModel
import app.gyrolet.mpvrx.ui.browser.playlist.playlistGridColumnLimit
import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedItem
import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedScreen
import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedViewModel
import app.gyrolet.mpvrx.ui.framecapture.SnapshotDetailScreen
import app.gyrolet.mpvrx.ui.framecapture.SnapshotLibraryViewModel
import app.gyrolet.mpvrx.ui.framecapture.SnapshotScreen
import app.gyrolet.mpvrx.ui.framecapture.toDetailItem
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.preferences.PreferencesScreen
import app.gyrolet.mpvrx.ui.preferences.ProfileWatchStatistics
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.io.File

private const val SHELF_LIMIT = 15
private const val AVATAR_FILE_PREFIX = "profile_avatar_"
private const val AVATAR_SIZE_PX = 512
private val SHELF_CARD_WIDTH = 168.dp

/** Personal hub that folds Recents, Playlists and Snapshots into one bottom-navigation tab. */
@Serializable
object ProfileScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val appearancePreferences = koinInject<AppearancePreferences>()
    val advancedPreferences = koinInject<AdvancedPreferences>()
    val browserPreferences = koinInject<BrowserPreferences>()
    val enableRecentlyPlayed by advancedPreferences.enableRecentlyPlayed.collectAsState()
    val showNetworkTab by appearancePreferences.showNetworkTab.collectAsState()
    val profileName by appearancePreferences.profileName.collectAsState()
    val profileImagePath by appearancePreferences.profileImagePath.collectAsState()
    val showRecentThumbnails by browserPreferences.recentView.showThumbnails.collectAsState()
    val playlistManualGrid by browserPreferences.playlistView.manualGridColumnsEnabled.collectAsState()
    val playlistGridColumnsPortrait by browserPreferences.playlistView.gridColumnsPortrait.collectAsState()
    val playlistGridColumnsLandscape by browserPreferences.playlistView.gridColumnsLandscape.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val maxPlaylistColumns = playlistGridColumnLimit(configuration.screenWidthDp, true)
    val requestedPlaylistColumns =
      if (isLandscape) playlistGridColumnsLandscape else playlistGridColumnsPortrait
    val playlistColumns =
      if (playlistManualGrid && requestedPlaylistColumns > 0) {
        requestedPlaylistColumns.coerceIn(1, maxPlaylistColumns)
      } else {
        maxPlaylistColumns
      }
    val profilePlaylistCardWidth =
      remember(configuration.screenWidthDp, playlistColumns) {
        val totalSpacing = 12 * (playlistColumns - 1)
        ((configuration.screenWidthDp - 24 - totalSpacing).toFloat() / playlistColumns)
          .coerceAtLeast(120f)
          .dp
      }
    val navigationBarHeight = LocalNavigationBarHeight.current

    val recentsViewModel: RecentlyPlayedViewModel = viewModel(factory = RecentlyPlayedViewModel.factory(application))
    val playlistViewModel: PlaylistViewModel = viewModel(factory = PlaylistViewModel.factory(application))
    val snapshotViewModel: SnapshotLibraryViewModel = viewModel(factory = SnapshotLibraryViewModel.factory(application))

    LifecycleResumeEffect(playlistViewModel) {
      playlistViewModel.refresh()
      onPauseOrDispose { }
    }

    val recentItems by recentsViewModel.recentItems.collectAsState()
    val playlists by playlistViewModel.playlistsWithCount.collectAsState()
    val snapshotLibrary by snapshotViewModel.library.collectAsState()
    val snapshotThumbnails by snapshotViewModel.thumbnailCache.collectAsState()

    val recentShelf = remember(recentItems) { recentItems.take(SHELF_LIMIT) }
    val recentVideos =
      remember(recentShelf) { recentShelf.filterIsInstance<RecentlyPlayedItem.VideoItem>().map { it.video } }
    val playbackInfo = rememberSwipePlaybackInfo(recentVideos)
    val snapshots = remember(snapshotLibrary.allCaptures) { snapshotLibrary.allCaptures.sortedByDescending { it.capturedAt } }

    var activeVideoItem by remember { mutableStateOf<RecentlyPlayedItem.VideoItem?>(null) }
    var showProfileDialog by rememberSaveable { mutableStateOf(false) }
    val isRefreshing = remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val missingFileMessage = stringResource(R.string.ui_recent_file_no_longer_exists)

    fun playRecent(video: Video) {
      scope.launch {
        val playable = recentsViewModel.resolvePlayableRecentVideo(video)
        if (playable != null) {
          MediaUtils.playFile(playable, context, "recently_played")
        } else {
          Toast.makeText(context, missingFileMessage, Toast.LENGTH_SHORT).show()
        }
      }
    }

    Scaffold(
      containerColor = wallpaperAwareBackgroundColor(),
      topBar = {
        BrowserTopBar(
          title = stringResource(R.string.ui_profile),
          isInSelectionMode = false,
          selectedCount = 0,
          totalCount = 0,
          onCancelSelection = { },
          onSettingsClick = { backStack.navigateTo(PreferencesScreen) },
        )
      },
    ) { paddingValues ->
      PullRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
          recentsViewModel.refresh()
          playlistViewModel.refresh().join()
        },
        listState = listState,
        modifier = Modifier.fillMaxSize().padding(paddingValues),
      ) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = navigationBarHeight + 16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item(key = "profile") {
            ProfileHeader(
              name = profileName,
              onEditProfile = {
                haptics.tick()
                showProfileDialog = true
              },
            )
          }

          item(key = "shortcuts") {
            ShortcutTiles(
              recentCount = recentItems.size,
              playlistCount = playlists.size,
              snapshotCount = snapshots.size,
              showNetwork = !showNetworkTab,
              onRecents = { backStack.navigateTo(RecentlyPlayedScreen) },
              onPlaylists = { backStack.navigateTo(PlaylistScreen) },
              onSnapshots = { backStack.navigateTo(SnapshotScreen) },
              onNetwork = { backStack.navigateTo(NetworkStreamingScreen) },
            )
          }

          item(key = "recents_header") {
            SectionHeader(
              title = stringResource(R.string.pref_advanced_enable_recently_played_title),
              onViewAll = { backStack.navigateTo(RecentlyPlayedScreen) },
            )
          }
          item(key = "recents_shelf") {
            when {
              !enableRecentlyPlayed ->
                ShelfEmptyCard(
                  icon = Icons.RoundedFilled.History,
                  title = stringResource(R.string.ui_recently_played_disabled),
                  message = stringResource(R.string.ui_recently_played_disabled_message),
                )

              recentShelf.isEmpty() ->
                ShelfEmptyCard(
                  icon = Icons.RoundedFilled.History,
                  title = stringResource(R.string.ui_no_recently_played_videos),
                  message = stringResource(R.string.profile_recents_empty_message),
                )

              else ->
                LazyRow(
                  contentPadding = PaddingValues(horizontal = 16.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  items(
                    recentShelf,
                    key = { item ->
                      when (item) {
                        is RecentlyPlayedItem.VideoItem -> "video_${item.video.path}"
                        is RecentlyPlayedItem.PlaylistItem -> "playlist_${item.playlist.id}"
                      }
                    },
                  ) { item ->
                    when (item) {
                      is RecentlyPlayedItem.VideoItem -> {
                        val info = playbackInfo[item.video.path]
                        RecentVideoCard(
                          video = item.video,
                          progress = info?.progressPercentage?.takeUnless { info.isWatched },
                          showThumbnail = showRecentThumbnails,
                          onClick = { playRecent(item.video) },
                          onLongClick = {
                            haptics.pickup()
                            activeVideoItem = item
                          },
                        )
                      }

                      is RecentlyPlayedItem.PlaylistItem ->
                        PlaylistCard(
                          playlist = item.playlist,
                          itemCount = item.videoCount,
                          onClick = { backStack.navigateTo(PlaylistDetailScreen(item.playlist.id)) },
                          onLongClick = { },
                          onThumbClick = { backStack.navigateTo(PlaylistDetailScreen(item.playlist.id)) },
                          modifier = Modifier.width(profilePlaylistCardWidth),
                          isGridMode = true,
                        )
                    }
                  }
                }
            }
          }

          item(key = "playlists_header") {
            SectionHeader(
              title = stringResource(R.string.ui_playlists),
              onViewAll = { backStack.navigateTo(PlaylistScreen) },
            )
          }
          item(key = "playlists_shelf") {
            if (playlists.isEmpty()) {
              ShelfEmptyCard(
                icon = Icons.RoundedFilled.Subscriptions,
                title = stringResource(R.string.ui_no_playlists_yet),
                message = stringResource(R.string.profile_playlists_empty_message),
              )
            } else {
              LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                items(playlists.take(SHELF_LIMIT), key = { it.playlist.id }) { entry ->
                  PlaylistCard(
                    playlist = entry.playlist,
                    itemCount = entry.itemCount,
                    onClick = { backStack.navigateTo(PlaylistDetailScreen(entry.playlist.id)) },
                    onLongClick = { },
                    onThumbClick = { backStack.navigateTo(PlaylistDetailScreen(entry.playlist.id)) },
                    modifier = Modifier.width(profilePlaylistCardWidth),
                    isGridMode = true,
                  )
                }
              }
            }
          }

          item(key = "snapshots_header") {
            SectionHeader(
              title = stringResource(R.string.ui_snapshots),
              onViewAll = { backStack.navigateTo(SnapshotScreen) },
            )
          }
          item(key = "snapshots_shelf") {
            if (snapshots.isEmpty()) {
              ShelfEmptyCard(
                icon = Icons.RoundedFilled.Image,
                title = stringResource(R.string.snapshot_empty_title),
                message = stringResource(R.string.snapshot_empty_message),
              )
            } else {
              LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                itemsIndexed(snapshots.take(SHELF_LIMIT), key = { _, capture -> capture.id }) { index, capture ->
                  LaunchedEffect(capture.id) { snapshotViewModel.loadThumbnail(capture) }
                  SnapshotShelfCard(
                    capture = capture,
                    thumbnail = snapshotThumbnails[capture.id],
                    onClick = {
                      backStack.navigateTo(
                        SnapshotDetailScreen(
                          captures = snapshots.map { it.toDetailItem() },
                          initialIndex = index,
                        ),
                      )
                    },
                  )
                }
              }
            }
          }
          item(key = "watch_statistics") {
            ProfileWatchStatistics()
          }
        }
      }
    }

    activeVideoItem?.let { item ->
      RecentVideoActionsSheet(
        video = item.video,
        onDismiss = { activeVideoItem = null },
        onPlay = {
          activeVideoItem = null
          playRecent(item.video)
        },
        onShare = {
          activeVideoItem = null
          MediaUtils.shareVideos(context, listOf(item.video))
        },
        onRemove = {
          activeVideoItem = null
          scope.launch { recentsViewModel.deleteVideosFromHistory(listOf(item.video)) }
        },
      )
    }

    if (showProfileDialog) {
      ProfileEditDialog(
        initialName = profileName,
        hasPhoto = profileImagePath.isNotBlank(),
        onDismiss = { showProfileDialog = false },
        onSave = { name, newPhoto, removePhoto ->
          showProfileDialog = false
          appearancePreferences.profileName.set(name.trim())
          if (newPhoto != null || removePhoto) {
            scope.launch {
              val path = withContext(Dispatchers.IO) { storeProfileAvatar(context, newPhoto) }
              appearancePreferences.profileImagePath.set(path)
            }
          }
        },
      )
    }
  }
}

/** The user's avatar, or the account glyph when none is set. Shared with the navigation pill. */
@Composable
internal fun ProfileAvatar(
  size: Dp,
  tint: Color,
  contentDescription: String?,
  modifier: Modifier = Modifier,
) {
  val path by koinInject<AppearancePreferences>().profileImagePath.collectAsState()
  val avatar = rememberProfileAvatar(path)
  if (avatar != null) {
    Image(
      bitmap = avatar,
      contentDescription = contentDescription,
      contentScale = ContentScale.Crop,
      modifier = modifier.size(size).clip(CircleShape),
    )
  } else {
    Icon(
      Icons.RoundedFilled.AccountCircle,
      contentDescription = contentDescription,
      tint = tint,
      modifier = modifier.size(size),
    )
  }
}

@Composable
private fun rememberProfileAvatar(path: String): ImageBitmap? {
  val avatar by produceState<ImageBitmap?>(initialValue = null, path) {
    value =
      if (path.isBlank()) {
        null
      } else {
        withContext(Dispatchers.IO) {
          runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
      }
  }
  return avatar
}

@Composable
private fun ProfileHeader(
  name: String,
  onEditProfile: () -> Unit,
) {
  val path by koinInject<AppearancePreferences>().profileImagePath.collectAsState()
  val avatar = rememberProfileAvatar(path)
  val editLabel = stringResource(R.string.profile_edit)
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Box(contentAlignment = Alignment.BottomEnd) {
      Surface(
        onClick = onEditProfile,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(72.dp).tvFocusHighlight(CircleShape),
      ) {
        Box(contentAlignment = Alignment.Center) {
          if (avatar != null) {
            Image(
              bitmap = avatar,
              contentDescription = editLabel,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          } else {
            Icon(
              Icons.RoundedFilled.Person,
              contentDescription = editLabel,
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(40.dp),
            )
          }
        }
      }
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(24.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(Icons.RoundedFilled.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
        }
      }
    }

    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = name.ifBlank { stringResource(R.string.ui_profile) },
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = editLabel,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clip(AppShapeScale.small).clickable(onClick = onEditProfile).padding(vertical = 2.dp),
      )
    }
  }
}

@Composable
private fun ShortcutTiles(
  recentCount: Int,
  playlistCount: Int,
  snapshotCount: Int,
  showNetwork: Boolean,
  onRecents: () -> Unit,
  onPlaylists: () -> Unit,
  onSnapshots: () -> Unit,
  onNetwork: () -> Unit,
) {
  Surface(
    shape = AppShapeScale.extraLarge,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
  ) {
    Row(modifier = Modifier.padding(8.dp)) {
      ShortcutTile(Icons.RoundedFilled.History, stringResource(R.string.ui_recents), recentCount, onRecents)
      ShortcutTile(Icons.RoundedFilled.Subscriptions, stringResource(R.string.ui_playlists), playlistCount, onPlaylists)
      ShortcutTile(Icons.RoundedFilled.Image, stringResource(R.string.ui_snapshots), snapshotCount, onSnapshots)
      if (showNetwork) {
        ShortcutTile(Icons.RoundedFilled.BringYourOwnIp, stringResource(R.string.ui_network), null, onNetwork)
      }
    }
  }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ShortcutTile(
  icon: AppIcon,
  label: String,
  count: Int?,
  onClick: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .weight(1f)
        .tvFocusHighlight(AppShapeScale.large)
        .clip(AppShapeScale.large)
        .clickable(onClick = onClick)
        .padding(vertical = 10.dp, horizontal = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
      Box(contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
      }
    }
    Text(
      text = count?.toString() ?: " ",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun SectionHeader(
  title: String,
  onViewAll: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f),
    )
    TextButton(onClick = onViewAll, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
      Text(stringResource(R.string.profile_view_all), style = MaterialTheme.typography.labelLarge)
      Spacer(Modifier.width(2.dp))
      Icon(Icons.RoundedFilled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
    }
  }
}

@Composable
private fun ShelfEmptyCard(
  icon: AppIcon,
  title: String,
  message: String,
) {
  Surface(
    shape = AppShapeScale.large,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(42.dp)) {
        Box(contentAlignment = Alignment.Center) {
          Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp))
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
      }
    }
  }
}

/** 16:9 frame plus a fixed two-line caption so the shelf never jumps while thumbnails load. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCard(
  title: String,
  subtitle: String?,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
  frame: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
  Column(
    modifier =
      Modifier
        .width(SHELF_CARD_WIDTH)
        .tvFocusHighlight(AppShapeScale.medium)
        .clip(AppShapeScale.medium)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .aspectRatio(16f / 9f)
          .clip(AppShapeScale.medium)
          .background(MaterialTheme.colorScheme.surfaceContainerHighest),
      contentAlignment = Alignment.Center,
      content = frame,
    )
    Column(modifier = Modifier.height(48.dp).padding(horizontal = 2.dp, vertical = 4.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = if (subtitle == null) 2 else 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (subtitle != null) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.outline,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun RecentVideoCard(
  video: Video,
  progress: Float?,
  showThumbnail: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  val thumbnailRepository = koinInject<ThumbnailRepository>()
  val density = LocalDensity.current
  val widthPx = with(density) { SHELF_CARD_WIDTH.roundToPx() }
  val heightPx = widthPx * 9 / 16
  var thumbnail by remember(video.path, showThumbnail) {
    mutableStateOf(if (showThumbnail) thumbnailRepository.peekThumbnailFromMemory(video, widthPx, heightPx) else null)
  }
  LaunchedEffect(video.path, showThumbnail) {
    if (showThumbnail && thumbnail == null) {
      thumbnail = thumbnailRepository.getThumbnail(video, widthPx, heightPx)
    }
  }

  ShelfCard(title = video.displayName, subtitle = null, onClick = onClick, onLongClick = onLongClick) {
    val bitmap = thumbnail
    if (bitmap != null) {
      Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
      Icon(
        if (video.isAudio) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.PlayArrow,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.size(36.dp),
      )
    }
    if (video.durationFormatted.isNotBlank() && video.durationFormatted != "--:--") {
      FrameBadge(video.durationFormatted, Modifier.align(Alignment.BottomEnd))
    }
    if (progress != null && progress > 0f) {
      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.Black.copy(alpha = 0.35f),
      )
    }
  }
}

@Composable
private fun SnapshotShelfCard(
  capture: FrameCapture,
  thumbnail: Bitmap?,
  onClick: () -> Unit,
) {
  ShelfCard(title = capture.videoTitle, subtitle = null, onClick = onClick) {
    if (thumbnail != null) {
      Image(thumbnail.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
      Icon(Icons.RoundedFilled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(36.dp))
    }
    FrameBadge(capture.formattedPosition, Modifier.align(Alignment.BottomEnd))
  }
}

@Composable
private fun FrameBadge(
  text: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = AppShapeScale.extraSmall,
    color = Color.Black.copy(alpha = 0.72f),
    contentColor = Color.White,
    modifier = modifier.padding(6.dp),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentVideoActionsSheet(
  video: Video,
  onDismiss: () -> Unit,
  onPlay: () -> Unit,
  onShare: () -> Unit,
  onRemove: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
      Text(
        text = video.displayName,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
      )
      HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
      SheetAction(Icons.RoundedFilled.PlayArrow, stringResource(R.string.ui_play), MaterialTheme.colorScheme.onSurface, onPlay)
      if (!video.path.contains("://")) {
        SheetAction(Icons.RoundedFilled.Share, stringResource(R.string.generic_share), MaterialTheme.colorScheme.onSurface, onShare)
      }
      SheetAction(Icons.RoundedFilled.Delete, stringResource(R.string.profile_remove_from_history), MaterialTheme.colorScheme.error, onRemove)
    }
  }
}

@Composable
private fun SheetAction(
  icon: AppIcon,
  label: String,
  tint: Color,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
        .clip(AppShapeScale.medium)
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
  }
}

@Composable
private fun ProfileEditDialog(
  initialName: String,
  hasPhoto: Boolean,
  onDismiss: () -> Unit,
  onSave: (name: String, newPhoto: Bitmap?, removePhoto: Boolean) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val currentPath by koinInject<AppearancePreferences>().profileImagePath.collectAsState()
  val currentAvatar = rememberProfileAvatar(currentPath)
  var name by rememberSaveable { mutableStateOf(initialName) }
  var pendingPhoto by remember { mutableStateOf<Bitmap?>(null) }
  var removePhoto by remember { mutableStateOf(false) }
  val pendingImage = remember(pendingPhoto) { pendingPhoto?.asImageBitmap() }
  val preview = pendingImage ?: currentAvatar.takeUnless { removePhoto }
  val showsPhoto = pendingPhoto != null || (hasPhoto && !removePhoto)

  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
      if (uri != null) {
        scope.launch {
          val decoded = withContext(Dispatchers.IO) { decodeProfileAvatar(context, uri) }
          if (decoded != null) {
            pendingPhoto = decoded
            removePhoto = false
          }
        }
      }
    }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.profile_edit)) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Surface(
          onClick = { picker.launch("image/*") },
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.size(96.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            if (preview != null) {
              Image(preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
              Icon(
                Icons.RoundedFilled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(52.dp),
              )
            }
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { picker.launch("image/*") }) {
            Text(stringResource(R.string.profile_choose_photo))
          }
          if (showsPhoto) {
            TextButton(
              onClick = {
                pendingPhoto = null
                removePhoto = true
              },
            ) {
              Text(stringResource(R.string.profile_remove_photo), color = MaterialTheme.colorScheme.error)
            }
          }
        }
        OutlinedTextField(
          value = name,
          onValueChange = { name = it.take(40) },
          label = { Text(stringResource(R.string.profile_display_name)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(name, pendingPhoto, removePhoto) }) {
        Text(stringResource(R.string.ui_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
    },
  )
}

/** Decodes [uri] into an upright, center-cropped square no larger than [AVATAR_SIZE_PX]. */
private fun decodeProfileAvatar(
  context: Context,
  uri: Uri,
): Bitmap? =
  runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= AVATAR_SIZE_PX) sampleSize *= 2
    val decoded =
      resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
      } ?: return null

    val rotation =
      resolver.openInputStream(uri)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
          ExifInterface.ORIENTATION_ROTATE_90 -> 90f
          ExifInterface.ORIENTATION_ROTATE_180 -> 180f
          ExifInterface.ORIENTATION_ROTATE_270 -> 270f
          else -> 0f
        }
      } ?: 0f

    val side = minOf(decoded.width, decoded.height)
    val target = minOf(side, AVATAR_SIZE_PX)
    val matrix =
      Matrix().apply {
        postScale(target.toFloat() / side, target.toFloat() / side)
        postRotate(rotation)
      }
    Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side, matrix, true)
  }.getOrNull()

/** Replaces the stored avatar with [bitmap] (or clears it when null) and returns the new path. */
private fun storeProfileAvatar(
  context: Context,
  bitmap: Bitmap?,
): String {
  context.filesDir.listFiles { file -> file.name.startsWith(AVATAR_FILE_PREFIX) }?.forEach(File::delete)
  if (bitmap == null) return ""
  // A fresh name per save changes the preference value, so every avatar observer reloads.
  val file = File(context.filesDir, "$AVATAR_FILE_PREFIX${System.currentTimeMillis()}.png")
  file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  return file.absolutePath
}
