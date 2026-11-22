---
title: Keep fork database upgrade paths separate from reused upstream versions
author: SakuraFubuki
date: 2026-11-22
branch: master
id: l-a380a50e
scope: repo
status: active
src: agent
touches:
  - scripts/test_database_migrations.py
---

## Decided
Keep the fork's published cloud/anime migration chain intact and apply new upstream playback columns in a new version after it. Database version numbers are branch-local schema identities, not a safe ordering between this fork and upstream.

In the code: the fork's 31→32→33→34→35 migrations create cloud caches, anime associations and technical metadata. Upstream 770bea79 instead uses 31→34, 32→34 and 33→34 for manual audio selection, including recovery from two reverted upstream builds. The integration uses schema 36 and 35→36 to add hasManualAudioSelection, manualAudioId and externalAudio without resetting aid or discarding cloud/anime data. scripts/test_database_migrations.py exercises the actual SQL against SQLite, including preservation of existing version-35 rows.

## Tried and ruled out
Do not choose the larger annotation version and register both branches' migrations. Both define MIGRATION_33_34 with different meanings, and upstream's direct 31→34 edge would skip the fork's tables. Do not run upstream's reverted-build flag-reset logic on a fork version-32 database.

## Watch out
The current path supports the fork's existing 31-35 databases. Direct upgrades from standalone upstream 32/33/34 databases would require explicit table/column-based schema recognition; the same numeric version is not enough. The Python checks validate migration SQL and data preservation, not a full Android Room schema validation or an installed-device upgrade.
