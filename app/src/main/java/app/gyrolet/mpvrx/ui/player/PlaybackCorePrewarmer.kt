/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Xml
import android.view.ViewGroup
import android.widget.FrameLayout
import app.gyrolet.mpvrx.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * Warms the process-wide libmpv core so a video open does not have to pay for it.
 *
 * ## Why the core is initialized here rather than in PlayerActivity
 *
 * `MPVLib.init()` is not a cheap call. It `dlopen`s a 30-40MB shared library, parses the user's
 * mpv.conf with its profiles and includes, loads every selected Lua/JS script, and materializes the
 * GLSL shader chain. All of that used to run on the main thread inside `PlayerActivity.setupMPV()`,
 * strictly after the Activity had been created, its view tree inflated and its first Compose frame
 * composed — so it sat in series with window creation, and the video's first frame could not arrive
 * until all of it had finished.
 *
 * Moving it earlier needs a core owner that does not depend on an Activity, and the reason it could
 * not simply be done at app launch is circular: libmpv must not initialize before its config and
 * scripts are on disk, and preparing those needs a Context that had SAF tree access — which used to
 * mean PlayerActivity. [PlaybackStartupAssets] is the seam that breaks that cycle, so this object
 * can now do the whole sequence from the application context.
 *
 * ## How it initializes the core without a View
 *
 * The core is initialized through a real, never-attached [MPVView], because that is where
 * `initOptions()`/`postInitOptions()`/`observeProperties()` live — moving ~130 lines of renderer,
 * decoder, subtitle and networking option selection out of the View would duplicate that logic in
 * two places and let the two drift. The offscreen View is never added to a window, so its
 * SurfaceHolder never becomes valid, no surface is ever bound and `vo` stays `null`: libmpv
 * initializes, but no renderer is created. This mirrors how REX-Player warms its core in
 * `HeadlessPlaybackController.createOffWindowView()`.
 *
 * ## How PlayerActivity adopts it
 *
 * Nothing here is a special case for the Activity. [MPVView.initializeSession] derives a
 * configuration key from the renderer backend, mpv.conf overrides, config cache and script
 * selection, and `PlaybackSession.initialize` returns early when the live core already matches
 * that key. The prewarmer computes the same key from the same preferences, so a match means
 * "already warm" and the open costs a lock acquisition instead of a core init. A preference change
 * between launch and open changes the key, which correctly recreates the core.
 */
internal object PlaybackCorePrewarmer {
  private const val TAG = "PlaybackCorePrewarmer"

  /**
   * Guards the one-shot publication of [warmCompletion].
   *
   * A plain monitor rather than a `Mutex`: the critical section is a single field read/write that
   * never suspends, and [start] is deliberately not a `suspend` function so it can be called from
   * `Application.onCreate` without a coroutine scope of its own. The warm-up work runs outside the
   * lock entirely, so nothing here can block on the initialization it is guarding.
   */
  private val warmLock = Any()

  @Volatile private var warmCompletion: CompletableDeferred<Result<Boolean>>? = null

  /**
   * Retained deliberately: [MPVView.initializeSession] registers itself on its own SurfaceHolder,
   * which holds a strong reference to the callback. Dropping the View would leave the holder
   * pointing at a collected callback. One unattached View for the whole process is not worth
   * risking that, and REX-Player keeps its equivalent reference for the same reason.
   */
  @Volatile private var offscreenView: MPVView? = null

  // No teardown latch is needed here, and that is a deliberate difference from REX-Player's
  // MPVLifecycleLock. `PlaybackSession.initialize` and `PlaybackSession.destroy` both hold the same
  // fair `nativeLock` around every `MPVLib.create`/`MPVLib.destroy`, so a warm-up racing the idle
  // reaper blocks until the reaper's destroy has returned and then observes a cleanly destroyed
  // core, rather than half of one. REX needed an explicit latch because it drove the global native
  // singleton from two owners without such a lock; a second lock here would serialize the same two
  // operations twice without adding a guarantee.

  /**
   * Starts the warm-up on [scope] if it is not already running or finished, and returns the
   * deferred the caller may await. Idempotent: repeated calls join the first attempt rather than
   * starting a second `MPVLib.init`.
   */
  fun start(
    context: Context,
    scope: CoroutineScope,
  ): CompletableDeferred<Result<Boolean>> {
    warmCompletion?.let { return it }
    val completion = CompletableDeferred<Result<Boolean>>()
    val published =
      synchronized(warmLock) {
        warmCompletion ?: completion.also { warmCompletion = it }
      }
    if (published !== completion) return published

    scope.launch(Dispatchers.IO) { runWarmUp(context.applicationContext, completion) }
    return completion
  }

  /**
   * Forgets a warm-up that did not produce a usable core, so the next open is free to initialize
   * again instead of adopting the failure.
   */
  fun invalidate() {
    warmCompletion = null
    offscreenView = null
  }

  private suspend fun runWarmUp(
    context: Context,
    completion: CompletableDeferred<Result<Boolean>>,
  ) {
    val startedAt = SystemClock.elapsedRealtime()
    val result =
      try {
        withContext(Dispatchers.IO) { PlaybackStartupAssets.prepare(context) }
        val view = createOffscreenView(context)
        offscreenView = view
        Result.success(view.initializeSession(context.filesDir.path, context.cacheDir.path).getOrThrow())
      } catch (cancellation: CancellationException) {
        // Never swallow this one: the deferred still has to be settled so an awaiting caller is not
        // stranded, but the cancellation itself must keep propagating to preserve structured
        // concurrency.
        completion.complete(Result.failure(cancellation))
        throw cancellation
      } catch (error: Throwable) {
        Result.failure(error)
      }
    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
    result
      .onSuccess { Log.i(TAG, "libmpv core prewarmed in $elapsedMs ms") }
      .onFailure { error ->
        Log.w(TAG, "libmpv core prewarm failed after $elapsedMs ms; the open path will initialize", error)
        // Leave nothing half-built behind: the next attempt must start from a clean slate.
        offscreenView?.let { view -> runCatching { view.releaseSurface() } }
        offscreenView = null
        invalidate()
      }
    completion.complete(result)
  }

  /**
   * Builds an [MPVView] that has an AttributeSet but no window.
   *
   * The AttributeSet is read out of a throwaway layout because [MPVView] must be constructed with one
   * — it is declared as non-null coming from the mpv library, which resolves its styled attributes
   * through it. Only the parser is used; the view it describes is discarded and an equivalent one is
   * constructed by hand.
   */
  private fun createOffscreenView(context: Context): MPVView {
    val parser = context.resources.getLayout(R.layout.mpv_core_warmup_holder)
    var type: Int
    while (parser.next().also { type = it } != XmlPullParser.START_TAG &&
      type != XmlPullParser.END_DOCUMENT
    ) {
      // Advance to the first tag.
    }
    val view = MPVView(context, Xml.asAttributeSet(parser))
    // Laid out but never attached: an offscreen view reports a zero-sized surface, which is the
    // signal the rest of the player uses to tell "no window yet" from "surface ready".
    view.layoutParams =
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      )
    return view
  }
}