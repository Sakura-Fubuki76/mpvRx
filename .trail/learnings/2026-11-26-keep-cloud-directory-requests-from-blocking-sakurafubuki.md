---
title: Keep cloud directory requests from blocking navigation behind background scans
author: SakuraFubuki
date: 2026-11-26
branch: master
id: l-0de93468
scope: repo
status: active
src: agent
---

## Decided
Allow established WebDAV/OpenList directory HTTP requests to run outside the repository lifecycle mutex. Foreground navigation and the persistent whole-storage scan previously held the same global mutex for the entire listing; one slow directory could therefore delay another path even after the page's job was cancelled. Keep setup/status changes locked and reject results if the active client was replaced. Keep stateful FTP/SFTP/SMB operations serialized.

In the code: NetworkRepository.listFiles and finishListing; NetworkDirectoryConcurrencyTest reproduces a slow non-cancellable listing, navigation, disconnect/reconnect, and stateful protocol serialization.

## Tried and ruled out
Do not change cache lifetime or disable whole-tree scanning to address this wait. Cached directories already display independently of freshness, while an unvisited directory still needs its own request. A per-connection mutex alone would retain contention between browsing and scanning the same storage.

## Watch out
This isolates listing waits; initial connect/probe still uses the lifecycle lock, and synchronous DAV socket cancellation is not changed. No live user server was used to attribute all observed latency to this mechanism. Preserve cached-display/revalidation policy and guard late results/status against client replacement.
