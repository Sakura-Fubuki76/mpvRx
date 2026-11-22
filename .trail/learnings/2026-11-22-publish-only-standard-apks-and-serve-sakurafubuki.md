---
title: Publish only Standard APKs and serve preview updates without Pages
author: SakuraFubuki
date: 2026-11-22
branch: master
id: l-67f8c97e
scope: repo
status: active
replaces: l-6d510d14
src: agent
---

## Decided
The maintainer now wants Standard-only CI/publication with no GitHub Pages, FongMi or No Vulkan jobs. This replaces the older seven-asset/three-distribution requirement, not the APK-only or mandatory release-signing rules. Keep app flavor implementations available for local builds.

In the code: build.yml, release.yml and preview.yml now build five Standard APKs (universal, arm64-v8a, armeabi-v7a, x86, x86_64). Native build checkouts remain recursive for libyuv. Preview publishes a rolling GitHub prerelease named preview and a latest.json asset; UpdateManager reads that asset instead of the Pages URL. scripts/test_ci_workflows.py tests the new policy and manifest compatibility.

## Tried and ruled out
Deleting only the extra matrix entries would leave stale asset checks/release notes and the Preview Pages deployment. Deleting Pages without moving the manifest would break in-app Preview updates. Upload versioned APKs before latest.json and prune old assets only after publication.

## Watch out
Old installed apps still have the Pages URL and need a manual upgrade to switch endpoints. Only the preview tag is mutable; stable tags and releases must not be replaced or marked as previews. The maintainer explicitly changed the old CI policy, so explain that replacement if trail check still reports the predecessor learning until merge.
