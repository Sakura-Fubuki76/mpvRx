---
title: Keep recursive checkout when the fork adds native submodule dependencies
author: SakuraFubuki
date: 2026-11-22
branch: master
id: l-ca597f13
scope: repo
status: active
src: agent
touches:
  - ".github/workflows/build.yml"
---

## Decided
Keep recursive submodule checkout in every workflow job that builds this fork's APKs. Upstream can remove that option safely only because it does not contain the fork's native YUV bridge. Workflow provenance must be compared with the fork's build dependencies, not accepted solely because it is current upstream.

In the code: commit 8d8050bc followed upstream checkout structure and dropped submodules: recursive in .github/workflows/build.yml and release.yml. GitHub run 45020967539 failed Standard and FongMi at :app:configureCMakeRelWithDebInfo[arm64-v8a]: CMakeLists.txt:78 adds third_party/libyuv, but checkout left the gitlink directory without CMakeLists.txt. .gitmodules and the tracked libyuv gitlink remain present. Restore recursive checkout for the build/release compilation jobs; do not delete the fork's YUV bridge to match upstream.

## Tried and ruled out
Successful Kotlin/Java compilation, 341 JVM tests and actionlint did not exercise a fresh native checkout or complete release APK build. Signing was skipped after the build failed, so signing secrets and AAB/Play Store publication are not this failure's cause. The workflow option existed in the pre-merge fork and disappeared in the merge; this is a workflow integration regression, not a missing libyuv source commit.

## Watch out
No Vulkan failed independently and earlier, resolving org.gradle.toolchains.foojay-resolver-convention:1.0.0. Standard and FongMi used the same settings and passed plugin resolution, so do not declare that version invalid or change it without download diagnostics. The current log does not expose the underlying HTTP failure. A rerun may resolve the repository/download issue, but it cannot repair the deterministic missing-submodule checkout. This diagnosis does not claim the restored checkout was pushed or that CI has subsequently passed.
