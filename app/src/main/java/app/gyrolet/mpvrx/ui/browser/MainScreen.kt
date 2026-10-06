/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import app.gyrolet.mpvrx.ui.utils.NavigationPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.MediaServerPreferences
import app.gyrolet.mpvrx.preferences.MusicSourceProvider
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ProvideLiquidGlassBackdrop
import app.gyrolet.mpvrx.presentation.components.LiquidGlassStyle
import app.gyrolet.mpvrx.presentation.components.LiquidGlassSurface
import app.gyrolet.mpvrx.presentation.components.captureLiquidGlassBackdrop
import app.gyrolet.mpvrx.presentation.components.rememberLiquidGlassBackdrop
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.browser.folderlist.FolderListScreen
import app.gyrolet.mpvrx.ui.browser.music.MusicLibraryContent
import app.gyrolet.mpvrx.ui.browser.music.MusicLibraryViewModel
import app.gyrolet.mpvrx.ui.browser.music.MusicTab
import app.gyrolet.mpvrx.ui.browser.networkstreaming.NetworkStreamingScreen
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistScreen
import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedScreen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.player.NavigationAnimStyle
import app.gyrolet.mpvrx.ui.utils.navigationDurationMillis
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object MainScreen : Screen {
  internal enum class MainTab {
    HOME,
    MUSIC,
    RECENTS,
    PLAYLISTS,
    NETWORK,
    JELLYFIN,
    SNAPSHOTS,
    PROFILE,
  }

  /**
   * Update selection state and navigation bar visibility
   * This method should be called whenever selection changes
   */
  fun updateSelectionState(
    isInSelectionMode: Boolean,
    isOnlyVideosSelected: Boolean,
    selectionManager: Any?,
  ) {
    NavigationBarState.updateSelectionState(
      inSelectionMode = isInSelectionMode,
      onlyVideos = isOnlyVideosSelected,
    )
  }

  /**
   * Update permission state to control FAB visibility
   */
  fun updatePermissionState(isDenied: Boolean) {
    NavigationBarState.updatePermissionState(isDenied)
  }

  /**
   * Get current permission denied state
   */
  fun getPermissionDeniedState(): Boolean = NavigationBarState.isPermissionDenied

  /**
   * Update bottom navigation bar visibility based on floating bottom bar state
   */
  fun updateBottomBarVisibility(shouldShow: Boolean) {
    NavigationBarState.updateBottomBarVisibility(shouldShow)
  }

  @SuppressLint("ComposableNaming")
  @Composable
  override fun Content() {
    val backStack = LocalBackStack.current
    val appearancePreferences = koinInject<AppearancePreferences>()
    val playerPreferences = koinInject<PlayerPreferences>()
    val navStyle by playerPreferences.appNavStyle.collectAsState()
    val animSpeed by playerPreferences.animationSpeed.collectAsState()
    val duration = navigationDurationMillis(animSpeed)
    var persistentSelectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    val mediaServerPreferences = koinInject<MediaServerPreferences>()
    val musicSourceProvider by mediaServerPreferences.musicSourceProvider.collectAsState()
    val musicLibraryViewModel: MusicLibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val localMusicTabs by musicLibraryViewModel.visibleTabs.collectAsStateWithLifecycle()
    val showMusicTab by appearancePreferences.showMusicTab.collectAsState()
    val showProfileTab by appearancePreferences.showProfileTab.collectAsState()
    val showRecentsTab by appearancePreferences.showRecentsTab.collectAsState()
    val showPlaylistsTab by appearancePreferences.showPlaylistsTab.collectAsState()
    val showNetworkTab by appearancePreferences.showNetworkTab.collectAsState()
    val showJellyfinTab by appearancePreferences.showJellyfinTab.collectAsState()
    val showSnapshotTab by appearancePreferences.showSnapshotTab.collectAsState()
    val liquidGlassEnabled by appearancePreferences.liquidGlassEnabled.collectAsState()
    val hideNavigationBar = NavigationBarState.shouldHideNavigationBar
    val isPermissionDenied = NavigationBarState.isPermissionDenied
    val isDualPaneFolderSelected = NavigationBarState.isDualPaneFolderSelected
    val isMiniPlayerVisible = NavigationBarState.isMiniPlayerVisible

    val visibleTabs =
      remember(
        showMusicTab,
        showProfileTab,
        showRecentsTab,
        showPlaylistsTab,
        showNetworkTab,
        showJellyfinTab,
        showSnapshotTab,
      ) {
        mainNavigationTabs(
          showMusic = showMusicTab,
          showProfile = showProfileTab,
          showRecents = showRecentsTab,
          showPlaylists = showPlaylistsTab,
          showNetwork = showNetworkTab,
          showJellyfin = showJellyfinTab,
          showSnapshots = showSnapshotTab,
        )
      }
    val navigationTabs = visibleTabs.takeIf { it.size > 1 }.orEmpty()

    // Track whether the floating pill nav bar is on screen so the mini player can
    // sit at the very bottom when navigating to screens without it.
    DisposableEffect(Unit) {
      onDispose {
        NavigationBarState.isNavBarVisible = false
      }
    }
    SideEffect {
      NavigationBarState.isNavBarVisible =
        backStack.lastOrNull() == MainScreen && !hideNavigationBar && navigationTabs.isNotEmpty() && !isPermissionDenied
    }

    val coroutineScope = rememberCoroutineScope()

    val initialPageIndex =
      remember(visibleTabs) {
        visibleTabs.indexOf(persistentSelectedTab).coerceAtLeast(0)
      }

    val pagerState =
      rememberPagerState(
        initialPage = initialPageIndex,
        pageCount = { visibleTabs.size },
      )
    var tabNavigationJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(pagerState, visibleTabs) {
      tabNavigationJob?.cancelAndJoin()
      tabNavigationJob = null
      if (visibleTabs.isEmpty()) {
        persistentSelectedTab = MainTab.HOME
        return@LaunchedEffect
      }

      val restorePage = visibleTabs.indexOf(persistentSelectedTab).takeIf { it >= 0 } ?: 0
      val isRestorePageSettled =
        pagerState.settledPage == restorePage &&
          pagerState.currentPage == restorePage &&
          pagerState.currentPageOffsetFraction == 0f
      if (!isRestorePageSettled) {
        pagerState.scrollToPage(restorePage)
      }

      snapshotFlow { pagerState.settledPage }
        .collect { page ->
          visibleTabs.getOrNull(page)?.let { settledTab ->
            persistentSelectedTab = settledTab
            if (settledTab != MainTab.HOME) {
              NavigationBarState.isDualPaneFolderSelected = false
            }
          }
        }
    }

    val targetPage = pagerState.targetPage.coerceIn(0, (visibleTabs.size - 1).coerceAtLeast(0))
    val selectedTab = visibleTabs.getOrNull(targetPage) ?: visibleTabs.firstOrNull() ?: MainTab.HOME

    LaunchedEffect(persistentSelectedTab) {
      if (persistentSelectedTab == MainTab.MUSIC) {
        musicLibraryViewModel.setTab(localMusicTabs.firstOrNull() ?: MusicTab.SONGS)
      }
    }

    val onTabSelected: (MainScreen.MainTab) -> Unit = { tab ->
      val targetIndex = visibleTabs.indexOf(tab)
      val isAlreadySettled =
        targetIndex >= 0 &&
          pagerState.settledPage == targetIndex &&
          !pagerState.isScrollInProgress &&
          pagerState.currentPageOffsetFraction == 0f
      if (targetIndex >= 0) {
        tabNavigationJob?.cancel()
        if (!isAlreadySettled) {
          tabNavigationJob =
            coroutineScope.launch {
              if (navStyle == NavigationAnimStyle.None) {
                pagerState.scrollToPage(targetIndex)
              } else {
                pagerState.animateScrollToPage(
                  page = targetIndex,
                  animationSpec = tween(duration, easing = FastOutSlowInEasing),
                )
              }
            }
        }
      }
    }

    val shouldReturnHome by remember(pagerState) {
      derivedStateOf {
        pagerState.settledPage != 0 || pagerState.currentPage != 0 || pagerState.isScrollInProgress
      }
    }
    // Register before page content: selection, search and nested navigation handle Back first.
    BackHandler(enabled = backStack.lastOrNull() == MainScreen && shouldReturnHome) {
      onTabSelected(MainTab.HOME)
    }

    // One captured backdrop drives both modes: plain Haze when Liquid Glass is off and
    // Haze Glass when it is on. This keeps the effect local to the floating capsule.
    val navigationBackdrop = rememberLiquidGlassBackdrop()

    val mainNavBar = @Composable { modifier: Modifier ->
      ExpressivePillNavigationBar(
        visibleTabs = navigationTabs,
        selectedTab = selectedTab,
        onTabSelected = onTabSelected,
        pagerState = pagerState,
        hazeBackdrop = navigationBackdrop,
        liquidGlassEnabled = liquidGlassEnabled,
        modifier = modifier,
      )
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current

    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // On portrait phones the edge-to-edge mini player sits above the pill nav bar,
    // so screens/FABs must clear it.
    val miniPlayerNavClearance = if (isMiniPlayerVisible && isPortrait && !isTablet) 96.dp else 0.dp
    val contentBottomPadding = (if (navigationTabs.isEmpty()) 0.dp else 88.dp) + miniPlayerNavClearance
    val context = androidx.compose.ui.platform.LocalContext.current
    val jellyfinViewModel: app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinViewModel =
      androidx.lifecycle.viewmodel.compose.viewModel(
        factory =
          app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinViewModel.factory(
            context.applicationContext as android.app.Application,
          ),
      )
    val navidromeViewModel: app.gyrolet.mpvrx.ui.browser.navidrome.NavidromeViewModel =
      androidx.lifecycle.viewmodel.compose.viewModel(
        factory =
          app.gyrolet.mpvrx.ui.browser.navidrome.NavidromeViewModel.factory(
            context.applicationContext as android.app.Application,
          ),
      )
    // Scaffold with bottom navigation bar
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      containerColor = wallpaperAwareBackgroundColor(),
    ) { paddingValues ->
      Box(modifier = Modifier.fillMaxSize()) {
        if (visibleTabs.isEmpty()) {
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .captureLiquidGlassBackdrop(navigationBackdrop, navigationTabs.isNotEmpty()),
          ) {
            CompositionLocalProvider(
              LocalNavigationBarHeight provides contentBottomPadding,
              LocalMainNavigationBar provides mainNavBar,
              LocalIsMainTabPage provides true,
            ) {
              FolderListScreen.Content()
            }
          }
        } else {
          CompositionLocalProvider(
            LocalNavigationBarHeight provides contentBottomPadding,
            LocalMainNavigationBar provides mainNavBar,
            LocalIsMainTabPage provides true,
          ) {
            NavigationPager(
              state = pagerState,
              modifier =
                Modifier
                  .fillMaxSize()
                  .clipToBounds()
                  .captureLiquidGlassBackdrop(navigationBackdrop, navigationTabs.isNotEmpty()),
              key = { page -> visibleTabs[page].name },
              beyondViewportPageCount = 1,
              userScrollEnabled = !isPermissionDenied,
            ) { page ->
              val tab = visibleTabs.getOrNull(page) ?: return@NavigationPager
              when (tab) {
                MainTab.HOME -> FolderListScreen.Content()
                MainTab.MUSIC -> {
                  if (musicSourceProvider == MusicSourceProvider.JELLYFIN) {
                    val jellyfinUiState by jellyfinViewModel.uiState.collectAsStateWithLifecycle()
                    if (jellyfinUiState.activeServer == null) {
                      Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                      ) {
                        androidx.compose.material3.Card(
                          shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                          colors =
                            androidx.compose.material3.CardDefaults.cardColors(
                              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                          Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                          ) {
                            androidx.compose.material3.Icon(
                              painter = painterResource(R.drawable.ic_jellyfin),
                              contentDescription = null,
                              modifier = Modifier.size(56.dp),
                              tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                              text = stringResource(R.string.music_source_jellyfin),
                              style = MaterialTheme.typography.titleLarge,
                              fontWeight = FontWeight.Bold,
                            )
                            Text(
                              text = stringResource(R.string.pref_jellyfin_no_server),
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            androidx.compose.material3.FilledTonalButton(
                              onClick = {
                                backStack.navigateTo(app.gyrolet.mpvrx.ui.preferences.MediaServersPreferencesScreen)
                              },
                            ) {
                              Text(stringResource(R.string.generic_configure))
                            }
                            androidx.compose.material3.TextButton(
                              onClick = {
                                mediaServerPreferences.musicSourceProvider.set(MusicSourceProvider.LOCAL)
                              },
                            ) {
                              Text(stringResource(R.string.music_source_local))
                            }
                          }
                        }
                      }
                    } else if (!jellyfinUiState.isLoading && !jellyfinUiState.hasMusicLibrary && jellyfinUiState.libraries.isNotEmpty()) {
                      Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                      ) {
                        androidx.compose.material3.Card(
                          shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                          colors =
                            androidx.compose.material3.CardDefaults.cardColors(
                              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                          Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                          ) {
                            androidx.compose.material3.Icon(
                              painter = painterResource(R.drawable.ic_jellyfin),
                              contentDescription = null,
                              modifier = Modifier.size(56.dp),
                              tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                              text = stringResource(R.string.music_source_jellyfin),
                              style = MaterialTheme.typography.titleLarge,
                              fontWeight = FontWeight.Bold,
                            )
                            Text(
                              text = stringResource(R.string.jellyfin_no_music_library),
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            androidx.compose.material3.FilledTonalButton(
                              onClick = {
                                mediaServerPreferences.musicSourceProvider.set(MusicSourceProvider.LOCAL)
                              },
                            ) {
                              Text(stringResource(R.string.music_source_local))
                            }
                            androidx.compose.material3.TextButton(
                              onClick = {
                                backStack.navigateTo(app.gyrolet.mpvrx.ui.preferences.MediaServersPreferencesScreen)
                              },
                            ) {
                              Text(stringResource(R.string.generic_configure))
                            }
                          }
                        }
                      }
                    } else {
                      LaunchedEffect(jellyfinUiState.libraries) {
                        jellyfinViewModel.ensureMusicDataLoaded()
                      }
                      app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinContent(
                        viewModel = jellyfinViewModel,
                        isMusicOnlyMode = true,
                      )
                    }
                  } else if (musicSourceProvider == MusicSourceProvider.NAVIDROME) {
                    val navidromeUiState by navidromeViewModel.uiState.collectAsStateWithLifecycle()
                    if (navidromeUiState.activeServer == null) {
                      Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                      ) {
                        androidx.compose.material3.Card(
                          shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                          colors =
                            androidx.compose.material3.CardDefaults.cardColors(
                              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                          Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                          ) {
                            androidx.compose.material3.Icon(
                              painter = painterResource(R.drawable.ic_navidrome),
                              contentDescription = null,
                              modifier = Modifier.size(56.dp),
                              tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                              text = stringResource(R.string.music_source_navidrome),
                              style = MaterialTheme.typography.titleLarge,
                              fontWeight = FontWeight.Bold,
                            )
                            Text(
                              text = stringResource(R.string.pref_navidrome_no_server),
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            androidx.compose.material3.FilledTonalButton(
                              onClick = {
                                backStack.navigateTo(app.gyrolet.mpvrx.ui.preferences.MediaServersPreferencesScreen)
                              },
                            ) {
                              Text(stringResource(R.string.generic_configure))
                            }
                            androidx.compose.material3.TextButton(
                              onClick = {
                                mediaServerPreferences.musicSourceProvider.set(MusicSourceProvider.LOCAL)
                              },
                            ) {
                              Text(stringResource(R.string.music_source_local))
                            }
                          }
                        }
                      }
                    } else {
                      app.gyrolet.mpvrx.ui.browser.navidrome.NavidromeContent(
                        viewModel = navidromeViewModel,
                        isMusicOnlyMode = true,
                      )
                    }
                  } else if (musicSourceProvider == MusicSourceProvider.AUDIOBOOKS) {
                    app.gyrolet.mpvrx.ui.browser.audiobooks.AudiobookLibraryContent(
                      isMusicTabMode = true,
                    )
                  } else {
                    MusicLibraryContent(
                      musicViewModel = musicLibraryViewModel,
                      jellyfinViewModel = jellyfinViewModel,
                      navidromeViewModel = navidromeViewModel,
                    )
                  }
                }
                MainTab.RECENTS -> RecentlyPlayedScreen.Content()
                MainTab.PLAYLISTS -> PlaylistScreen.Content()
                MainTab.NETWORK -> NetworkStreamingScreen.Content()
                MainTab.JELLYFIN -> app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinContent(viewModel = jellyfinViewModel)
                MainTab.SNAPSHOTS -> app.gyrolet.mpvrx.ui.framecapture.SnapshotScreen.Content()
                MainTab.PROFILE -> app.gyrolet.mpvrx.ui.browser.profile.ProfileScreen.Content()
              }
            }
          }
        }

        // Animated bottom navigation bar with slide animations
        ProvideLiquidGlassBackdrop(
          backdrop = navigationBackdrop,
          enabled = liquidGlassEnabled,
        ) {
          AnimatedVisibility(
            visible = !hideNavigationBar && navigationTabs.isNotEmpty() && !isPermissionDenied,
            enter = if (navStyle == NavigationAnimStyle.None) EnterTransition.None else
              slideInVertically(
                animationSpec = tween(duration, easing = FastOutSlowInEasing),
                initialOffsetY = { fullHeight -> fullHeight * 2 },
              ) + fadeIn(tween(duration)),
            exit = if (navStyle == NavigationAnimStyle.None) ExitTransition.None else
              slideOutVertically(
                animationSpec = tween(duration, easing = FastOutSlowInEasing),
                targetOffsetY = { fullHeight -> fullHeight * 2 },
              ) + fadeOut(tween(duration)),
            modifier =
              Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart),
          ) {
            BoxWithConstraints(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .navigationBarsPadding()
                  .padding(bottom = 8.dp),
            ) {
              val containerWidth = maxWidth
              val density = LocalDensity.current
              val centerFraction = animateFloatAsState(
                targetValue = when {
                  isDualPaneFolderSelected && selectedTab == MainTab.HOME -> 0.2f
                  isMiniPlayerVisible && (isLandscape || isTablet) -> 0f
                  else -> 0.5f
                },
                animationSpec = if (navStyle == NavigationAnimStyle.None) snap() else tween(duration, easing = FastOutSlowInEasing),
                label = "pill_alignment",
              )

              ExpressivePillNavigationBar(
                visibleTabs = navigationTabs,
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                pagerState = pagerState,
                hazeBackdrop = navigationBackdrop,
                liquidGlassEnabled = liquidGlassEnabled,
                modifier = Modifier
                  .layout { measurable, constraints ->
                    val margin = 28.dp.roundToPx()
                    val paneWidth =
                      if (isDualPaneFolderSelected && selectedTab == MainTab.HOME) {
                        (constraints.maxWidth * 0.4f).roundToInt()
                      } else {
                        constraints.maxWidth
                      }
                    val availableWidth = (paneWidth - margin * 2).coerceAtLeast(0)
                    val maxNuvioWidth = minOf(400.dp.roundToPx(), availableWidth)
                    val placeable =
                      measurable.measure(
                        constraints.copy(minWidth = 0, maxWidth = maxNuvioWidth),
                      )
                    layout(constraints.maxWidth, placeable.height) {
                      val desired =
                        (constraints.maxWidth * centerFraction.value - placeable.width / 2f)
                          .roundToInt()
                      val maxStart = (paneWidth - margin - placeable.width).coerceAtLeast(margin)
                      val start = desired.coerceIn(margin, maxStart)
                      placeable.placeRelative(start, 0)
                    }
                  }
                  .onGloballyPositioned { coords ->
                    val width = with(density) { coords.size.width.toDp() }
                    NavigationBarState.navbarWidth = width
                    NavigationBarState.navbarLeftOffset =
                      (containerWidth * centerFraction.value - width / 2).coerceAtLeast(28.dp)
                  },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun ExpressivePillNavigationBar(
  visibleTabs: List<MainScreen.MainTab>,
  selectedTab: MainScreen.MainTab,
  onTabSelected: (MainScreen.MainTab) -> Unit,
  modifier: Modifier = Modifier,
  pagerState: PagerState? = null,
  hazeBackdrop: HazeState? = null,
  liquidGlassEnabled: Boolean = false,
) {
  if (visibleTabs.isEmpty()) return

  val initialFocusRequester = rememberTvInitialFocusRequester(visibleTabs.isNotEmpty())
  val position =
    if (pagerState != null) {
      (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(
        0f,
        (visibleTabs.size - 1).toFloat(),
      )
    } else {
      visibleTabs.indexOf(selectedTab).coerceAtLeast(0).toFloat()
    }

  val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
  val accentColor = MaterialTheme.colorScheme.primary
  val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
  val selectedSurface = accentColor.copy(alpha = 0.15f)
  val hazeStyle =
    remember(surfaceColor) {
      HazeBlurStyle {
        // Matches Nuvio Android's floating bar blur treatment.
        blurRadius(24.dp)
        noiseFactor(0f)
        backgroundColor(surfaceColor.copy(alpha = 0.55f))
        colorEffects(listOf(HazeColorEffect.tint(surfaceColor.copy(alpha = 0.12f))))
        fallbackColorEffect(HazeColorEffect.tint(surfaceColor.copy(alpha = 0.82f)))
      }
    }

  val rimModifier =
    Modifier.drawWithCache {
      val strokeWidth = 0.75.dp.toPx()
      val rim =
        Brush.verticalGradient(
          listOf(
            Color.White.copy(alpha = 0.27f),
            Color.White.copy(alpha = 0.02f),
          ),
        )
      onDrawWithContent {
        drawContent()
        drawRoundRect(
          brush = rim,
          topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
          size = Size(size.width - strokeWidth, size.height - strokeWidth),
          cornerRadius = CornerRadius((size.height - strokeWidth) / 2f),
          style = Stroke(strokeWidth),
        )
      }
    }

  val trackContent: @Composable () -> Unit = {
    BoxWithConstraints(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(4.dp),
    ) {
      val tabWidth = maxWidth / visibleTabs.size
      val travelFraction =
        kotlin.math.abs(position - kotlin.math.round(position)).coerceIn(0f, 0.5f) * 2f

      Box(
        modifier =
          Modifier
            .offset(x = tabWidth * position)
            .width(tabWidth)
            .fillMaxHeight()
            .graphicsLayer {
              // Nuvio's selected jelly pill subtly stretches while travelling between tabs.
              scaleX = 1f + 0.08f * travelFraction
              scaleY = 1f - 0.035f * travelFraction
            }
            .clip(CircleShape)
            .background(selectedSurface),
      )

      Row(
        modifier = Modifier.fillMaxSize().selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        visibleTabs.forEachIndexed { index, tab ->
          key(tab) {
            val activeFraction = (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)
            val label =
              when (tab) {
                MainScreen.MainTab.HOME -> stringResource(R.string.ui_home)
                MainScreen.MainTab.MUSIC -> stringResource(R.string.ui_music)
                MainScreen.MainTab.RECENTS -> stringResource(R.string.ui_recents)
                MainScreen.MainTab.PLAYLISTS -> stringResource(R.string.ui_playlists)
                MainScreen.MainTab.NETWORK -> stringResource(R.string.ui_network)
                MainScreen.MainTab.JELLYFIN -> stringResource(R.string.ui_jellyfin)
                MainScreen.MainTab.SNAPSHOTS -> stringResource(R.string.ui_snapshots)
                MainScreen.MainTab.PROFILE -> stringResource(R.string.ui_profile)
              }
            val contentColor =
              androidx.compose.ui.graphics.lerp(
                mutedColor,
                accentColor,
                activeFraction,
              )

            Box(
              modifier =
                Modifier
                  .weight(1f)
                  .fillMaxHeight()
                  .then(if (tab == selectedTab) Modifier.tvInitialFocus(initialFocusRequester) else Modifier)
                  .tvFocusHighlight(CircleShape, focusedScale = 1.04f)
                  .clip(CircleShape)
                  .selectable(
                    selected = tab == selectedTab,
                    role = Role.Tab,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                  ) {
                    onTabSelected(tab)
                  },
              contentAlignment = Alignment.Center,
            ) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
              ) {
                MainTabIcon(tab, contentColor, label)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = label,
                  style =
                    MaterialTheme.typography.labelSmall.copy(
                      fontSize = 13.sp,
                      lineHeight = 16.sp,
                      fontWeight = if (activeFraction > 0.5f) FontWeight.Bold else FontWeight.Normal,
                    ),
                  color = contentColor,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
              }
            }
          }
        }
      }
    }
  }

  val baseModifier =
    modifier
      .widthIn(max = 400.dp)
      .fillMaxWidth()
      .height(64.dp)

  if (liquidGlassEnabled) {
    LiquidGlassSurface(
      modifier = baseModifier,
      shape = CircleShape,
      style = LiquidGlassStyle.Navigation,
      glassColor = surfaceColor.copy(alpha = 0.24f),
      fallbackColor = surfaceColor.copy(alpha = 0.82f),
      contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
      Box(Modifier.matchParentSize().then(rimModifier)) {
        trackContent()
      }
    }
  } else {
    Box(
      modifier =
        baseModifier
          .shadow(8.dp, CircleShape)
          .clip(CircleShape)
          .then(
            if (hazeBackdrop != null) {
              Modifier.hazeBlur(
                input = HazeInput.Sources(hazeBackdrop),
                style = hazeStyle,
              )
            } else {
              Modifier.background(surfaceColor.copy(alpha = 0.82f))
            },
          )
          .then(rimModifier),
    ) {
      trackContent()
    }
  }
}

@Composable
private fun MainTabIcon(
  tab: MainScreen.MainTab,
  tint: Color,
  contentDescription: String?,
) {
  if (tab == MainScreen.MainTab.PROFILE) {
    app.gyrolet.mpvrx.ui.browser.profile.ProfileAvatar(
      size = MainNavigationIconSize,
      tint = tint,
      contentDescription = contentDescription,
    )
    return
  }
  val icon = when (tab) {
    MainScreen.MainTab.HOME -> Icons.RoundedFilled.Home
    MainScreen.MainTab.MUSIC -> Icons.RoundedFilled.Audiotrack
    MainScreen.MainTab.RECENTS -> Icons.RoundedFilled.History
    MainScreen.MainTab.PLAYLISTS -> Icons.RoundedFilled.Subscriptions
    MainScreen.MainTab.NETWORK -> Icons.RoundedFilled.BringYourOwnIp
    MainScreen.MainTab.JELLYFIN -> null
    MainScreen.MainTab.SNAPSHOTS -> Icons.RoundedFilled.Image
    MainScreen.MainTab.PROFILE -> Icons.RoundedFilled.AccountCircle
  }
  if (icon == null) {
    androidx.compose.material3.Icon(
      painter = painterResource(R.drawable.ic_jellyfin),
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier.size(MainNavigationIconSize),
    )
  } else {
    Icon(
      icon,
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier.size(MainNavigationIconSize),
    )
  }
}

private val MainNavigationIconSize = 28.dp

/**
 * Bottom-navigation tab order. Home is the permanent root so Back never exits directly from another
 * tab. The Profile tab absorbs Recents, Playlists and Snapshots, which stay reachable from inside it.
 */
internal fun mainNavigationTabs(
  showMusic: Boolean,
  showProfile: Boolean,
  showRecents: Boolean,
  showPlaylists: Boolean,
  showNetwork: Boolean,
  showJellyfin: Boolean,
  showSnapshots: Boolean,
): List<MainScreen.MainTab> =
  buildList {
    add(MainScreen.MainTab.HOME)
    if (showMusic) add(MainScreen.MainTab.MUSIC)
    if (!showProfile && showRecents) add(MainScreen.MainTab.RECENTS)
    if (!showProfile && showPlaylists) add(MainScreen.MainTab.PLAYLISTS)
    if (showNetwork) add(MainScreen.MainTab.NETWORK)
    if (showJellyfin) add(MainScreen.MainTab.JELLYFIN)
    if (!showProfile && showSnapshots) add(MainScreen.MainTab.SNAPSHOTS)
    if (showProfile) add(MainScreen.MainTab.PROFILE)
  }

val LocalNavigationBarHeight = compositionLocalOf { 0.dp }

// True for a screen hosted as a bottom-navigation page; false when the same screen is pushed on the back stack.
val LocalIsMainTabPage = compositionLocalOf { false }

// CompositionLocal for main navigation bar
val LocalMainNavigationBar =
  compositionLocalOf<@Composable (Modifier) -> Unit> {
    { }
  }
