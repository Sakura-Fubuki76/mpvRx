/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

@file:Suppress("DEPRECATION")

package app.gyrolet.mpvrx.ui.player

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.data.lyrics.LyricsTranslationService
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.domain.thumbnail.getArtwork
import app.gyrolet.mpvrx.domain.torrent.TorrentStreamingEngine
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.repository.lyrics.LyricsRepository
import app.gyrolet.mpvrx.ui.components.AppToast
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.floating.FloatingLyricsController
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Background playback service for mpv. A Media3 [MediaSession] mirrors mpv through
 * [MpvSessionPlayer], and Media3 posts and updates the media notification for it.
 */
@OptIn(UnstableApi::class)
class MediaPlaybackService :
  MediaSessionService(),
  MPVLib.EventObserver,
  KoinComponent {
  companion object {
    private const val TAG = "MediaPlaybackService"

    // Final state saves must outlive serviceScope, which is cancelled during teardown.
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_CHANNEL_ID = "mpvrx_playback_channel"
    private const val PLAYBACK_STATE_SAVE_INTERVAL_MS = 5000L
    private const val MEDIA_NOTIFICATION_UPDATE_INTERVAL_MS = 1000L
    // Scrubbing jumps the position on every step. Jumps republish at most this often, the last one late.
    private const val POSITION_JUMP_PUBLISH_INTERVAL_MS = 500L
    private const val MEDIA_SESSION_DRIFT_TOLERANCE_SECONDS = 2.0
    private const val MAX_MEDIA_SESSION_QUEUE_ITEMS = 200
    private const val VIDEO_CONTENT_INTENT_REQUEST_CODE = 1100
    private const val AUDIO_CONTENT_INTENT_REQUEST_CODE = 1101

    // MediaNotificationManager.SELF_INTENT_UID_KEY, on the start Media3 sends itself.
    private const val MEDIA3_SELF_START_KEY = "androidx.media3.session.intent.uid"

    // CustomCommandPendingIntentBuilder.ACTION_CUSTOM, the action of Media3's custom notification buttons.
    private const val MEDIA3_CUSTOM_ACTION = "androidx.media3.session.CUSTOM_NOTIFICATION_ACTION"
    const val ACTION_OPEN_PLAYER = "app.gyrolet.mpvrx.action.OPEN_PLAYER_FROM_NOTIFICATION"
    const val ACTION_NOTIFICATION_PREVIOUS = "app.gyrolet.mpvrx.action.NOTIFICATION_PREVIOUS"
    const val ACTION_NOTIFICATION_PLAY_PAUSE = "app.gyrolet.mpvrx.action.NOTIFICATION_PLAY_PAUSE"
    const val ACTION_NOTIFICATION_NEXT = "app.gyrolet.mpvrx.action.NOTIFICATION_NEXT"
    const val ACTION_NOTIFICATION_SHUFFLE = "app.gyrolet.mpvrx.action.NOTIFICATION_SHUFFLE"
    const val ACTION_NOTIFICATION_FAVORITE = "app.gyrolet.mpvrx.action.NOTIFICATION_FAVORITE"
    const val ACTION_NOTIFICATION_MEDIA_FAVORITE = "app.gyrolet.mpvrx.action.NOTIFICATION_MEDIA_FAVORITE"
    const val ACTION_NOTIFICATION_CLOSE = "app.gyrolet.mpvrx.action.NOTIFICATION_CLOSE"
    const val ACTION_NOTIFICATION_STOP = "app.gyrolet.mpvrx.action.NOTIFICATION_STOP"
    const val EXTRA_EXTERNAL_DISPLAY_ACTIVE = "external_display_active"

    @Volatile
    internal var thumbnail: Bitmap? = null

    @Volatile
    private var isServiceRunning = false

    @Volatile
    private var activeInstance: MediaPlaybackService? = null

    private val _foregroundActive = MutableStateFlow(false)
    internal val foregroundActive = _foregroundActive.asStateFlow()

    private fun publishForegroundActive() {
      _foregroundActive.value = isForegroundActive()
    }

    /**
     * True only while playback is detached from the foreground Activity.
     *
     * PlayerActivity historically used this query in onStart() as a signal to destroy the
     * playback service. Keeping the physical service alive while the Activity owns the surface
     * avoids tearing down and recreating the MediaSession/foreground notification on every
     * notification tap. This mirrors the single long-lived notification-owner model used by
     * MediaSessionService-style players.
     */
    fun isRunning(): Boolean = isServiceRunning && !activityForeground

    fun isForegroundActive(): Boolean =
      activeInstance?.let { service ->
        service.foregroundReady && !activityForeground && !service.handingBackToActivity
      } == true

    /** True once the service owns the foreground notification, independent of Activity focus. */
    internal fun isNotificationOwnerReady(): Boolean = activeInstance?.foregroundReady == true

    /**
     * True while a PlayerActivity is the active foreground owner of the shared playback session
     * (e.g. the full player is visible and playing, including audio-only media that has no
     * attached video surface). The service must not steal audio focus from it.
     *
     * Entering the foreground is an ownership handoff, not a service teardown: the service keeps
     * the MediaSession/notification alive but releases audio focus to PlayerActivity. Leaving the
     * foreground clears the handoff marker so detached playback can take ownership again.
     */
    @Volatile
    var activityForeground = false
      set(value) {
        field = value
        activeInstance?.let { service ->
          if (value) {
            service.handingBackToActivity = true
            service.abandonAudioOwnership()
          } else {
            service.handingBackToActivity = false
          }
          service.updatePlaybackWakeLock()
        }
        publishForegroundActive()
      }

    internal fun relinquishMediaSessionToActivity() {
      activeInstance?.deactivateMediaSession()
    }

    internal fun takeAudioOwnershipForDetachedPlayback(): Boolean = activeInstance?.takeAudioOwnership() == true

    /** A play action by the user: takes focus back even from an app that only borrowed it. */
    internal fun reclaimAudioFocusForPlay(): Boolean = activeInstance?.takeAudioOwnership(reclaim = true) == true

    // In-app full player transport. It shares the detached owner's queue handling, notification
    // updates and forced state saves, exactly like the notification and media-session controls.
    internal fun skipToNextFromApp(): Boolean = activeInstance?.playNextFromSession() == true

    internal fun skipToPreviousFromApp() {
      activeInstance?.playPreviousFromSession()
    }

    internal fun playQueueItemFromApp(index: Int): Boolean = activeInstance?.playQueueItemFromSession(index) == true

    internal fun setExternalDisplayActive(active: Boolean) {
      activeInstance?.updateExternalDisplayWakeLock(active)
    }

    /**
     * Marks that playback is being handed back to a foreground Activity (e.g. reopening the
     * player from the Mini Player / playback notification). Release the service-owned focus
     * immediately, but never mutate mpv's pause state: the foreground Activity will acquire
     * focus in its normal lifecycle. This makes the handoff lossless even when notification
     * re-entry and service teardown are delivered in different Android lifecycle turns.
     */
    internal fun prepareForActivityHandoff() {
      activeInstance?.let { service ->
        service.handingBackToActivity = true
        service.abandonAudioOwnership()
        service.updatePlaybackWakeLock()
      }
    }

    internal fun isActivityHandoffInProgress(): Boolean = activeInstance?.handingBackToActivity == true

    /** Releases every service-owned MPV access before an Activity destroys the global core. */
    internal fun prepareForMpvShutdown() {
      activeInstance?.let { service ->
        runCatching { service.releaseMpvAccessBeforeShutdown() }
          .onFailure { error -> Log.e(TAG, "Error preparing service for MPV shutdown", error) }
      }
    }

    internal fun stopForTerminalDismissal() {
      val service = activeInstance
      if (service != null && !service.mpvAccessReleased) {
        service.stopPlaybackAndService(force = true)
      } else {
        PlaybackSession.stop(clearQueue = true)
      }
    }

    internal fun stopExternalDisplayBackground() {
      activeInstance?.let { service ->
        service.stopForegroundNotification()
        service.stopSelf()
      }
    }

    fun createNotificationChannel(context: Context) {
      val channel =
        NotificationChannel(
          NOTIFICATION_CHANNEL_ID,
          context.getString(R.string.notification_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = context.getString(R.string.notification_channel_description)
          setShowBadge(false)
          enableLights(false)
          enableVibration(false)
        }

      (context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
        .createNotificationChannel(channel)
    }
  }

  private val binder = MediaPlaybackBinder()

  // One Media3 session per stretch of detached ownership: it is released when the service stops
  // owning playback, so Media3 never shows a stale notification after Close or a handoff.
  private var mediaSession: MediaSession? = null
  private var sessionPlayer: MpvSessionPlayer? = null
  private val sessionArtwork = SessionArtworkLoader()
  private val mainHandler = Handler(Looper.getMainLooper())
  private val sessionRefreshPending = AtomicBoolean(false)
  private val sessionRefresh =
    Runnable {
      sessionRefreshPending.set(false)
      sessionPlayer?.refresh()
    }

  // Media3 builds its notification only after its notification controller sees a playlist. Until
  // then a rebuild request would make it remove the notification, foreground state included.
  private var mediaNotificationShown = false
  private var publishedNotificationText: String? = null
  private var publishedFavoriteButton: Boolean? = null
  private val playerPreferences: PlayerPreferences by inject()
  private val audioPreferences: AudioPreferences by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val thumbnailRepository: ThumbnailRepository by inject()
  private val gesturePreferences: GesturePreferences by inject()
  private val playlistRepository: PlaylistRepository by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val torrentStreamingEngine: TorrentStreamingEngine by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val lyricsRepository: LyricsRepository by inject()
  private val lyricsTranslationService: LyricsTranslationService by inject()
  private var floatingLyrics: FloatingLyricsController? = null

  // Audio decision for loads this service drives on its own (detached playback), using the
  // same TrackSelector rules as the Activity path.
  private val detachedTrackSelector: TrackSelector by lazy {
    TrackSelector(audioPreferences, subtitlesPreferences)
  }

  @Volatile
  private var detachedRestoreGeneration: Long = 0L
  private var detachedRestoreJob: Job? = null

  private var mediaIdentifier = ""
  private var mediaTitle = ""
  private var mediaArtist = ""
  private var mediaUri: String? = null
  private var activeArtworkUri: String? = null
  private var cloudArtworkJob: kotlinx.coroutines.Job? = null
  private var paused = false
  private var playbackSpeed = 1.0f

  // Queue index of each item in the session's playlist, a window around the current item;
  // -1 for the single item published when the queue has none.
  private var publishedQueueIndexes: List<Int> = emptyList()
  private var lastSessionItemUid: Any? = null

  // Playlist state — mirrored from PlayerActivity so the notification intent can restore it
  private var notificationIsAudio: Boolean = false
  @Volatile
  private var lastNotificationUpdateTime = 0L
  @Volatile
  private var lastPublishedPositionSeconds = 0.0
  @Volatile
  private var lastPlaybackStateSaveTime = 0L

  // What the MediaSession last told the system, which extrapolates position from it.
  @Volatile
  private var sessionPositionSeconds = 0.0
  @Volatile
  private var sessionPositionUpdatedAt = 0L
  @Volatile
  private var sessionPositionSpeed = 0f

  // Chapter state for the notification's second line
  private var chapters: List<ChapterNode> = emptyList()
  private var currentChapterIndex: Int = -1
  @Volatile
  private var currentPositionSeconds: Double = 0.0
  private var mediaDurationSeconds: Double = 0.0
  private var lastThumbnailSource: WeakReference<Bitmap>? = null
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var playbackStateSaveJob: Job? = null
  // Only touched from the libmpv event thread.
  private var positionJumpPublishJob: Job? = null
  private var favoriteStateJob: Job? = null
  private var favoriteActionJob: Job? = null
  private val mediaFavoriteActionMutex = Mutex()
  @Volatile private var mpvAccessReleased = false
  @Volatile private var isCurrentFavorite = false
  private var usesAudioBackgroundPlayback = false
    set(value) {
      if (field != value) {
        field = value
        floatingLyrics?.onPlaybackKindChanged()
      }
    }
  private var externalDisplayWakeLock: PowerManager.WakeLock? = null
  private var playbackWakeLock: PowerManager.WakeLock? = null
  private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

  // Mutated from the framework's audio-focus callback thread as well as serviceScope and the
  // Activity's main thread; stale reads here strand focus and permanently duck other apps.
  @Volatile private var audioFocusRequest: AudioFocusRequest? = null

  @Volatile private var ownsAudioFocus = false

  @Volatile private var hasAudioFocus = false

  @Volatile
  private var handingBackToActivity = false
    set(value) {
      field = value
      publishForegroundActive()
    }

  @Volatile private var resumeAfterFocusGain = false

  @Volatile private var volumeBeforeDuck: Double? = null
  private var noisyReceiverRegistered = false
  private var playbackObserversStarted = false
  private var handledEofGeneration: Long? = null

  // The last notification Media3 built, reposted when a start must promote the service at once.
  @Volatile
  private var lastMediaNotification: Notification? = null
  @Volatile
  private var foregroundReady = false
    set(value) {
      field = value
      updatePlaybackWakeLock()
      publishForegroundActive()
    }
  private val audioFocusChangeListener =
    AudioManager.OnAudioFocusChangeListener { change ->
      if (mpvAccessReleased || handingBackToActivity || audioPreferences.ignoreAudioFocus.get()) {
        return@OnAudioFocusChangeListener
      }
      when (change) {
        AudioManager.AUDIOFOCUS_LOSS -> {
          resumeAfterFocusGain = false
          PlaybackSession.setPropertyBoolean("pause", true)
          abandonAudioOwnership()
        }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
          hasAudioFocus = false
          resumeAfterFocusGain =
            resumeAfterFocusGain || PlaybackSession.getPropertyBoolean("pause") == false
          PlaybackSession.setPropertyBoolean("pause", true)
        }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
          if (volumeBeforeDuck == null) {
            PlaybackSession.getPropertyDouble("volume")?.let { volume ->
              volumeBeforeDuck = volume
              PlaybackSession.setPropertyDouble("volume", volume * 0.5)
            }
          }
        }
        AudioManager.AUDIOFOCUS_GAIN -> {
          if (!ownsAudioFocus) return@OnAudioFocusChangeListener
          hasAudioFocus = true
          restoreDuckedVolume()
          if (resumeAfterFocusGain) PlaybackSession.setPropertyBoolean("pause", false)
          resumeAfterFocusGain = false
        }
      }
    }
  private val noisyReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        // Headphone disconnection still pauses detached playback when focus handling is disabled.
        if (canHandleDetachedTransport() &&
          intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY
        ) {
          PlaybackSession.setPropertyBoolean("pause", true)
        }
      }
    }

  inner class MediaPlaybackBinder : Binder() {
    fun getService() = this@MediaPlaybackService
  }

  fun isForegroundReady(): Boolean = foregroundReady

  private fun updateExternalDisplayWakeLock(active: Boolean) {
    if (active) {
      if (externalDisplayWakeLock?.isHeld != true) {
        externalDisplayWakeLock =
          (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:external-display")
            .apply { acquire() }
      }
    } else {
      externalDisplayWakeLock?.let { if (it.isHeld) it.release() }
      externalDisplayWakeLock = null
    }
  }

  /** The foreground notification alone does not keep the CPU running with the screen off. */
  @Synchronized
  private fun updatePlaybackWakeLock() {
    val state = PlaybackSession.state.value
    val shouldHold = foregroundReady && !mpvAccessReleased && !activityForeground &&
      !handingBackToActivity && !state.paused && state.currentItem != null &&
      state.phase in setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
    runCatching {
      if (shouldHold) {
        val wakeLock = playbackWakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
          .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:background-playback")
          .apply { setReferenceCounted(false) }
          .also { playbackWakeLock = it }
        // A movie can outlive any fixed timeout; pause/stop/handoff/shutdown release this lock.
        if (!wakeLock.isHeld) wakeLock.acquire()
      } else {
        playbackWakeLock?.let { if (it.isHeld) it.release() }
      }
    }.onFailure { error -> Log.e(TAG, "Could not update background playback wake lock", error) }
  }

  private fun deactivateMediaSession() {
    foregroundReady = false
    releaseMediaSession()
  }

  override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "Service created")

    activeInstance = this
    isServiceRunning = true
    mpvAccessReleased = false
    handingBackToActivity = false

    // Ensure notification channel exists before starting foreground service
    createNotificationChannel(this)
    setMediaNotificationProvider(PlaybackNotificationProvider())
    // Between files, and after an error, mpv is idle; the notification stays while the service
    // owns playback.
    setShowNotificationForIdlePlayer(MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)
    setListener(
      object : MediaSessionService.Listener {
        override fun onForegroundServiceStartNotAllowedException() {
          Log.w(TAG, "Android did not allow the media notification to become foreground")
        }
      },
    )

    // A bind can arrive before onStartCommand. Promote before any playback work so that cold
    // starts also meet Android's foreground deadline. The session is created first (it reads
    // no MPV state) so even this notification is the media notification.
    setupMediaSession()
    if (!startForegroundNotification()) {
      // Also releases the session, so media buttons cannot reach a service that never became
      // foreground.
      stopForegroundNotification()
      stopSelf()
    }
  }

  private fun startPlaybackObservers() {
    if (playbackObserversStarted) return
    playbackObserversStarted = true
    serviceScope.launch {
      audioPreferences.floatingLyricsEnabled.changes().distinctUntilChanged().collect { wanted ->
        if (!wanted || app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(this@MediaPlaybackService)) {
          stopFloatingLyrics()
          return@collect
        }
        if (floatingLyrics != null) return@collect
        val controller = FloatingLyricsController(
          context = this@MediaPlaybackService,
          preferences = audioPreferences,
          playerPreferences = playerPreferences,
          lyricsRepository = lyricsRepository,
          translations = lyricsTranslationService,
          thumbnails = thumbnailRepository,
          scope = serviceScope,
          isAudio = { resolveNotificationIsAudio(it, usesAudioBackgroundPlayback) },
          openPlayer = {
            if (!mpvAccessReleased) {
              try {
                buildContentIntent().send()
              } catch (error: PendingIntent.CanceledException) {
                Log.e(TAG, "Could not open the player from floating lyrics", error)
                AppToast.makeText(this@MediaPlaybackService, R.string.floating_lyrics_unavailable, AppToast.LENGTH_LONG).show()
              }
            }
          },
          togglePlay = {
            if (canHandleTransportAction()) handleMediaPlayAction(shouldPlay = PlaybackSession.state.value.paused)
          },
          previous = { playPreviousFromSession() },
          next = { playNextFromSession() },
          seek = { seekFromSession(it) },
        )
        floatingLyrics = controller
        controller.start()
      }
    }

    ContextCompat.registerReceiver(
      this,
      noisyReceiver,
      IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    noisyReceiverRegistered = true

    serviceScope.launch {
      // Includes pause, EOF, error, stop and native shutdown; no periodic wake-up timer.
      PlaybackSession.state.collect { updatePlaybackWakeLock() }
    }

    serviceScope.launch {
      audioPreferences.ignoreAudioFocus.changes().distinctUntilChanged().collect { ignore ->
        if (mpvAccessReleased) return@collect
        if (ignore) {
          abandonAudioOwnership()
        } else if (canHandleDetachedTransport() &&
          PlaybackSession.getPropertyBoolean("pause") == false && !takeAudioOwnership()
        ) {
          PlaybackSession.setPropertyBoolean("pause", true)
        }
      }
    }

    serviceScope.launch {
      // Audio always keeps playing in the background. Turning the video switch off ends a video
      // that is already playing there.
      playerPreferences.videoBackgroundPlayback.changes().distinctUntilChanged().drop(1).collect { enabled ->
        val item = PlaybackSession.state.value.currentItem
        val isAudio =
          item != null && (item.audiobook != null || resolveNotificationIsAudio(item, usesAudioBackgroundPlayback))
        if (!enabled && !isAudio) {
          Log.d(TAG, "Video background playback disabled; stopping service")
          stopDetachedPlaybackIfNeeded()
          stopForegroundNotification()
          stopSelf()
        }
      }
    }

    serviceScope.launch {
      PlaybackSession.queue.collect(::syncQueueState)
    }

    serviceScope.launch {
      // Detached loads (queue playback with no foreground Activity) have no Activity
      // restore path: without this collector nothing would re-add the file's external
      // audio, restore its saved manual selection, or correct mpv's automatic pick.
      // One restore runs per load generation, and only while this service — not an
      // Activity — owns playback, so the two paths never fight over the same load.
      PlaybackSession.state.collect { session ->
        if (session.phase != PlaybackPhase.READY && session.phase != PlaybackPhase.BACKGROUND) {
          return@collect
        }
        val generation = session.generation
        if (generation <= 0L || generation == detachedRestoreGeneration) return@collect
        if (!canHandleDetachedTransport()) return@collect
        val item = session.currentItem ?: return@collect
        detachedRestoreGeneration = generation
        detachedRestoreJob?.cancel()
        detachedRestoreJob =
          serviceScope.launch(Dispatchers.IO) {
            restoreDetachedAudioSelection(item.stableId, generation)
          }
      }
    }

    serviceScope.launch {
      thumbnailRepository.cacheRevision.drop(1).collect {
        replaceOwnedThumbnail(null)
        updateMediaSessionMetadata()
        updateNotification()
        MediaPlayerWidget.clearArtworkCache()
        MediaPlayerWidget.requestUpdate(this@MediaPlaybackService)
        PlaybackSession.queue.value.currentItem?.let(::loadSessionArtwork)
      }
    }

    // Only add MPV observer if MPV is initialized
    try {
      PlaybackSession.addObserver(this)
      PlaybackSession.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
      PlaybackSession.observeProperty("media-title", MPVLib.MpvFormat.MPV_FORMAT_STRING)
      PlaybackSession.observeProperty("metadata/by-key/artist", MPVLib.MpvFormat.MPV_FORMAT_STRING)
      PlaybackSession.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
      PlaybackSession.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
      PlaybackSession.observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
      PlaybackSession.observeProperty("chapter", MPVLib.MpvFormat.MPV_FORMAT_INT64)
      PlaybackSession.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
      Log.d(TAG, "MPV observer registered")
    } catch (e: Exception) {
      Log.e(TAG, "Error registering MPV observer", e)
    }
  }

  override fun onBind(intent: Intent?): IBinder? {
    setupMediaSession()
    // Media3 answers its own controllers and legacy media browsers; PlayerActivity binds without
    // an action.
    return super.onBind(intent) ?: binder.takeIf { intent?.action == null }
  }

  @SuppressLint("ForegroundServiceType")
  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    Log.d(TAG, "Service starting with startId: $startId")

    // Media3 starts the service itself right before it posts its notification as the foreground
    // one, so that start needs nothing more. One left over from a stopped instance must not keep
    // a new instance alive with only the placeholder.
    if (intent?.hasExtra(MEDIA3_SELF_START_KEY) == true) {
      if (!foregroundReady) {
        stopForegroundNotification()
        stopSelf(startId)
      }
      return START_NOT_STICKY
    }

    // Acknowledge EVERY startForegroundService request, including repeated starts and stop
    // actions. foregroundReady describes playback ownership, not the system's pending start.
    // Reuse the last notification without querying MPV or rebuilding artwork on this path. The
    // session exists first so even this first notification is the media notification.
    setupMediaSession()
    if (!startForegroundNotification(startupTitle = intent?.getStringExtra("media_title"))) {
      stopForegroundNotification()
      stopSelf(startId)
      return START_NOT_STICKY
    }

    if (!PlaybackSession.isInitialized) {
      Log.w(TAG, "Ignoring playback service start without a live playback session")
      stopForegroundNotification()
      stopSelf(startId)
      return START_NOT_STICKY
    }

    setupMediaSession()
    startPlaybackObservers()

    // Media buttons, and the buttons of Media3's notification, go to the session. Our own actions
    // (widgets, the mini player) are handled below.
    super.onStartCommand(intent, flags, startId)
    // Once this service owns playback, those need nothing else; taking ownership again would also
    // take audio focus from another app on a mere Favorite tap or dismissal.
    if (foregroundReady && (intent?.action == Intent.ACTION_MEDIA_BUTTON || intent?.action == MEDIA3_CUSTOM_ACTION)) {
      return START_NOT_STICKY
    }

    intent?.let {
      when (it.action) {
        ACTION_NOTIFICATION_PREVIOUS -> {
          playPreviousFromSession()
          if (foregroundReady) return START_NOT_STICKY
        }
        ACTION_NOTIFICATION_PLAY_PAUSE -> {
          togglePlaybackFromNotification()
          if (foregroundReady) return START_NOT_STICKY
        }
        ACTION_NOTIFICATION_NEXT -> {
          playNextFromSession()
          if (foregroundReady) return START_NOT_STICKY
        }
        ACTION_NOTIFICATION_SHUFFLE -> {
          if (canHandleTransportAction()) {
            val queueState = PlaybackSession.queue.value
            PlaybackSession.setShuffleEnabled(!queueState.shuffleEnabled)
            refreshTransportControls()
          }
          if (foregroundReady) return START_NOT_STICKY
        }
        ACTION_NOTIFICATION_FAVORITE -> {
          toggleCurrentItemFavorite()
          if (foregroundReady) return START_NOT_STICKY
        }
        ACTION_NOTIFICATION_MEDIA_FAVORITE -> {
          toggleCurrentMediaNotificationFavorite()
          if (foregroundReady) return START_NOT_STICKY
        }
        ACTION_NOTIFICATION_CLOSE,
        ACTION_NOTIFICATION_STOP,
        -> {
          stopPlaybackAndService(force = true)
          return START_NOT_STICKY
        }
      }

      val title = it.getStringExtra("media_title")
      if (it.hasExtra(EXTRA_EXTERNAL_DISPLAY_ACTIVE)) {
        updateExternalDisplayWakeLock(it.getBooleanExtra(EXTRA_EXTERNAL_DISPLAY_ACTIVE, false))
      }
      val artist = it.getStringExtra("media_artist")
      val uri = it.getStringExtra("media_uri")
      val identifier = it.getStringExtra("media_identifier")
      if (it.hasExtra("audio_background_playback")) {
        val isAudio = it.getBooleanExtra("audio_background_playback", false)
        usesAudioBackgroundPlayback = isAudio
        notificationIsAudio = isAudio
      }

      if (!title.isNullOrBlank()) {
        mediaTitle = FileTypeUtils.stripExtension(title)
        mediaArtist = artist ?: ""
        Log.d(TAG, "Media info from intent: $mediaTitle")
      }
      if (!identifier.isNullOrBlank()) {
        mediaIdentifier = identifier
      }
      if (!uri.isNullOrBlank()) {
        mediaUri = uri
      }
    }

    // Fallback: Read current state from MPV if not provided via intent
    if (mediaTitle.isBlank()) {
      mediaTitle = FileTypeUtils.stripExtension(PlaybackSession.getPropertyString("media-title") ?: "")
      mediaArtist = PlaybackSession.getPropertyString("metadata/by-key/artist") ?: ""
    }

    paused = PlaybackSession.getPropertyBoolean("pause") == true
    playbackSpeed =
      PlaybackSession
        .getPropertyDouble("speed")
        ?.toFloat()
        ?.takeIf { it.isFinite() && it > 0f }
        ?: 1.0f
    mediaDurationSeconds = runCatching { PlaybackSession.getPropertyDouble("duration") }.getOrNull() ?: 0.0
    currentPositionSeconds = runCatching { PlaybackSession.getPropertyDouble("time-pos") }.getOrNull() ?: 0.0
    currentChapterIndex = runCatching { PlaybackSession.getPropertyInt("chapter") }.getOrNull() ?: -1

    // Only take audio ownership for a truly detached session. Notification re-entry first moves
    // the process-wide session back to READY and/or marks a handoff. Re-requesting focus from the
    // service in that window can make PlayerActivity receive AUDIOFOCUS_LOSS and pause the video.
    val sessionState = PlaybackSession.state.value
    val shouldTakeDetachedAudioFocus =
      sessionState.phase == PlaybackPhase.BACKGROUND &&
        !sessionState.surfaceAttached &&
        !activityForeground &&
        !handingBackToActivity
    if (shouldTakeDetachedAudioFocus && !takeAudioOwnership()) {
      PlaybackSession.setPropertyBoolean("pause", true)
    }

    // The session now describes playback, so Media3 replaces the startup notification with its
    // media notification, kept foreground while this service owns playback.
    foregroundReady = true
    updateMediaSessionMetadata()
    publishMediaButtonPreferences()
    Log.d(TAG, "Foreground service started successfully")

    return START_NOT_STICKY
  }

  @SuppressLint("ForegroundServiceType")
  private fun startForegroundNotification(startupTitle: String? = null): Boolean =
    try {
      val type =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
          0
        }
      // Media3's own notification once it has built one, so a repeated start never swaps it for
      // the placeholder. It uses the same id, so Media3's next update replaces this one.
      val notification = lastMediaNotification ?: buildStartupNotification(startupTitle)
      ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
      true
    } catch (error: Exception) {
      Log.e(TAG, "Error starting foreground service", error)
      false
    }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
    mediaSession.takeUnless { mpvAccessReleased }

  /**
   * Media3 keeps the service foreground only while playing. This service owns detached playback,
   * paused or not, until it stops, exactly as before, so the notification stays foreground (and
   * the service alive) for a paused background video too.
   */
  override fun onUpdateNotificationAsync(
    session: MediaSession,
    startInForegroundRequired: Boolean,
  ): ListenableFuture<Void?> =
    super.onUpdateNotificationAsync(session, startInForegroundRequired || (foregroundReady && !mpvAccessReleased))

  fun setMediaInfo(
    title: String,
    artist: String,
    thumbnail: Bitmap? = null,
    uri: String? = null,
    identifier: String? = null,
  ) {
    serviceScope.launch {
      val resolvedTitle = FileTypeUtils.stripExtension(title)
      val resolvedIdentifier = identifier?.takeIf { it.isNotBlank() }
      // A metadata-only Activity sync must not erase artwork already resolved for this queue item.
      // Item changes are cleared explicitly in applySessionItem before the next cover is loaded.
      val artworkChanged = thumbnail?.let(::replaceOwnedThumbnail) ?: false
      val metadataChanged =
        mediaTitle != resolvedTitle ||
          mediaArtist != artist ||
          (uri != null && mediaUri != uri) ||
          (resolvedIdentifier != null && mediaIdentifier != resolvedIdentifier) ||
          artworkChanged

      mediaTitle = resolvedTitle
      mediaArtist = artist
      uri?.let { mediaUri = it }
      resolvedIdentifier?.let { mediaIdentifier = it }
      if (metadataChanged) {
        updateMediaSessionMetadata()
      }
      updateMediaSessionPlaybackState()
      updateNotification()
    }
  }

  private fun replaceOwnedThumbnail(source: Bitmap?): Boolean {
    if (source == null && thumbnail == null) return false
    if (source != null && source === lastThumbnailSource?.get() && thumbnail?.isRecycled == false) return false
    val ownedCopy =
      source?.let { bitmap ->
        runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
      }
    val previous = thumbnail
    lastThumbnailSource = source?.let(::WeakReference)
    thumbnail = ownedCopy
    // Media3's notification and session caches may still hold the previous artwork, so it is left
    // to the garbage collector rather than recycled here.
    sessionArtwork.publish(ownedCopy)
    return previous !== ownedCopy
  }

  fun setPlaylistInfo(isAudio: Boolean) {
    notificationIsAudio = isAudio
    usesAudioBackgroundPlayback = isAudio
  }

  /**
   * Takes audio focus so other apps pause while mpvRx plays. [reclaim] is for a play action by the
   * user: it asks again even while another app has only borrowed focus (a short sound or the
   * assistant), so mpvRx wins. During a phone call the request fails and playback waits.
   */
  fun takeAudioOwnership(reclaim: Boolean = false): Boolean {
    if (audioPreferences.ignoreAudioFocus.get()) {
      abandonAudioOwnership()
      return true
    }
    // During a foreground handoff, returning true means "ownership is intentionally not needed".
    // Do not request focus and bounce it back to PlayerActivity; that focus ping-pong is exactly
    // what can turn a notification tap into an unexpected pause.
    if (activityForeground || handingBackToActivity) return true
    if (ownsAudioFocus && (hasAudioFocus || !reclaim)) {
      if (!hasAudioFocus) resumeAfterFocusGain = true
      return hasAudioFocus
    }
    val request =
      audioFocusRequest ?: AudioFocusRequest
        .Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
          AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build(),
        ).setOnAudioFocusChangeListener(audioFocusChangeListener)
        .build()
        .also { audioFocusRequest = it }
    hasAudioFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    if (hasAudioFocus) {
      ownsAudioFocus = true
      resumeAfterFocusGain = false
    } else if (ownsAudioFocus) {
      // Still queued behind the borrower (a call); resume when it hands focus back.
      resumeAfterFocusGain = true
    }
    return hasAudioFocus
  }

  private fun abandonAudioOwnership() {
    restoreDuckedVolume()
    if (!ownsAudioFocus) {
      resumeAfterFocusGain = false
      return
    }
    val request = audioFocusRequest
    if (request != null) {
      audioManager.abandonAudioFocusRequest(request)
    } else {
      Log.w(TAG, "Owned audio focus without a request; focus may remain held")
    }
    audioFocusRequest = null
    ownsAudioFocus = false
    hasAudioFocus = false
    resumeAfterFocusGain = false
  }

  private fun restoreDuckedVolume() {
    volumeBeforeDuck?.let { volume -> PlaybackSession.setPropertyDouble("volume", volume) }
    volumeBeforeDuck = null
  }

  private fun playNextFromSession(): Boolean {
    if (!canHandleTransportAction()) return false
    schedulePlaybackStateSave(force = true)
    val item = PlaybackSession.playNext()
    if (item == null) {
      refreshTransportControls()
      return false
    }
    applySessionItem(item)
    return true
  }

  private fun playPreviousFromSession() {
    if (!canHandleTransportAction()) return
    schedulePlaybackStateSave(force = true)
    PlaybackSession.playPrevious()?.let(::applySessionItem) ?: refreshTransportControls()
  }

  private fun playQueueItemFromSession(index: Int): Boolean {
    if (!canHandleTransportAction()) return false
    schedulePlaybackStateSave(force = true)
    val item = PlaybackSession.playQueueItem(index)
    if (item == null) {
      refreshTransportControls()
      return false
    }
    applySessionItem(item)
    return true
  }

  private fun handleMediaPlayAction(shouldPlay: Boolean) {
    when (gesturePreferences.mediaPlayGesture.get()) {
      SingleActionGesture.PlayPause -> {
        if (shouldPlay && !PlaybackSession.state.value.surfaceAttached && !takeAudioOwnership(reclaim = true)) return
        PlaybackSession.setPropertyBoolean("pause", !shouldPlay)
        paused = !shouldPlay
        refreshTransportControls()
      }
      SingleActionGesture.Custom -> PlaybackSession.command("keypress", CustomKeyCodes.MediaPlay.keyCode)
      SingleActionGesture.Seek,
      SingleActionGesture.None,
      -> Unit
    }
  }

  private fun applySessionItem(item: PlaybackItem) {
    val itemChanged = mediaIdentifier != item.stableId || mediaUri != item.originalUri
    val artworkChanged = activeArtworkUri != item.artworkUri
    val isAudio = resolveNotificationIsAudio(item, notificationIsAudio)
    notificationIsAudio = isAudio
    usesAudioBackgroundPlayback = isAudio
    mediaIdentifier = item.stableId
    mediaTitle = FileTypeUtils.stripExtension(item.title.orEmpty()).ifBlank { getString(R.string.player_unknown_video) }
    mediaArtist = item.artist.orEmpty()
    mediaUri = item.originalUri
    activeArtworkUri = item.artworkUri
    currentPositionSeconds = 0.0
    lastPublishedPositionSeconds = 0.0
    mediaDurationSeconds = 0.0
    paused = false
    // Speed is core-wide and may remain unchanged across files, so no new property event is
    // guaranteed. Preserve the actual 2x/etc. rate used by Android's notification timeline.
    playbackSpeed =
      readMpvDouble("speed", playbackSpeed.toDouble())
        .toFloat().takeIf { it.isFinite() && it > 0f } ?: 1.0f
    isCurrentFavorite = false
    if (itemChanged || artworkChanged) {
      chapters = emptyList()
      currentChapterIndex = -1
      replaceOwnedThumbnail(null)
    }
    updateMediaSessionMetadata()
    updateMediaSessionPlaybackState()
    publishMediaButtonPreferences()
    updateNotification()
    loadSessionArtwork(item)
    refreshFavoriteState(item)
  }

  private fun refreshFavoriteState(item: PlaybackItem) {
    favoriteStateJob?.cancel()
    val expectedIdentifier = item.stableId
    val favoritePath = item.originalUri.ifBlank { item.playableUri }
    val isAudio = resolveNotificationIsAudio(item, notificationIsAudio)
    if (favoritePath.isBlank()) {
      if (isCurrentFavorite) {
        isCurrentFavorite = false
        refreshTransportControls()
      }
      return
    }
    favoriteStateJob =
      serviceScope.launch {
        playlistRepository.observeIsFavorite(favoritePath, isAudio).collect { favorite ->
          if (mediaIdentifier == expectedIdentifier) {
            if (isCurrentFavorite != favorite) {
              isCurrentFavorite = favorite
              refreshTransportControls()
            }
          }
        }
      }
  }

  /** The home-screen widget's favorite toggle. */
  private fun toggleCurrentItemFavorite() {
    if (favoriteActionJob?.isActive == true) return
    val item = PlaybackSession.queue.value.currentItem ?: return
    val favoritePath = item.originalUri.ifBlank { item.playableUri }
    val favoriteName = item.title?.takeIf { it.isNotBlank() } ?: mediaTitle
    val isAudio = resolveNotificationIsAudio(item, notificationIsAudio)
    favoriteActionJob =
      serviceScope.launch {
        runCatching {
          withContext(Dispatchers.IO) {
            playlistRepository.toggleFavorite(favoritePath, favoriteName, isAudio)
          }
        }.onFailure { error ->
          Log.e(TAG, "Failed to toggle favorite from notification", error)
        }
      }
  }

  /** Media notification favorite path: serialize taps and publish the resulting state immediately. */
  private fun toggleCurrentMediaNotificationFavorite() {
    val item = PlaybackSession.queue.value.currentItem ?: return
    val favoritePath = item.originalUri.ifBlank { item.playableUri }
    if (favoritePath.isBlank()) return
    val favoriteName = item.title?.takeIf { it.isNotBlank() } ?: mediaTitle
    val isAudio = resolveNotificationIsAudio(item, notificationIsAudio)
    val expectedIdentifier = item.stableId

    serviceScope.launch {
      runCatching {
        withContext(Dispatchers.IO) {
          mediaFavoriteActionMutex.withLock {
            playlistRepository.toggleFavorite(favoritePath, favoriteName, isAudio)
          }
        }
      }.onSuccess { favorite ->
        if (mediaIdentifier == expectedIdentifier && isCurrentFavorite != favorite) {
          isCurrentFavorite = favorite
          refreshTransportControls()
        }
      }.onFailure { error ->
        Log.e(TAG, "Failed to toggle favorite from media notification", error)
      }
    }
  }

  private fun loadSessionArtwork(item: PlaybackItem) {
    val revision = thumbnailRepository.cacheRevision.value
    val expectedIdentifier = item.stableId
    serviceScope.launch {
      // Cloud items have no local file to probe: their artwork comes from the network thumbnail cache.
      val loaded =
        item.networkSource?.let { source ->
          withContext(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(30_000) {
              thumbnailRepository.getThumbnailForNetworkSource(
                source.connectionId,
                source.relativePath,
                480,
                300,
                respectDisplayPreference = false,
              )
            }
          }
        } ?: thumbnailRepository.getArtwork(
          source = item.originalUri,
          artworkUri = item.artworkUri,
          isAudio = resolveNotificationIsAudio(item, usesAudioBackgroundPlayback),
          widthPx = 480,
        ) ?: return@launch
      if (mediaIdentifier != expectedIdentifier || revision != thumbnailRepository.cacheRevision.value) {        return@launch
      }
      val changed = replaceOwnedThumbnail(loaded)
      if (changed) {
        updateMediaSessionMetadata()
        updateNotification()
      }
    }
  }

  private fun resolveNotificationIsAudio(
    item: PlaybackItem,
    fallback: Boolean,
  ): Boolean =
    when (item.declaredMediaKind()) {
      DeclaredPlaybackMediaKind.AUDIO -> true
      DeclaredPlaybackMediaKind.VIDEO -> false
      DeclaredPlaybackMediaKind.UNKNOWN -> fallback
    }

  private fun syncQueueState(queueState: PlaybackQueueState) {
    // The session's playlist and shuffle mode are rebuilt from the queue on every refresh. Repeat
    // mode remains a player/queue feature, but is intentionally not published through the session
    // so the media notification cannot expose Repeat / Repeat One / Repeat All.
    val currentItem = queueState.currentItem
    val currentTitle =
      currentItem?.title?.let { FileTypeUtils.stripExtension(it) }.orEmpty().ifBlank {
        getString(R.string.player_unknown_video)
      }
    if (currentItem != null &&
      (currentItem.stableId != mediaIdentifier ||
        currentItem.originalUri != mediaUri ||
        currentTitle != mediaTitle ||
        currentItem.artist.orEmpty() != mediaArtist ||
        currentItem.artworkUri != activeArtworkUri)
    ) {
      applySessionItem(currentItem)
    } else {
      refreshTransportControls()
    }
  }

  private fun refreshTransportControls() {
    updateMediaSessionPlaybackState()
    publishMediaButtonPreferences()
    updateNotification()
    MediaPlayerWidget.requestUpdate(this)
  }

  private fun togglePlaybackFromNotification() {
    if (!canHandleTransportAction()) return
    val shouldPlay = PlaybackSession.getPropertyBoolean("pause") != false
    if (shouldPlay && !PlaybackSession.state.value.surfaceAttached && !takeAudioOwnership(reclaim = true)) return
    paused = !shouldPlay
    PlaybackSession.setPropertyBoolean("pause", paused)
    refreshTransportControls()
  }

  private fun stopPlaybackAndService(force: Boolean = false) {
    if (!force && !canHandleTransportAction()) return
    handingBackToActivity = false
    schedulePlaybackStateSave(force = true)
    torrentStreamingEngine.stopStream()
    PlaybackSession.stop(clearQueue = true)
    paused = true
    abandonAudioOwnership()
    // Releases the session, which removes Media3's notification.
    stopForegroundNotification()
    stopSelf()
  }

  private fun stopDetachedPlaybackIfNeeded(expectedGeneration: Long? = null) {
    // Never kill the shared PlaybackSession media while an Activity is taking it back over.
    // A fresh launch may already have destroyed and recreated the shared session before this
    // service's asynchronous onDestroy() callback reaches here.
    if ((mpvAccessReleased && expectedGeneration == null) || activityForeground || handingBackToActivity ||
      activeInstance !== this
    ) return
    PlaybackSession.stopIfDetached(
      expectedGeneration = expectedGeneration ?: PlaybackSession.state.value.generation,
      ownerIsActive = { activeInstance === this && !activityForeground && !handingBackToActivity },
      beforeStop = { torrentStreamingEngine.stopStream() },
    )
  }

  private fun handleDetachedEndOfFile(expectedGeneration: Long, naturalEnd: Boolean = false) {
    // A retained TextureView, mini player or external display is not an Activity playback owner.
    // Only the foreground handoff decides whether the Activity or service advances the queue.
    if (!canHandleDetachedTransport() || !foregroundReady) return
    val state = PlaybackSession.state.value
    if (state.generation != expectedGeneration || handledEofGeneration == expectedGeneration) return
    if (state.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND) &&
      !(naturalEnd && state.phase == PlaybackPhase.IDLE)
    ) return
    if (!naturalEnd) {
      if (PlaybackSession.getPropertyBoolean("eof-reached") != true) return
      // Read the live timeline here; the notification's throttled/coroutine-updated fields can
      // still describe the previous position when the EOF property event arrives.
      val duration = PlaybackSession.getPropertyDouble("duration") ?: mediaDurationSeconds
      val position = PlaybackSession.getPropertyDouble("time-pos") ?: currentPositionSeconds
      if (duration > 0.0 && position < duration - 2.0) return
    }
    handledEofGeneration = expectedGeneration
    if (AudiobookPlayback.handleEndOfFile()) return
    val queueState = PlaybackSession.queue.value
    val autoplay = queueState.currentItem?.audiobook != null ||
      if (notificationIsAudio) playerPreferences.autoplayNextAudio.get() else playerPreferences.autoplayNextVideo.get()
    when {
      queueState.repeatMode == RepeatMode.ONE -> {
        PlaybackSession.command("seek", "0", "absolute")
        PlaybackSession.setPropertyBoolean("pause", false)
      }
      autoplay || queueState.repeatMode == RepeatMode.ALL -> {
        if (!playNextFromSession()) stopPlaybackAndService()
      }
      else -> stopPlaybackAndService()
    }
  }

  fun setChapters(chapters: List<ChapterNode>) {
    serviceScope.launch {
      this@MediaPlaybackService.chapters =
        chapters
          .filter { it.time.isFinite() && it.time >= 0f }
          .distinctBy { it.time }
          .sortedBy { it.time }
      currentChapterIndex =
        runCatching { PlaybackSession.getPropertyInt("chapter") }
          .getOrNull()
          ?.takeIf { it in this@MediaPlaybackService.chapters.indices }
          ?: -1
      updateNotification()
    }
  }

  private fun setupMediaSession() {
    if (mediaSession != null || mpvAccessReleased) return
    val player = MpvSessionPlayer()
    publishedFavoriteButton = isCurrentFavorite
    publishedNotificationText = null
    mediaNotificationShown = false
    val session =
      MediaSession
        .Builder(this, player)
        .setId("$TAG-${SystemClock.elapsedRealtimeNanos()}")
        .setCallback(SessionCallback())
        .setBitmapLoader(sessionArtwork)
        .setMediaButtonPreferences(mediaButtonPreferences())
        .setSessionActivity(buildContentIntent())
        .build()
    sessionPlayer = player
    mediaSession = session
    // Media3 tracks the session for its notification from now on, not only once a controller binds.
    addSession(session)
  }

  /** Releasing the session makes Media3 remove its notification and disconnect its controllers. */
  private fun releaseMediaSession() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { releaseMediaSession() }
      return
    }
    mainHandler.removeCallbacks(sessionRefresh)
    sessionRefreshPending.set(false)
    lastMediaNotification = null
    mediaNotificationShown = false
    lastSessionItemUid = null
    val session = mediaSession ?: return
    val player = sessionPlayer
    mediaSession = null
    sessionPlayer = null
    runCatching { session.release() }
      .onFailure { error -> Log.e(TAG, "Error releasing media session", error) }
    runCatching { player?.release() }
      .onFailure { error -> Log.e(TAG, "Error releasing media session player", error) }
  }

  /** Coalesces the several changes of one mpv event into a single session update. */
  private fun invalidateSessionPlayer() {
    if (sessionPlayer != null && sessionRefreshPending.compareAndSet(false, true)) {
      mainHandler.post(sessionRefresh)
    }
  }

  private fun canHandleDetachedTransport(): Boolean =
    !mpvAccessReleased && !activityForeground && !handingBackToActivity

  private fun canHandleTransportAction(): Boolean =
    !mpvAccessReleased && (activityForeground || !handingBackToActivity)

  private fun updateMediaSessionMetadata() {
    mediaSession?.setSessionActivity(buildContentIntent())
    invalidateSessionPlayer()
  }

  private fun updateMediaSessionPlaybackState() = invalidateSessionPlayer()

  /** True once the system's extrapolated position is off, e.g. after a stall or a speed change. */
  private fun sessionPositionDrifted(now: Long): Boolean {
    val elapsedSeconds = (now - sessionPositionUpdatedAt).coerceAtLeast(0L) / 1000.0
    val extrapolated = sessionPositionSeconds + elapsedSeconds * sessionPositionSpeed
    return abs(currentPositionSeconds - extrapolated) > MEDIA_SESSION_DRIFT_TOLERANCE_SECONDS
  }

  /**
   * Media3 rebuilds its notification when playback state, metadata or the queue change. The chapter
   * line is mpvRx's own, so a new chapter asks for a rebuild.
   */
  private fun updateNotification() {
    if (!foregroundReady) return
    lastNotificationUpdateTime = SystemClock.elapsedRealtime()
    lastPublishedPositionSeconds = currentPositionSeconds
    invalidateSessionPlayer()
    val text = chapterContentText()
    if (mediaNotificationShown && text != publishedNotificationText) {
      publishedNotificationText = text
      triggerNotificationUpdate()
    }
  }

  private fun sanitizedDurationMs(): Long {
    val seconds = mediaDurationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: return 0L
    return (seconds * 1000.0).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
  }

  private fun sanitizedPositionMs(): Long {
    // Queue selection publishes the incoming title before native loadfile replaces the old file.
    // Never put the outgoing position (or a cached position) under the incoming notification.
    currentPositionSeconds = PlaybackSession.readLoadedPlaybackState(mediaIdentifier) { position, _ -> position } ?: 0.0
    val seconds = currentPositionSeconds.takeIf { it.isFinite() && it > 0.0 } ?: return 0L
    return (seconds * 1000.0)
      .coerceAtMost(Long.MAX_VALUE.toDouble())
      .toLong()
      .coerceAtMost(sanitizedDurationMs().takeIf { it > 0L } ?: Long.MAX_VALUE)
  }

  // ==================== Media3 Session ====================

  /**
   * The session's view of mpv. It stays empty until this service owns playback, so Media3 shows no
   * notification for a service that is only starting up.
   */
  private fun buildSessionState(): SimpleBasePlayer.State {
    if (!foregroundReady || mpvAccessReleased) {
      publishedQueueIndexes = emptyList()
      lastSessionItemUid = null
      return SimpleBasePlayer.State
        .Builder()
        .setAvailableCommands(Player.Commands.Builder().add(Player.COMMAND_RELEASE).build())
        .setPlaybackState(Player.STATE_IDLE)
        .build()
    }
    val durationMs = sanitizedDurationMs()
    val positionMs = sanitizedPositionMs()
    val queueState = PlaybackSession.queue.value
    val playlist = ArrayList<SimpleBasePlayer.MediaItemData>()
    val queueIndexes = ArrayList<Int>()
    var currentIndex = 0
    if (queueState.currentIndex in queueState.items.indices) {
      val unknownTitle = getString(R.string.player_unknown_video)
      val halfWindow = MAX_MEDIA_SESSION_QUEUE_ITEMS / 2
      val firstIndex =
        (queueState.currentIndex - halfWindow)
          .coerceAtLeast(0)
          .coerceAtMost((queueState.items.size - MAX_MEDIA_SESSION_QUEUE_ITEMS).coerceAtLeast(0))
      val lastExclusive = (firstIndex + MAX_MEDIA_SESSION_QUEUE_ITEMS).coerceAtMost(queueState.items.size)
      for (index in firstIndex until lastExclusive) {
        val item = queueState.items[index]
        val uid = "$index:${item.stableId}"
        if (index == queueState.currentIndex) {
          currentIndex = playlist.size
          playlist += currentSessionItem(uid, item.stableId, durationMs)
        } else {
          playlist += queuedSessionItem(uid, item, unknownTitle)
        }
        queueIndexes += index
      }
    } else {
      playlist += currentSessionItem("current", mediaIdentifier, durationMs)
      queueIndexes += -1
    }
    publishedQueueIndexes = queueIndexes

    val hasPrevious = PlaybackSession.hasPrevious()
    val hasNext = PlaybackSession.hasNext()
    val commands =
      Player.Commands
        .Builder()
        .addAll(
          Player.COMMAND_PLAY_PAUSE,
          Player.COMMAND_STOP,
          Player.COMMAND_SEEK_TO_MEDIA_ITEM,
          Player.COMMAND_SET_SHUFFLE_MODE,
          Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
          Player.COMMAND_GET_TIMELINE,
          Player.COMMAND_GET_METADATA,
          Player.COMMAND_RELEASE,
        ).addIf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, durationMs > 0L)
        .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, hasPrevious)
        .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPrevious)
        .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNext)
        .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNext)
        .build()
    // Media3 keeps showing the notification for an idle player while this service owns playback.
    val playbackState =
      when (PlaybackSession.state.value.phase) {
        PlaybackPhase.READY, PlaybackPhase.BACKGROUND -> Player.STATE_READY
        PlaybackPhase.LOADING, PlaybackPhase.INITIALIZING, PlaybackPhase.IDLE, PlaybackPhase.STOPPING ->
          Player.STATE_BUFFERING
        PlaybackPhase.UNINITIALIZED, PlaybackPhase.ERROR -> Player.STATE_IDLE
      }
    sessionPositionSeconds = positionMs / 1000.0
    sessionPositionSpeed = if (playbackState == Player.STATE_READY && !paused) playbackSpeed else 0f
    sessionPositionUpdatedAt = SystemClock.elapsedRealtime()
    val state =
      SimpleBasePlayer.State
        .Builder()
        .setAvailableCommands(commands)
        .setPlayWhenReady(!paused, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackState(playbackState)
        .setShuffleModeEnabled(queueState.shuffleEnabled)
        .setPlaybackParameters(PlaybackParameters(playbackSpeed))
        .setPlaylist(playlist)
        .setCurrentMediaItemIndex(currentIndex)
        .setContentPositionMs(positionMs)
    // The system extrapolates position from the last update it got, so each update within an item
    // carries mpv's position. A change of item reports its own transition.
    val itemUid = playlist[currentIndex].uid
    if (itemUid == lastSessionItemUid) state.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_INTERNAL, positionMs)
    lastSessionItemUid = itemUid
    return state.build()
  }

  private fun currentSessionItem(
    uid: String,
    mediaId: String,
    durationMs: Long,
  ): SimpleBasePlayer.MediaItemData {
    val title = mediaTitle.ifBlank { getString(R.string.player_unknown_video) }
    val metadata =
      MediaMetadata
        .Builder()
        .setTitle(title)
        .setDisplayTitle(title)
        .setArtist(mediaArtist.takeIf { it.isNotBlank() })
        .setArtworkUri(sessionArtwork.uri)
        .setDurationMs(durationMs.takeIf { it > 0L })
        .setMediaType(if (notificationIsAudio) MediaMetadata.MEDIA_TYPE_MUSIC else MediaMetadata.MEDIA_TYPE_VIDEO)
        .build()
    val durationUs = if (durationMs > 0L && durationMs < Long.MAX_VALUE / 1000L) durationMs * 1000L else C.TIME_UNSET
    return SimpleBasePlayer.MediaItemData
      .Builder(uid)
      .setMediaItem(MediaItem.Builder().setMediaId(mediaId.ifBlank { uid }).setMediaMetadata(metadata).build())
      .setMediaMetadata(metadata)
      .setDurationUs(durationUs)
      .setIsSeekable(durationUs != C.TIME_UNSET)
      .build()
  }

  private fun queuedSessionItem(
    uid: String,
    item: PlaybackItem,
    unknownTitle: String,
  ): SimpleBasePlayer.MediaItemData {
    val metadata =
      MediaMetadata
        .Builder()
        .setTitle(item.title?.takeIf { it.isNotBlank() } ?: unknownTitle)
        .setArtist(item.artist?.takeIf { it.isNotBlank() })
        .build()
    return SimpleBasePlayer.MediaItemData
      .Builder(uid)
      .setMediaItem(MediaItem.Builder().setMediaId(item.stableId.ifBlank { uid }).setMediaMetadata(metadata).build())
      .setMediaMetadata(metadata)
      .build()
  }

  private fun seekFromSession(positionMs: Long) {
    if (!canHandleTransportAction()) return
    val duration = sanitizedDurationMs()
    val resolvedPosition = positionMs.coerceIn(0L, duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
    currentPositionSeconds = resolvedPosition / 1000.0
    PlaybackSession.setPropertyDouble("time-pos", currentPositionSeconds)
    refreshTransportControls()
  }

  private fun mediaButtonPreferences(): List<CommandButton> =
    listOf(
      CommandButton
        .Builder(if (isCurrentFavorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
        .setDisplayName(favoriteActionLabel())
        .setCustomIconResId(favoriteActionIcon())
        .setSessionCommand(SessionCommand(ACTION_NOTIFICATION_MEDIA_FAVORITE, Bundle.EMPTY))
        .setSlots(CommandButton.SLOT_OVERFLOW)
        .build(),
      CommandButton
        .Builder(CommandButton.ICON_UNDEFINED)
        .setDisplayName(getString(R.string.notification_close))
        .setCustomIconResId(Icons.Platform.Close)
        .setSessionCommand(SessionCommand(ACTION_NOTIFICATION_CLOSE, Bundle.EMPTY))
        .setSlots(CommandButton.SLOT_OVERFLOW)
        .build(),
    )

  /** A favorite change replaces the buttons, and Media3 rebuilds its notification with them. */
  private fun publishMediaButtonPreferences() {
    val session = mediaSession ?: return
    if (!mediaNotificationShown || publishedFavoriteButton == isCurrentFavorite) return
    publishedFavoriteButton = isCurrentFavorite
    session.setMediaButtonPreferences(mediaButtonPreferences())
  }

  /** mpv behind Media3's Player API; every command goes to the same handlers as the old session. */
  private inner class MpvSessionPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    fun refresh() = invalidateState()

    override fun getState(): State = buildSessionState()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
      if (canHandleTransportAction()) handleMediaPlayAction(shouldPlay = playWhenReady)
      return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
      mediaItemIndex: Int,
      positionMs: Long,
      seekCommand: Int,
    ): ListenableFuture<*> {
      // Previous and next follow mpvRx's queue rules (shuffle, repeat), whatever index Media3 implies.
      when (seekCommand) {
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> playPreviousFromSession()
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> playNextFromSession()
        Player.COMMAND_SEEK_TO_MEDIA_ITEM ->
          publishedQueueIndexes.getOrNull(mediaItemIndex)?.takeIf { it >= 0 }?.let { playQueueItemFromSession(it) }
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> if (positionMs != C.TIME_UNSET) seekFromSession(positionMs)
      }
      return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
      // Stopping releases this player, so it runs once this call has returned.
      mainHandler.post { if (canHandleTransportAction()) stopPlaybackAndService() }
      return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
      if (canHandleDetachedTransport()) PlaybackSession.setShuffleEnabled(shuffleModeEnabled)
      return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
  }

  private inner class SessionCallback : MediaSession.Callback {
    override fun onConnectAsync(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.ConnectionResult> {
      val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
      // The notification controller's commands also become the platform session's custom actions.
      if (controller.isTrusted) {
        result.setAvailableSessionCommands(
          MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
            .buildUpon()
            .add(SessionCommand(ACTION_NOTIFICATION_MEDIA_FAVORITE, Bundle.EMPTY))
            .add(SessionCommand(ACTION_NOTIFICATION_CLOSE, Bundle.EMPTY))
            .build(),
        )
      }
      return Futures.immediateFuture(result.build())
    }

    override fun onCustomCommand(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      customCommand: SessionCommand,
      args: Bundle,
    ): ListenableFuture<SessionResult> {
      val action = customCommand.customAction
      if (action != ACTION_NOTIFICATION_MEDIA_FAVORITE && action != ACTION_NOTIFICATION_CLOSE) {
        return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
      }
      // Close releases this session, so both run once the command has returned.
      mainHandler.post {
        if (!canHandleTransportAction()) return@post
        if (action == ACTION_NOTIFICATION_CLOSE) {
          stopPlaybackAndService(force = true)
        } else {
          toggleCurrentMediaNotificationFavorite()
        }
      }
      return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
  }

  /**
   * Hands Media3 the artwork this service already decoded. Every request resolves at once, so Media3
   * builds each notification in one pass and never posts a late one on its own.
   */
  private class SessionArtworkLoader : BitmapLoader {
    private var artwork: Bitmap? = null
    private var revision = 0L

    @Volatile
    var uri: Uri? = null
      private set

    @Synchronized
    fun publish(bitmap: Bitmap?) {
      artwork = bitmap
      uri = bitmap?.let { Uri.parse("mpvrx-artwork://session/${++revision}") }
    }

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
      val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
      return if (bitmap != null) {
        Futures.immediateFuture(bitmap)
      } else {
        Futures.immediateFailedFuture(IllegalArgumentException("Undecodable artwork"))
      }
    }

    @Synchronized
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
      val current = artwork
      return if (current != null && uri == this.uri) {
        Futures.immediateFuture(current)
      } else {
        Futures.immediateFailedFuture(IllegalStateException("Artwork is no longer current"))
      }
    }
  }

  /** Media3's media notification with mpvRx's channel, id, icon and chapter line. */
  private inner class PlaybackNotificationProvider : MediaNotification.Provider {
    private val delegate =
      object : DefaultMediaNotificationProvider(
        this@MediaPlaybackService,
        DefaultMediaNotificationProvider.NotificationIdProvider { NOTIFICATION_ID },
        NOTIFICATION_CHANNEL_ID,
        R.string.notification_channel_name,
      ) {
        override fun getNotificationContentText(metadata: MediaMetadata): CharSequence =
          chapterContentText().also { publishedNotificationText = it }
      }.apply { setSmallIcon(R.drawable.ic_launcher_monochrome) }

    override fun createNotification(
      mediaSession: MediaSession,
      mediaButtonPreferences: ImmutableList<CommandButton>,
      actionFactory: MediaNotification.ActionFactory,
      onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
      val created =
        delegate.createNotification(mediaSession, mediaButtonPreferences, actionFactory) { changed ->
          lastMediaNotification = changed.notification
          onNotificationChangedCallback.onNotificationChanged(changed)
        }
      lastMediaNotification = created.notification
      if (!mediaNotificationShown && mediaSession === this@MediaPlaybackService.mediaSession) {
        mediaNotificationShown = true
        // Buttons that changed before this first notification are published now.
        mainHandler.post { publishMediaButtonPreferences() }
      }
      return created
    }

    override fun handleCustomCommand(
      session: MediaSession,
      action: String,
      extras: Bundle,
    ): Boolean = delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
      delegate.notificationChannelInfo
  }

  // ==================== Notification Builders ====================

  /**
   * The first foreground notification, posted before MPV is read, until Media3 posts its own.
   * Without the notification permission Android shows only media notifications, so, as in
   * mpv-android, it is already the media notification of the session while something is loaded. A
   * bind without live playback keeps a plain placeholder, so no empty media control appears.
   */
  private fun buildStartupNotification(title: String?): Notification {
    val builder =
      NotificationCompat
        .Builder(this, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        .setPriority(NotificationCompat.PRIORITY_LOW)
    val session = mediaSession
    if (session == null || !PlaybackSession.isInitialized || PlaybackSession.state.value.currentItem == null) {
      return builder.setContentTitle(getString(R.string.notification_channel_name)).build()
    }
    val startupTitle =
      title?.takeIf { it.isNotBlank() }?.let { FileTypeUtils.stripExtension(it) }
        ?: mediaTitle.ifBlank { getString(R.string.notification_channel_name) }
    return builder
      .setContentTitle(startupTitle)
      .setContentIntent(buildContentIntent())
      .setStyle(MediaStyleNotificationHelper.MediaStyle(session))
      .build()
      .apply { extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, session.platformToken) }
  }

  private fun buildContentIntent(): PendingIntent {
    val currentItem = PlaybackSession.queue.value.currentItem
    val isAudio =
      notificationIsAudio || currentItem?.declaredMediaKind() == DeclaredPlaybackMediaKind.AUDIO
    val targetUri = currentItem?.originalUri?.takeIf { it.isNotBlank() } ?: mediaUri
    val targetTitle = currentItem?.title?.takeIf { it.isNotBlank() } ?: mediaTitle
    val targetIdentifier = currentItem?.stableId?.takeIf { it.isNotBlank() } ?: mediaIdentifier
    val contentIntent =
      Intent(this, PlayerActivity::class.java).apply {
        action = ACTION_OPEN_PLAYER
        type = currentItem?.mimeType ?: "audio/*".takeIf { isAudio }
        targetUri?.let { putExtra("uri", it) }
        putExtra("title", targetTitle)
        putExtra("media_identifier", targetIdentifier)
        putExtra(
          "position",
          (currentPositionSeconds
            .takeIf { it.isFinite() && it > 0.0 }
            ?.toLong()
            ?: 0L)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt(),
        )
        putExtra("launch_source", "notification")
        putExtra("internal_launch", true)
        putExtra("is_audio", isAudio)
        putExtra("media_library_audio", isAudio)
        currentItem?.audiobook?.let { book ->
          putExtra(AudiobookPlayback.EXTRA_BOOK_ID, book.bookId)
          putExtra(AudiobookPlayback.EXTRA_TRACK_ID, book.trackId)
        }
        flags =
          Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_NEW_TASK
      }

    return PendingIntent.getActivity(
      this,
      if (isAudio) AUDIO_CONTENT_INTENT_REQUEST_CODE else VIDEO_CONTENT_INTENT_REQUEST_CODE,
      contentIntent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  private fun favoriteActionIcon(): Int =
    if (isCurrentFavorite) Icons.Platform.Favorite else Icons.Platform.FavoriteBorder

  private fun favoriteActionLabel(): String =
    getString(
      if (isCurrentFavorite) R.string.notification_saved_to_favorites else R.string.notification_add_to_favorites,
    )

  private fun chapterContentText(): String {
    val chapterName =
      if (currentChapterIndex >= 0) {
        chapters.getOrNull(currentChapterIndex)?.title?.takeIf { it.isNotBlank() }
      } else {
        null
      }
    return chapterName ?: mediaArtist.ifBlank { getString(R.string.notification_playing) }
  }

  private fun stopForegroundNotification() {
    foregroundReady = false
    // Media3 removes the notification of the released session as well.
    releaseMediaSession()
    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
      .onFailure { error -> Log.e(TAG, "Error stopping foreground notification", error) }
    runCatching {
      val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.cancel(NOTIFICATION_ID)
    }.onFailure { error ->
      Log.e(TAG, "Error canceling playback notification", error)
    }
  }

  // ==================== MPV Event Observers ====================

  override fun eventProperty(property: String) {}

  override fun eventProperty(
    property: String,
    value: Long,
  ) {
    if (property == "chapter") {
      serviceScope.launch {
        currentChapterIndex = value.takeIf { it in 0L..chapters.lastIndex.toLong() }?.toInt() ?: -1
        updateNotification()
      }
    }
  }

  override fun eventProperty(
    property: String,
    value: Boolean,
  ) {
    when (property) {
      "pause" -> {
        serviceScope.launch {
          paused = value
          // Any start of detached playback, including a new song that starts on its own, takes
          // audio focus so other apps pause. Without it (a call) playback waits instead.
          if (!value && canHandleDetachedTransport() && !hasAudioFocus && !takeAudioOwnership()) {
            paused = true
            PlaybackSession.setPropertyBoolean("pause", true)
          }
          updateMediaSessionPlaybackState()
          updateNotification()
          schedulePlaybackStateSave(force = true)
        }
      }
      "eof-reached" -> {
        // keep-open never emits a natural END_FILE, so detached advance must key off this flag.
        val generation = PlaybackSession.state.value.generation
        serviceScope.launch {
          if (value) {
            handleDetachedEndOfFile(generation)
          } else if (handledEofGeneration == generation) {
            // Repeat-one and a user seek can reach EOF again within the same media generation.
            handledEofGeneration = null
          }
        }
      }
    }
  }

  override fun eventProperty(
    property: String,
    value: String,
  ) {
    when (property) {
      "media-title" -> {
        if (value.isNotBlank()) {
          serviceScope.launch {
            if (mediaTitle == value) return@launch
            mediaTitle = value
            updateMediaSessionMetadata()
            updateNotification()
          }
        }
      }
      "metadata/by-key/artist" -> {
        serviceScope.launch {
          if (mediaArtist == value) return@launch
          mediaArtist = value
          updateMediaSessionMetadata()
          updateNotification()
        }
      }
    }
  }

  override fun eventProperty(
    property: String,
    value: Double,
  ) {
    when (property) {
      "time-pos" -> {
        currentPositionSeconds = value.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val currentTime = SystemClock.elapsedRealtime()
        val updateInterval = MEDIA_NOTIFICATION_UPDATE_INTERVAL_MS
        val elapsedSinceUpdate = (currentTime - lastNotificationUpdateTime).coerceAtLeast(0L)
        val expectedAdvance = elapsedSinceUpdate / 1000.0 * playbackSpeed.coerceAtLeast(0f)
        val positionJump = abs(currentPositionSeconds - lastPublishedPositionSeconds) > expectedAdvance + 3.0
        if (positionJump && elapsedSinceUpdate < POSITION_JUMP_PUBLISH_INTERVAL_MS) {
          // A scrub jumps on every step. Republishing each jump rebuilt the notification and the
          // MediaSession state on the main thread many times a second, right while the finger moved.
          schedulePositionJumpPublish(POSITION_JUMP_PUBLISH_INTERVAL_MS - elapsedSinceUpdate)
          return
        }
        if (elapsedSinceUpdate >= updateInterval || positionJump) {
          positionJumpPublishJob?.cancel()
          positionJumpPublishJob = null
          lastNotificationUpdateTime = currentTime
          lastPublishedPositionSeconds = currentPositionSeconds
          // System media controls draw progress from the session and advance it themselves, so
          // the position is republished only after a jump or once their estimate has drifted.
          val republish = positionJump || sessionPositionDrifted(currentTime)
          serviceScope.launch {
            schedulePlaybackStateSave()
            if (republish) updateMediaSessionPlaybackState()
          }
        }
      }
      "duration" -> {
        serviceScope.launch {
          val resolvedDuration = value.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
          if (mediaDurationSeconds == resolvedDuration) return@launch
          mediaDurationSeconds = resolvedDuration
          updateMediaSessionMetadata()
          updateMediaSessionPlaybackState()
          updateNotification()
        }
      }
      "speed" -> {
        serviceScope.launch {
          val resolvedSpeed = value.toFloat().takeIf { it.isFinite() && it > 0f } ?: 1.0f
          if (playbackSpeed == resolvedSpeed) return@launch
          playbackSpeed = resolvedSpeed
          updateMediaSessionPlaybackState()
          updateNotification()
        }
      }
    }
  }

  override fun eventProperty(
    property: String,
    value: MPVNode,
  ) {}

  override fun event(
    eventId: Int,
    data: MPVNode,
  ) {
    if (eventId == MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN) {
      Log.d(TAG, "MPV shutdown event received, stopping service")
      savePlaybackStateNow()
      stopSelf()
      return
    }

    if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
      if (PlaybackSession.isNaturalEndFile(data)) {
        val generation = PlaybackSession.state.value.generation
        serviceScope.launch { handleDetachedEndOfFile(generation, naturalEnd = true) }
      }

      // The current file has finished (or been quit). Release the static
      // thumbnail Bitmap reference now so it does not linger in the
      // companion object for the entire process lifetime — which can be
      // long if the service is killed by the system without onDestroy
      // being called. The next file loaded will set a fresh thumbnail
      // via setMediaInfo(). See issue 2.4 in the leak audit.
      // We null the companion field (not the local instance field) so
      // the next setMediaInfo call starts from a clean state.
      if (PlaybackSession.queue.value.currentItem == null) replaceOwnedThumbnail(null)
    }
  }

  /**
   * The detached counterpart of PlayerActivity's restore, audio side only: re-add the
   * file's stored external audio tracks, apply its saved manual selection, and — only
   * when no manual selection applied — run the automatic correction, in the same order
   * and under the same mpv.conf ownership rule as the foreground path.
   */
  private suspend fun restoreDetachedAudioSelection(identifier: String, generation: Long) {
    if (identifier.isBlank()) return
    if (!PlaybackSession.isCurrentGeneration(generation) || !canHandleDetachedTransport()) return
    ExternalAudioDiscovery.autoload(this@MediaPlaybackService, generation)
    if (!PlaybackSession.isCurrentGeneration(generation) || !canHandleDetachedTransport()) return
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AUDIO_TRACK_SELECTION)) return

    val state = runCatching { playbackStateRepository.getVideoDataByTitle(identifier) }.getOrNull()
    if (!PlaybackSession.isCurrentGeneration(generation) || !canHandleDetachedTransport()) return

    if (state != null && state.externalAudio.isNotBlank()) {
      for (audioUri in state.externalAudio.split("|").filter { it.isNotBlank() }) {
        if (!PlaybackSession.isCurrentGeneration(generation)) return
        val uri = Uri.parse(audioUri)
        val path = runCatching { uri.resolveUri(this@MediaPlaybackService) }.getOrNull() ?: audioUri
        // "auto": present but not force-selected; the manual restore below decides.
        ExternalAudioDiscovery.addIfAbsent(path, generation)
      }
    }
    if (!PlaybackSession.isCurrentGeneration(generation)) return

    if (state != null && detachedTrackSelector.applySavedAudioSelection(state, generation)) return
    detachedTrackSelector.applyAutomaticAudioSelection(generation)
  }

  /** Publishes the newest position once the jump window closes, so a scrub's final spot still lands. */
  private fun schedulePositionJumpPublish(delayMillis: Long) {
    if (positionJumpPublishJob?.isActive == true) return
    positionJumpPublishJob =
      serviceScope.launch {
        delay(delayMillis)
        lastNotificationUpdateTime = SystemClock.elapsedRealtime()
        lastPublishedPositionSeconds = currentPositionSeconds
        schedulePlaybackStateSave()
        updateMediaSessionPlaybackState()
      }
  }

  private fun schedulePlaybackStateSave(force: Boolean = false) {
    val identifier = mediaIdentifier
    if (identifier.isBlank()) return

    val now = SystemClock.elapsedRealtime()
    if (!force && now - lastPlaybackStateSaveTime < PLAYBACK_STATE_SAVE_INTERVAL_MS) return
    lastPlaybackStateSaveTime = now

    playbackStateSaveJob?.cancel()
    playbackStateSaveJob =
      serviceScope.launch(Dispatchers.IO) {
        // The capture is several synchronous libmpv reads; doing them here keeps this periodic
        // save from stalling the main thread every few seconds while media plays.
        val snapshot = capturePlaybackStateSnapshot(identifier, oldState = null) ?: return@launch
        persistPlaybackState(identifier, snapshot)
      }
  }

  private fun savePlaybackStateNow() {
    val identifier = mediaIdentifier
    if (identifier.isBlank()) return
    // Every libmpv read happens here, so the database write can safely outlive the service.
    val snapshot = capturePlaybackStateSnapshot(identifier, oldState = null) ?: return

    playbackStateSaveJob?.cancel()
    persistenceScope.launch {
      runCatching {
        persistPlaybackState(identifier, snapshot)
      }.onFailure { error ->
        Log.e(TAG, "Error force-saving playback state", error)
      }
    }
  }

  private fun savePlaybackStateBeforeTaskRemoval() {
    val identifier = mediaIdentifier
    if (identifier.isBlank()) return
    val snapshot = capturePlaybackStateSnapshot(identifier, oldState = null) ?: return

    val pendingSave = playbackStateSaveJob
    runBlocking(Dispatchers.IO) {
      pendingSave?.cancelAndJoin()
      persistPlaybackState(identifier, snapshot)
    }
  }

  private suspend fun persistPlaybackState(
    identifier: String,
    capturedSnapshot: PlaybackStateSnapshot,
  ) {
    if (identifier.isBlank() || capturedSnapshot.mediaIdentifier != identifier) return

    runCatching {
      val oldState = playbackStateRepository.getVideoDataByTitle(identifier)
      val snapshot =
        if (capturedSnapshot.externalSubtitles.isBlank() && !oldState?.externalSubtitles.isNullOrBlank()) {
          capturedSnapshot.copy(externalSubtitles = oldState.externalSubtitles.orEmpty())
        } else {
          capturedSnapshot
        }
      val playbackState =
        PlaybackStatePersistence.buildEntity(
          oldState = oldState,
          snapshot = snapshot,
          savePositionOnQuit = playerPreferences.savePositionOnQuit.get(),
          watchedThreshold = browserPreferences.watchedThreshold.get(),
        )
      playbackStateRepository.upsert(playbackState)
      PlaybackStateEvents.notifyChanged(identifier)
    }.onFailure { error ->
      Log.e(TAG, "Error saving playback state from service", error)
    }
  }

  /*
   * Capture every native value before the first database suspension. This prevents a delayed save
   * for item A from reading item B's libmpv properties and writing them under A's identifier.
   */
  private fun capturePlaybackStateSnapshot(
    identifier: String,
    oldState: PlaybackStateEntity?,
  ): PlaybackStateSnapshot? {
    if (identifier.isBlank()) return null

    return PlaybackSession.readLoadedPlaybackState(identifier) { position, duration ->
      PlaybackStateSnapshot(
        mediaIdentifier = identifier,
        mediaTitle = mediaTitle.ifBlank { identifier },
        currentPosition = position.toInt(),
        duration = duration.toInt(),
        isPositionRestorePending =
          PlaybackSession.isPositionRestorePending(PlaybackSession.state.value.activeGeneration),
        isLive = PlaybackSession.isLiveMedia(),
        playbackSpeed = readMpvDouble("speed", oldState?.playbackSpeed ?: DEFAULT_PLAYBACK_STATE_SPEED),
        videoZoom = PlaybackSession.videoZoom.value,
        sid = readMpvTrackId("sid", oldState?.sid ?: -1),
        secondarySid = readMpvTrackId("secondary-sid", oldState?.secondarySid ?: -1),
        subDelayMs =
          (
            readMpvDouble(
              "sub-delay",
              (oldState?.subDelay ?: 0) / PLAYBACK_STATE_MILLISECONDS_TO_SECONDS.toDouble(),
            ) * PLAYBACK_STATE_MILLISECONDS_TO_SECONDS
          ).toInt(),
        subSpeed = readMpvDouble("sub-speed", oldState?.subSpeed ?: DEFAULT_PLAYBACK_STATE_SUB_SPEED),
        aid = readMpvTrackId("aid", oldState?.aid ?: -1),
        sessionManualAudioId = captureSessionManualAudioId(identifier),
        audioDelayMs =
          (
            readMpvDouble(
              "audio-delay",
              (oldState?.audioDelay ?: 0) / PLAYBACK_STATE_MILLISECONDS_TO_SECONDS.toDouble(),
            ) * PLAYBACK_STATE_MILLISECONDS_TO_SECONDS
          ).toInt(),
        externalSubtitles = oldState?.externalSubtitles.orEmpty(),
      )
    }
  }

  /**
   * Same rule as PlayerActivity's capture: a manual decision is persisted only when the
   * user picked a track by hand for this file and the current track is still that pick
   * (readMpvTrackId reports "no" as -1; the manual convention uses 0 for audio off).
   * External audio needs no field here: the snapshot leaves it blank and buildEntity
   * keeps the stored list, exactly like externalSubtitles on this path.
   */
  private fun captureSessionManualAudioId(identifier: String): Int? {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AUDIO_TRACK_SELECTION)) return null
    val marked = ManualAudioSelectionTracker.markedTrackId(identifier) ?: return null
    val current = readMpvTrackId("aid", -1)
    val currentSelectionId = if (current < 0) 0 else current
    return marked.takeIf { it == currentSelectionId }
  }

  private fun readMpvDouble(
    property: String,
    fallback: Double,
  ): Double =
    runCatching {
      PlaybackSession.getPropertyDouble(property) ?: fallback
    }.getOrDefault(fallback)

  private fun readMpvTrackId(
    property: String,
    fallback: Int,
  ): Int =
    runCatching {
      when (val value = PlaybackSession.getPropertyString(property)) {
        null -> fallback
        "no" -> -1
        else -> value.toIntOrNull() ?: fallback
      }
    }.getOrDefault(fallback)

  /**
   * Snapshots playback and unregisters callbacks while libmpv is still alive. This method is
   * idempotent because Activity teardown can prepare the service before stopService() later
   * delivers Service.onDestroy().
   */
  private fun releaseMpvAccessBeforeShutdown() {
    if (mpvAccessReleased) return
    stopFloatingLyrics()
    mpvAccessReleased = true
    updatePlaybackWakeLock()

    runCatching { savePlaybackStateNow() }
      .onFailure { error -> Log.e(TAG, "Error saving playback state before MPV shutdown", error) }
    runCatching { PlaybackSession.removeObserver(this) }
      .onFailure { error -> Log.e(TAG, "Error removing MPV observer", error) }
    runCatching { serviceScope.cancel() }
      .onFailure { error -> Log.e(TAG, "Error canceling playback service work", error) }
    // The session would otherwise keep sending controller commands to the shut-down core.
    releaseMediaSession()
  }

  private fun stopFloatingLyrics() {
    floatingLyrics?.stop()
    floatingLyrics = null
  }

  override fun onDestroy() {
    val detachedGeneration =
      if (mpvAccessReleased || activityForeground || handingBackToActivity || activeInstance !== this) {
        null
      } else {
        PlaybackSession.state.value.takeUnless { it.surfaceAttached }?.generation
      }
    try {
      Log.d(TAG, "Service destroyed")

      updateExternalDisplayWakeLock(false)
      releaseMpvAccessBeforeShutdown()
      foregroundReady = false
      releaseMediaSession()
      abandonAudioOwnership()
      if (noisyReceiverRegistered) {
        runCatching { unregisterReceiver(noisyReceiver) }
        noisyReceiverRegistered = false
      }
      if (activeInstance === this) isServiceRunning = false

      try {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } catch (e: Exception) {
        Log.e(TAG, "Error stopping foreground", e)
      }

      try {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
      } catch (e: Exception) {
        Log.e(TAG, "Error canceling notification", e)
      }

      // Media3 caches may still hold the artwork, so it is left to the garbage collector.
      thumbnail = null
      sessionArtwork.publish(null)

      Log.d(TAG, "Service cleanup completed")
    } catch (e: Exception) {
      Log.e(TAG, "Error in onDestroy", e)
    } finally {
      if (detachedGeneration != null) {
        runCatching { stopDetachedPlaybackIfNeeded(detachedGeneration) }
          .onFailure { Log.e(TAG, "Error stopping detached playback after service destruction", it) }
      }
      if (activeInstance === this) {
        isServiceRunning = false
        activeInstance = null
      }
      handingBackToActivity = false
      super.onDestroy()
    }
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    // A player still on screen (picture-in-picture lives in its own task) keeps playing when
    // another mpvRx card is cleared. Media3's default would pause it, so it is not called.
    if (activityForeground) return
    // Clearing mpvRx from Recents ends its background playback, music as well as video.
    Log.d(TAG, "Task removed - ending playback")
    savePlaybackStateBeforeTaskRemoval()
    if (!mpvAccessReleased) {
      stopPlaybackAndService(force = true)
    } else {
      torrentStreamingEngine.stopStream()
      PlaybackSession.stop(clearQueue = true)
      stopForegroundNotification()
      stopSelf()
    }
  }
}
