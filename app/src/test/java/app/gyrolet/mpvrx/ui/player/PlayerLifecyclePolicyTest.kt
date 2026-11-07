package app.gyrolet.mpvrx.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlayerLifecyclePolicyTest {
  private val idle = PlaybackSessionState(phase = PlaybackPhase.IDLE, generation = 12L)

  @Test fun unusedIdleCoreMayBeReclaimedWithoutAnEverLoadedFlag() {
    assertTrue(PlayerLifecyclePolicy.canReclaimIdleCore(true, idle, false, false))
  }

  @Test fun onlyIdleCoresCanBeReclaimed() {
    for (phase in PlaybackPhase.entries) {
      assertEquals(phase.name, phase == PlaybackPhase.IDLE,
        PlayerLifecyclePolicy.canReclaimIdleCore(true, idle.copy(phase = phase), false, false))
    }
    assertFalse(PlayerLifecyclePolicy.canReclaimIdleCore(false, idle, false, false))
  }

  @Test fun newActivityClaimCancelsIdleReclamation() {
    val token = PlaybackActivityOwner.claim()
    try {
      assertFalse(PlayerLifecyclePolicy.canReclaimIdleCore(true, idle, false, PlaybackActivityOwner.hasOwner.value))
    } finally {
      PlaybackActivityOwner.release(token)
    }
    assertTrue(PlayerLifecyclePolicy.canReclaimIdleCore(true, idle, false, PlaybackActivityOwner.hasOwner.value))
  }

  @Test fun attachedSurfaceOrPendingMediaBlocksIdleReclamation() {
    assertFalse(PlayerLifecyclePolicy.canReclaimIdleCore(true, idle.copy(surfaceAttached = true), false, false))
    val selected = PlaybackItem(stableId = "selected", originalUri = "/selected.mp4")
    assertFalse(PlayerLifecyclePolicy.canReclaimIdleCore(true, idle.copy(currentItem = selected), false, false))
    assertFalse(PlayerLifecyclePolicy.canReclaimIdleCore(true, idle, true, false))
  }

  @Test fun serviceTeardownStopsOnlyItsDetachedGeneration() {
    assertTrue(PlayerLifecyclePolicy.canStopDetachedPlayback(true, idle, 12L))
    assertFalse(PlayerLifecyclePolicy.canStopDetachedPlayback(true, idle.copy(generation = 13L), 12L))
    assertFalse(PlayerLifecyclePolicy.canStopDetachedPlayback(true, idle.copy(surfaceAttached = true), 12L))
    assertFalse(PlayerLifecyclePolicy.canStopDetachedPlayback(false, idle, 12L))
  }

  @Test fun staleNativeFileEventsAreIgnored() {
    assertFalse(PlayerLifecyclePolicy.shouldDispatchNativeEvent(true, 11L, 12L, false, false))
    assertTrue(PlayerLifecyclePolicy.shouldDispatchNativeEvent(true, 12L, 12L, false, false))
    assertTrue(PlayerLifecyclePolicy.shouldDispatchNativeEvent(true, null, 12L, false, false))
  }

  @Test fun nativeStopAndShutdownCanCompleteAfterAGenerationChange() {
    assertTrue(PlayerLifecyclePolicy.shouldDispatchNativeEvent(true, 11L, 12L, true, false))
    assertTrue(PlayerLifecyclePolicy.shouldDispatchNativeEvent(true, 11L, 12L, false, true))
  }

  @Test fun anOldNativeObserverCannotAffectTheReplacementCore() {
    assertFalse(PlayerLifecyclePolicy.shouldDispatchNativeEvent(false, 12L, 12L, false, false))
    assertFalse(PlayerLifecyclePolicy.shouldDispatchNativeEvent(false, null, 12L, true, true))
  }

  @Test fun transientShutdownErrorsAreRecognizedThroughWrappedCauses() {
    val message = "MPV shutdown is in progress"
    val error = IllegalStateException("initialization failed", RuntimeException(message))
    assertEquals(message, PlayerLifecyclePolicy.shutdownInProgressMessage(error))
    assertTrue(PlayerLifecyclePolicy.isShutdownInProgress("SHUTDOWN IN PROGRESS"))
  }

  @Test fun unrelatedErrorsDoNotTriggerCoreRetry() {
    assertFalse(PlayerLifecyclePolicy.isShutdownInProgress(null))
    assertFalse(PlayerLifecyclePolicy.isShutdownInProgress("Vulkan renderer unavailable"))
    assertFalse(PlayerLifecyclePolicy.isShutdownInProgress("shutdown completed"))
    assertNull(PlayerLifecyclePolicy.shutdownInProgressMessage(IllegalStateException("configuration invalid")))
  }

  @Test fun causeInspectionIsBoundedEvenForCycles() {
    val first = RuntimeException("first")
    val second = RuntimeException("second", first)
    first.initCause(second)
    assertNull(PlayerLifecyclePolicy.shutdownInProgressMessage(first))
  }

  @Test fun deepCausesAreNotFollowedWithoutBound() {
    val shutdown = RuntimeException("shutdown in progress")
    val withinLimit = (1..7).fold(shutdown as Throwable) { cause, _ -> RuntimeException("wrapper", cause) }
    assertEquals("shutdown in progress", PlayerLifecyclePolicy.shutdownInProgressMessage(withinLimit))
    assertNull(PlayerLifecyclePolicy.shutdownInProgressMessage(RuntimeException("one more", withinLimit)))
  }

  @Test fun rotationKeepsPlaybackWithoutEnablingBackgroundMode() {
    assertTrue(PlayerLifecyclePolicy.shouldKeepBackgroundPlaybackAliveOnDestroy(false, false, true))
    assertFalse(PlayerLifecyclePolicy.shouldKeepBackgroundPlaybackAliveOnDestroy(false, false, false))
    assertTrue(PlayerLifecyclePolicy.shouldKeepBackgroundPlaybackAliveOnDestroy(true, true, false))
    assertFalse(PlayerLifecyclePolicy.shouldKeepBackgroundPlaybackAliveOnDestroy(true, false, false))
  }

  @Test fun missingCoreIsInitializedRatherThanAdoptingStaleMedia() {
    assertEquals(PlaybackLaunchPreparation.INITIALIZE_CORE,
      PlayerLifecyclePolicy.launchPreparation(false, PlaybackPhase.READY, true))
    assertEquals(PlaybackLaunchPreparation.INITIALIZE_CORE,
      PlayerLifecyclePolicy.launchPreparation(true, PlaybackPhase.UNINITIALIZED, true))
  }

  @Test fun activeMediaCanBeAttachedOrReplacedWithoutColdInitialization() {
    for (phase in listOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) {
      assertEquals(PlaybackLaunchPreparation.ATTACH_CURRENT_MEDIA,
        PlayerLifecyclePolicy.launchPreparation(true, phase, true))
      assertEquals(PlaybackLaunchPreparation.REPLACE_CURRENT_MEDIA,
        PlayerLifecyclePolicy.launchPreparation(true, phase, false))
    }
  }

  @Test fun idleCoresAreReusedAndStoppingCoresAreAwaited() {
    assertEquals(PlaybackLaunchPreparation.REUSE_CORE,
      PlayerLifecyclePolicy.launchPreparation(true, PlaybackPhase.IDLE, false))
    assertEquals(PlaybackLaunchPreparation.WAIT_FOR_STOP,
      PlayerLifecyclePolicy.launchPreparation(true, PlaybackPhase.STOPPING, true))
  }

  @Test fun configurationChangeIsNotAPipDismissal() {
    assertFalse(PlayerLifecyclePolicy.shouldTreatStopAsPipDismissal(true, true, false, true, false, false))
    assertFalse(PlayerLifecyclePolicy.shouldTreatStopAsPipDismissal(true, true, false, false, true, false))
    assertTrue(PlayerLifecyclePolicy.shouldTreatStopAsPipDismissal(true, true, true, false, false, false))
  }
}
