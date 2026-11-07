---
title: Resolve upstream conflicts by provenance and preserve narrow custom hooks
author: SakuraFubuki
date: 2026-11-07
branch: master
id: l-95a011da
scope: repo
status: active
src: agent
---

## Decided
Resolve this rewritten-upstream merge by commit provenance, because the fork side contains both user features and abandoned upstream implementations. Use current upstream behavior as the base and carry narrow custom hooks rather than choosing a whole side.

In the code: VideoCard's independent anime fields originate in a1aac8ac, tree/cloud controls in cc432a08, RemoteImage's logo fade in 1f66027a, and network playback identities in 345b9287. Keep these alongside upstream grayscale, thumbnail reveal, ZIP artwork and recent-playback changes. AppToast and AppToastHost differences are older upstream implementations, so use the current upstream versions.

## Tried and ruled out
Do not copy upstream PlayerActivity's asset/font helpers wholesale because that would undo the fork's recursive and linked-font work. Port the script inventory/module-cache fixes into PlaybackStartupAssets instead. Do not replace SubtitleOps wholesale: its font preparation and cloud tracing are custom. Use upstream bounded SubtitleDiscovery, retaining the custom anime matcher only when explicit episode numbers agree, so it cannot override upstream sequel and season rejection.

## Watch out
In the code: merging a previously extracted helper can produce duplicate subtitle jobs/imports or stale variable references even without a semantic conflict. Preserve the earlier DLNA XML compatibility fix, network queue policy, native-owner guards and minimal thumbnail swipe overlays. PlayerControls must use the upstream playback row with the already-masked controlsShown state. Regression-test discovery through its public find path, not only the standalone name matcher. JVM/compile checks are not device gesture or native playback validation.
