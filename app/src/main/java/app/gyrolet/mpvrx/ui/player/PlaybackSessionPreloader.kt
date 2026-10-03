/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.os.SystemClock
import android.util.Log
import app.gyrolet.mpvrx.domain.torrent.isTorrentSource
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Starts opening the tapped file *before* PlayerActivity exists.
 *
 * This is the piece of REX-Player's `HeadlessPlaybackController` that actually makes its open feel
 * instant, and it is a different win from [PlaybackCorePrewarmer]. The prewarmer removes libmpv's
 * `init()` from the critical path; this removes libmpv's *open* — probing the container, selecting
 * streams, standing up the demuxer and filling the first read-ahead — so all of that runs while
 * Android is still creating the Activity and composing the first Compose frame, instead of after.
 *
 * ## How it stays safe
 *
 * The pre-load deliberately produces a session that is **READY but paused**, which is the exact
 * shape `PlayerActivity.hasValidSavedPlaybackSession()` already accepts for handoff. That means:
 *
 * - `PlaybackSession.load` is given `holdPausedUntilAdopted`, so `desiredPaused` becomes true and no
 *   position-restore hold is armed. FILE_LOADED therefore settles the session straight into READY
 *   instead of parking it in LOADING waiting for an owner that does not exist yet. A LOADING session
 *   is exactly what that guard refuses to adopt, to avoid the black/00:00 state it documents.
 * - No audio escapes. The pre-load never unpauses; only an adopting Activity does, after it has
 *   applied the resume position itself.
 * - `PlaybackSession.load` already forces `vo=null` whenever no surface is attached (see its
 *   `if (!_state.value.surfaceAttached)` branch), so the headless phase decodes without a renderer
 *   and the Activity's `bindSurface` restores `vo` to `desiredVideoOutput` when its surface arrives.
 *
 * ## Adoption is always optional
 *
 * Nothing about the Activity's normal load path was removed. If the claim does not match — different
 * item, different index, a stale generation, an unrelated script restore, an audiobook, or a session
 * that something else already replaced — [claim] returns false, [discard] runs, and the Activity
 * performs exactly the load it would have performed before this existed. The worst outcome of a
 * pre-load is one wasted `loadfile`.
 *
 * ## What it will not disturb
 *
 * A pre-load replaces the queue, so it refuses to run at all while something is already playing or
 * loading: minimised background playback and the mini player must never be interrupted by the user
 * merely browsing towards a different video.
 */
internal object PlaybackSessionPreloader {
  private const val TAG = "PlaybackPreloader"

  /**
   * App-scoped rather than caller-scoped on purpose: the pre-load has to outlive the browser screen
   * that started it, because the whole point is to keep working during Activity creation. Cancelled
   * only by [discard] or a newer pre-load.
   */
  private val scope = CoroutineScope(SupervisorJob())

  private val lock = Any()

  /** Identity of the file the live session was pre-loaded for, or null when nothing is claimed. */
  private data class Claim(
    val index: Int,
    val stableId: String?,
    val originalUri: String,
    val generation: Long,
  )

  @Volatile private var claim: Claim? = null
  private var job: Job? = null

  /** Identifies the newest pre-load attempt, so a slow one cannot overwrite a newer claim. */
  private var attemptId = 0L

  /**
   * Whether the pre-warmer's core is usable *right now*.
   *
   * Checked rather than awaited: by the time a user has navigated a list and chosen a file the warm-up
   * has long since finished, and blocking a tap on it would trade the latency this whole mechanism
   * exists to remove. A cold core simply means no pre-load.
   */
  private fun coreIsWarm(): Boolean = PlaybackSession.isInitialized

  /**
   * Queues a pre-load for [items] starting at [index], replacing any previous one.
   *
   * Fire and forget by design. Every failure path is silent and harmless: the Activity reloads.
   */
  fun preload(
    items: List<PlaybackItem>,
    index: Int,
  ) {
    if (items.isEmpty()) return
    val selectedIndex = index.coerceIn(items.indices)
    val item = items[selectedIndex]

    // Refused here rather than left for claim() to reject afterwards. [stage] is also used by the
    // audiobook, network and streaming launch sites, and a loadfile for one of those has side
    // effects of its own (audiobook timers, network stream registration, torrent resolution) that
    // would already have happened by the time any adoption check could decline it.
    if (!isPreloadable(item)) {
      Log.d(TAG, "Skipping pre-load for a source that needs its own preparation")
      discard()
      return
    }

    synchronized(lock) {
      // Replacing a live session out from under whatever is playing would interrupt mini-player and
      // background playback just because the user scrolled towards another video.
      val state = PlaybackSession.state.value
      if (state.currentItem != null &&
        state.phase in setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
      ) {
        Log.d(TAG, "Skipping pre-load; a session is already ${state.phase}")
        cancelLocked()
        return
      }
      if (!coreIsWarm()) {
        Log.d(TAG, "Skipping pre-load; the libmpv core is not warm yet")
        return
      }
      cancelLocked()
      val myAttempt = ++attemptId
      job =
        scope.launch {
          val startedAt = SystemClock.elapsedRealtime()
          val generation =
            try {
              // The Activity may already have consumed this queue by the time this runs;
              // re-publishing it is harmless because replaceQueue is pure state and load replaces the
              // file outright.
              PlaybackSession.replaceQueue(
                items = items,
                currentIndex = selectedIndex,
                isExplicitQueue = true,
              )
              PlaybackSession.load(
                item = item,
                // The adopting Activity owns resume position, yt-dlp readiness and the
                // previous-stop wait. Pre-deciding any of that here would duplicate that
                // orchestration and could disagree with it, so the pre-load stays position-agnostic
                // and parks the file paused for whoever adopts it.
                restoreSavedPosition = false,
                holdPausedUntilAdopted = true,
              )
            } catch (cancellation: CancellationException) {
              // Must propagate: swallowing it would detach this coroutine from its scope.
              throw cancellation
            } catch (error: Throwable) {
              Log.w(TAG, "Pre-load failed; the Activity will load normally", error)
              synchronized(lock) {
                if (attemptId == myAttempt) claim = null
              }
              return@launch
            }

          if (generation < 0L) {
            Log.w(TAG, "Pre-load produced no generation; the Activity will load normally")
            return@launch
          }
          synchronized(lock) {
            // A newer pre-load may have replaced this one while the file was opening; do not let a
            // stale claim overwrite the newer one. An attempt counter is used rather than comparing
            // Job references because launch can start the body before the Job field is assigned.
            if (attemptId != myAttempt) return@launch
            claim =
              Claim(
                index = selectedIndex,
                stableId = item.stableId,
                originalUri = item.originalUri,
                generation = generation,
              )
          }
          Log.i(
            TAG,
            "Pre-loaded ${item.originalUri} generation=$generation in " +
              "${SystemClock.elapsedRealtime() - startedAt} ms",
          )
        }
    }
  }

  /**
   * Whether [PlaybackSession.load] on this item is a plain file open with no side effects.
   *
   * Deliberately conservative: anything that resolves a URI, registers a stream, or starts a timer
   * on load is excluded, because the pre-loader cannot run those steps and a half-prepared file is
   * worse than no pre-load at all.
   */
  private fun isPreloadable(item: PlaybackItem): Boolean {
    if (item.audiobook != null) return false
    if (item.networkSource != null) return false
    if (item.torrentFileIndex != null) return false
    if (item.isDefinitelyAudioOnly()) return false
    if (YtdlpManager.requiresYtdlp(item.originalUri)) return false
    if (YtdlpManager.requiresYtdlp(item.playableUri)) return false
    if (isTorrentSource(item.originalUri, null)) return false
    return true
  }

  /**
   * Returns the pre-loaded generation when the live session really is the one [item] at [index] wants.
   *
   * Every check is a hard requirement. A false answer costs only a normal load; a wrong true answer
   * would show the user the previous video, so the identity comparison is deliberately strict.
   */
  fun claim(
    item: PlaybackItem?,
    index: Int,
  ): Long? {
    val candidate = claim ?: return null
    if (item == null) return null
    if (candidate.index != index) return null
    val sameIdentity =
      candidate.stableId != null && item.stableId != null && candidate.stableId == item.stableId ||
        candidate.originalUri == item.originalUri
    if (!sameIdentity) return null

    val state = PlaybackSession.state.value
    if (!PlaybackSession.isCurrentGeneration(candidate.generation)) return null
    val liveItem = state.currentItem
    val liveMatches =
      liveItem != null &&
        (liveItem.stableId == item.stableId || liveItem.originalUri == item.originalUri)
    if (!liveMatches) return null
    // Only a settled session may be adopted. A still-LOADING pre-load would be reattached to a
    // half-open timeline, which is the black/00:00 failure hasValidSavedPlaybackSession() guards.
    if (state.phase != PlaybackPhase.READY) return null

    synchronized(lock) {
      if (claim !== candidate) return null
      claim = null
    }
    return candidate.generation
  }

  /** Drops any pre-load state. Safe to call when nothing was pre-loaded. */
  fun discard() {
    synchronized(lock) { cancelLocked() }
  }

  private fun cancelLocked() {
    job?.cancel()
    job = null
    claim = null
  }
}