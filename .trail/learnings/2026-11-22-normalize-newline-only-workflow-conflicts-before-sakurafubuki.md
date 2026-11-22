---
title: Normalize newline-only workflow conflicts before judging fork customizations
author: SakuraFubuki
date: 2026-11-22
branch: master
id: l-472b4519
scope: repo
status: active
src: agent
touches:
  - ".github/workflows/build.yml"
---

## Decided
Before treating a whole-workflow merge conflict as a conflict with the fork's features, compare LF-normalized copies of the three index stages with git merge-file --diff3. This is a dry run, not authorization to replace the worktree or pick a side. Preserve meaningful fork publishing changes while following the current upstream workflow design.

In the code: f24d4cbc introduced the fork-derived Pages URLs but also rewrote .github/workflows/build.yml and preview.yml with CRLF. During the merge of upstream 770bea79 with HEAD f24d4cbc, Git reported each as a whole-file conflict. The ours copies each had 427 CRLF lines; base and upstream were LF. LF-normalized preview.yml merged with exit 0, while normalized build.yml exposed only three localized conflicts. The Preview fork-URL edits and upstream Preview edits were compatible.

## Tried and ruled out
Reading the original full-file conflict or attributing all lines to the latest local blame author exaggerates the amount of user customization. Do not accept the whole upstream Preview file merely because it clears the markers: that would discard the fork-derived manifest and Pages URLs. Do not keep all of the old workflow merely because its newest commit was local: it would preserve retired upstream Experimental publishing and can undo the APK-only policy.

## Watch out
Keep git index stages intact until the maintainer decides the substantive conflicts. Inspect normalized output for meaning and workflow syntax, and keep the output outside the source tree until resolution is authorized. This technique removes newline noise only; build/release APK-only versus upstream Play Store/AAB publishing still requires deliberate integration, and a clean merge is not an actionlint or release validation result.
