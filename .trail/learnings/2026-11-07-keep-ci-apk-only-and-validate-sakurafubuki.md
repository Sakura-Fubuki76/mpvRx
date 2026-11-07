---
title: Keep CI APK-only and validate workflow syntax before releases
author: SakuraFubuki
date: 2026-11-07
branch: master
id: l-6d510d14
scope: repo
status: active
src: agent
touches:
  - ".github/workflows/build.yml"
  - ".github/workflows/preview.yml"
  - ".github/workflows/release.yml"
---

## Decided
This fork publishes APKs only because the maintainer has no Google Play publication setup. Keep Standard, FongMi and No Vulkan CI/release distributions and their APK signing; remove dormant AAB build/sign/symbol stages, not the app's Play Store source flavor.

In the code: .github/workflows/build.yml and release.yml already excluded Play Store from their matrices. The actual Preview failure was invalid YAML: preview.yml's publish checkout lacked `with:` before `submodules: recursive`. Restore that mapping. Validate all three workflows with actionlint before publishing; Gradle unit tests cannot detect workflow syntax errors.

## Tried and ruled out
The old package == 'aab' branches were unreachable, so missing Play credentials did not explain the observed zero-job failure. Do not mistake those leftovers or preview-site files under fastlane/metadata for an active Google Play upload.

## Watch out
Keep all seven required release APK assets and mandatory stable signing intact. Workflow files are not indexed by this Trail graph, so inspect them directly after that scope check. User approval to investigate CI is not approval to resume an interrupted tag push; wait for renewed publication authorization.
