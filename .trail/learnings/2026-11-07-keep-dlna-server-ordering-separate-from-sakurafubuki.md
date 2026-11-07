---
title: Keep DLNA server ordering separate from cloud library scans
author: SakuraFubuki
date: 2026-11-07
branch: master
id: l-56034b4f
scope: repo
status: active
src: agent
---

## Decided
Keep DLNA ContentDirectory references separate from the cloud-library pipeline because DLNA paths are opaque object IDs and ordered albums must preserve server order. WebDAV/OpenList retain cache/index search, video-only filtering, forced refresh, anime flags and cloud view modes. DLNA audio ignores the ordinary local-audio toggle.

In the code: NetworkBrowserViewModel.publish bypasses cloud enrichment, directory caching and background scanning for DLNA. NetworkBrowserScreen and networkPlaybackQueue share networkBrowserIncludesAudio. NetworkAddress.parse preserves OPENLIST and DLNA for HTTP(S), with existing DAV-alias behavior retained.

## Tried and ruled out
Do not wholesale replace the browser with upstream because that loses cloud index/cache behavior. Do not feed DLNA through cloud enrichment because that reorders albums and treats object IDs as filenames.

The upstream DlnaXml parser unconditionally sets Xerces security flags, which fails with ParserConfigurationException on Android. Android's DocumentBuilderFactoryImpl only recognizes namespaces and validation. In the code: DlnaXml.parseWithFactory makes those flags optional because the platform does not implement them; mandatory DOCTYPE rejection and a rejecting EntityResolver still prohibit external entities. DlnaXmlTest uses an Android-like factory to cover compatibility and security.

## Watch out
Never rebuild DlnaPath references from display titles. Preserve repeated tracks as separate playlist entries with distinct paths. In the code: DlnaClientTest tests HTTP range continuation and ignored Range headers; NetworkPlaybackQueueTest checks DLNA server order and cloud-index queues. JVM tests do not cover physical-device SSDP discovery.

Android implementation: https://android.googlesource.com/platform/libcore/+/refs/heads/main/luni/src/main/java/org/apache/harmony/xml/parsers/DocumentBuilderFactoryImpl.java
