---
title: Follow upstream core ownership without reviving obsolete speculative startup
author: SakuraFubuki
date: 2026-11-07
branch: master
id: l-094aa544
scope: repo
status: active
src: agent
---

## Decided
Use the current upstream ownership and native-core lifecycle rather than preserving all fork-side startup code, because git history confirms the discarded prewarmer and preloader were old upstream implementations, not user thumbnail customization. Preserve process-wide core reuse, but let only the active Activity initialize it and let the session recheck reclamation under its native lock.

In the code: PlaybackCorePrewarmer came from Ritesh Pandit's 03be92b9 and PlaybackSessionPreloader from 711b7cf6. Neither exists in upstream/master at 49b7cb50. Port c4caa905 and its ef3f8f9e follow-up, remove the old preloader/adoption/hold-paused path and the hasEverLoadedMedia reaper exemption, and retain the data-only PreparedPlaybackLaunchStore.

## Tried and ruled out
Keeping the old speculative startup objects merely to preserve fork-side code was ruled out because the prewarmer has no caller and stage() returns inside synchronized before reaching preload(). Do not restore those paths while adopting the new owner-aware lifecycle. Do not copy the entire upstream PlayerActivity over the fork: PlaybackStartupAssets contains later recursive-font and linked-font-source changes from 3dc6c652 and 88cc0777, which must remain. Keep cloud tracing, DLNA and thumbnail/minimal swipe behavior outside this lifecycle rewrite.

In the code: retain the extracted font-aware PlaybackStartupAssets helper while threading ownerIsActive through PlayerActivity, MPVView and PlaybackSession. Native shutdown snapshots observers and matching loaded position before cleanup, advances generation, and uses a bounded Activity recovery path. Configuration changes are attachment changes, not terminal playback stops.

## Watch out
In the code: evaluate PlayerLifecyclePolicy's reclaim/stop/native-event predicates under nativeLock, immediately before acting. PlaybackActivityOwner.owns must stay lock-free because initialization calls it under nativeLock while ownership-guarded actions can enter nativeLock in the opposite order. Stale Activity release must not clear a newer owner; stale service destruction must not stop a newer generation. Restore metadata, position and subtitles from the confirmed loaded PlaybackItem, not the outgoing Activity Intent.

The 29 new JVM tests cover owner/release behavior, lock-free predicates, native-event guards, reclaim/stop guards and data-only launch queues; 53 seek/preview/DLNA regression tests also pass. These do not validate actual native teardown, PiP, rotation, lock/unlock or rapid reopen on a device. Keep these device checks explicit rather than treating JVM policy tests as native integration coverage.
