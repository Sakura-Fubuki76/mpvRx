---
title: Retire generated fallback-font configuration through the shared startup path
author: SakuraFubuki
date: 2026-11-25
branch: master
id: l-2aaaf1d6
scope: repo
status: active
src: agent
touches:
  - app/src/main/java/app/gyrolet/mpvrx/ui/player/PlaybackStartupAssets.kt
  - app/src/main/java/app/gyrolet/mpvrx/utils/storage/TreeViewScanner.kt
---

## Decided
When upstream retires its fallback-font downloader, remove the obsolete generated font configuration through the fork's shared startup helper before its bundled-assets cache can return early. Keep the font-bank pipeline. The old app-owned fonts.conf otherwise prevents mpvlib from generating its replacement, while an unmarked file can belong to the user.

In the code: PlaybackStartupAssets.prepare and syncBundledAssetsIfNeeded own startup preparation in this fork. Upstream 2ab65372 deleted MpvFontConfig and introduced removeLegacySubtitleFallbackFonts in PlayerActivity. Carry the marker-gated cleanup into PlaybackStartupAssets, removing its obsolete MpvFontConfig.ensure call.

## Tried and ruled out
Choosing the fork's PlayerActivity conflict block alone leaves a reference to a deleted class in an automatically untouched helper. Copying the upstream Activity helpers back would duplicate the fork's resource preparation. Clearing every fonts.conf would erase user-owned configuration.

## Watch out
A clean conflict index does not prove cross-file compatibility. This merge also needed buildChildrenIndex(merged) passed to markFlattenedFolders when withExtraFolders was added, because the fork caches child indexes and requires that third argument. The missing argument surfaced in Kotlin compilation.
