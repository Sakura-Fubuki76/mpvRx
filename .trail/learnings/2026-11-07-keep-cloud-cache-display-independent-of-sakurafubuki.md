---
title: Keep cloud cache display independent of revalidation age
author: SakuraFubuki
date: 2026-11-07
branch: master
id: l-2867bef5
scope: repo
status: active
src: agent
---

## Decided
Separate cloud cache availability from revalidation because an unchanged library should not lose visible counts or paths merely with age. The persisted directory and folder tables are not an LRU. The concrete age-related symptom came from the 24-hour display gate, which marked stored complete summaries incomplete, and NetworkFolderCard suppressed their values. Keep the 30-minute revalidation interval and explicit refresh; display the last successfully completed snapshot until observed listing changes invalidate it. On directory refresh failure, retain the cached listing and report the error.

In the code: CloudDirectoryCachePolicy.withCloudFolderSummary now supplies the same time-independent snapshot to CloudMetadataRepository.enrichVideos, observeLibraryData and NetworkBrowserViewModel. CloudMetadataDao.putScannedFolder retains the previous row on a partial scan, including completeness and timestamp. Its replaceDirectory transaction already invalidates ancestor completeness and timestamps on actual listing changes, so an interrupted scan must not restore an invalidated row. Cache tests cover days-old snapshots, descendant failures, successful empty scans, and known-change invalidation. These policies predate the November upstream merge; do not attribute this symptom to upstream solely because it was investigated afterward.

## Tried and ruled out
Do not enlarge an LRU or make directory freshness infinite: these directory snapshots live in Room, not the bounded thumbnail/container caches, and suppressing validation would hide real cloud changes. Retaining only numeric counts was insufficient because the UI still hid them when scanComplete became false after a transient partial failure. Renewing updatedAt on failure was also wrong because it could postpone the next useful validation.

## Watch out
A complete last-known snapshot is not proof the remote has not changed since validation. Continue background enumeration and explicit forceRefresh, and expose listing errors instead of treating them as successful emptiness. Only a successful empty listing/complete scan may establish an empty directory. Keep cancellation propagation and DLNA's separate path/order behavior. No live OpenList device/session was used to prove the user's exact network-failure sequence.
