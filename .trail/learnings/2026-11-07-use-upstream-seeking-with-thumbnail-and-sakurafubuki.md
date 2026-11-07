---
title: Use upstream seeking with thumbnail and minimal swipe overlays
author: SakuraFubuki
date: 2026-11-07
branch: master
id: l-e9cdf661
scope: repo
status: active
src: agent
---

## Decided
The user clarified that code on the fork side of a merge may be an older upstream implementation, not their customization. Prefer the new upstream seek behavior because preserving every old seek guard/coalescer would retain the problems upstream intentionally removed. For this integration, the requested custom behavior is the thumbnail preview and a minimal screen-swipe overlay, not a separate native seek engine.

In the code: port upstream 741f09bb and 66d1a4d6 plus the current Seekbar discontinuity handling. PlayerViewModel uses immediate seek commands with upstream's 0.5-second duplicate suppression, and horizontal release does not send another native seek. AudioPlayerControls and MPVPipHelper use the same new policy. The existing preload/hold-paused lifecycle behavior was not changed because that is a separate pending integration.

## Tried and ruled out
Do not retain the former seekPreviewTo loop, pause/unpause sequence, seek-specific mute guard, or a second scrubPreviewPosition StateFlow just because they were on the local side. They duplicate the new upstream flow. In the code: PlayerControls derives the horizontal thumbnail target directly from precisePosition; SeekbarContent still uses the user's live thumb during direct bar dragging. The existing CloudSeekPreview and SeekPreviewPlacement rendering stays intact.

## Watch out
In the code: fullControlsVisible and bufferingFeedbackEnabled suppress full controls, center seek feedback and the spinner during a horizontal swipe, because the user wants only the timeline and thumbnail in that mode. Existing orientation/preference/lock rules remain in seekTimelineVisible; portrait swipe remains without the timeline as before. GestureHandler must release ownership and clear the temporary timeline on cancellation without repeating a seek or changing the pre-existing pause state. ScrubSeekPolicyTest, SeekbarPositionPolicyTest, SeekOverlayPolicyTest and SeekPreviewPlacementTest cover the pure policy/placement rules, not physical-device gestures.
