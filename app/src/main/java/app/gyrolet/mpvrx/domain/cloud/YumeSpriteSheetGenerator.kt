package app.gyrolet.mpvrx.domain.cloud

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.gyrolet.mpvrx.domain.cloud.SpriteMigrationLog as Logger
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class YumeSpriteSheetGenerator(
    private val sharedOkHttpClient: OkHttpClient,
    private val thumbnailMaxDimension: Int = 320,
) {

    companion object {
        private const val TAG = "SpriteSheetGenerator"

        const val THUMB_WIDTH = 160

        const val THUMB_HEIGHT = 90

        const val COLS = 10

        const val ROWS = 10

        const val FRAME_COUNT = COLS * ROWS

        private const val REMOTE_MAX_FRAMES = 100

        private const val WEBP_QUALITY = 80

        private const val DECODE_TIMEOUT_MS = 3000L

        private const val MAX_CONCURRENT_DOWNLOADS = 8

        private const val FRAME_CANDIDATE_RADIUS = 2

        private const val MAX_DECODER_NAME_CACHE_ENTRIES = 16
    }

    private val decoderNameCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(MAX_DECODER_NAME_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_DECODER_NAME_CACHE_ENTRIES
        },
    )

    private var nativeSolidColorCheckAvailable = true

    private val generationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val inFlightGenerations = ConcurrentHashMap<String, Deferred<YumeSpriteResult?>>()

    suspend fun generate(
        source: String,
        httpHeaders: Map<String, String>? = null,
        durationMs: Long,
        cacheDir: File,
        cacheKey: String,
        context: Context? = null,
        onGenerationFinished: () -> Unit = {},
        sourceExtension: String? = null,
    ): YumeSpriteResult? {
        val inFlightKey = "${cacheDir.absolutePath}|$cacheKey"
        var created = false
        val deferred = inFlightGenerations[inFlightKey] ?: synchronized(inFlightGenerations) {
            inFlightGenerations[inFlightKey] ?: generationScope.async {
                doGenerate(
                    source = source,
                    httpHeaders = httpHeaders,
                    durationMs = durationMs,
                    cacheDir = cacheDir,
                    cacheKey = cacheKey,
                    context = context?.applicationContext,
                    sourceExtension = sourceExtension,
                )
            }.also {
                inFlightGenerations[inFlightKey] = it
                created = true
            }
        }

        if (!created) {
            Logger.d(TAG, "Join in-flight sprite sheet generation for $cacheKey")
        }

        return try {
            deferred.await()
        } finally {
            deferred.invokeOnCompletion { onGenerationFinished() }
            if (deferred.isCompleted) {
                inFlightGenerations.remove(inFlightKey, deferred)
            }
        }
    }

    private suspend fun doGenerate(
        source: String,
        httpHeaders: Map<String, String>? = null,
        durationMs: Long,
        cacheDir: File,
        cacheKey: String,
        context: Context? = null,
        sourceExtension: String? = null,
    ): YumeSpriteResult? = withContext(Dispatchers.IO) {
        try {
            cacheDir.mkdirs()
            val spriteFile = File(cacheDir, "$cacheKey.webp")
            val metaFile = File(cacheDir, "$cacheKey.json")
            val extension = sourceExtension ?: source.substringBefore('?').substringAfterLast('.')
            val fragmented = context != null && source.startsWith("http") && extension.lowercase() in setOf("mp4", "mov", "m4v") &&
                FragmentedMp4Support.inspect(source, cacheKey, context)?.fragmented == true

            if (spriteFile.exists() && spriteFile.length() > 0 && metaFile.exists()) {
                val meta = readMetadata(metaFile)
                if (meta != null && meta.durationMs == durationMs && meta.isComplete() && (!fragmented || meta.fragmentIndexVersion >= 1)) {
                    CloudTrace.event("sprite.cache.hit", detail = "frames=${meta.frameCount}")
                    return@withContext YumeSpriteResult(spriteFile, meta)
                }
            }

            CloudTrace.event("sprite.cache.miss")
            Logger.d(TAG, "Generating sprite sheet for $source, duration=${durationMs}ms")

            val isRemote = source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true)

            val isContentUri = source.startsWith("content://", ignoreCase = true)

            val sourcePath = sourceExtension?.let { "video.$it" } ?: source.substringBefore('?')
            val isMp4 = sourcePath.endsWith(".mp4", ignoreCase = true) || sourcePath.endsWith(".m4v", ignoreCase = true) ||
                sourcePath.endsWith(".mov", ignoreCase = true)
            val isMkv = sourcePath.endsWith(".mkv", ignoreCase = true) ||
                sourcePath.endsWith(".webm", ignoreCase = true)

            if (isMp4 && (isRemote || !isContentUri)) {
                generateFromMoov(source, httpHeaders, durationMs, cacheDir, cacheKey)
            } else if (isRemote && isMkv) {
                generateFromMkv(source, httpHeaders, durationMs, cacheDir, cacheKey, context)
            } else {
                generateFromMediaExtractor(source, durationMs, spriteFile, metaFile, cacheDir, cacheKey, context)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "Sprite sheet generation failed", e)
            null
        }
    }

    private suspend fun generateFromMoov(
        source: String,
        httpHeaders: Map<String, String>?,
        durationMs: Long,
        cacheDir: File,
        cacheKey: String,
    ): YumeSpriteResult? {
        val isLocal = !source.startsWith("http://", ignoreCase = true) &&
            !source.startsWith("https://", ignoreCase = true)

        val spriteFile = File(cacheDir, "$cacheKey.webp")
        val metaFile = File(cacheDir, "$cacheKey.json")

        Logger.d("BUG4_SpriteSheet", "generateFromMoov: isLocal=$isLocal source=${source.take(100)} httpHeaders=$httpHeaders")
        val okHttpClient = buildRequestClient(httpHeaders.takeUnless { isLocal })
        val mp4Extractor = Mp4KeyframeExtractor(okHttpClient)

        val parsed = if (isLocal) {
            mp4Extractor.loadParsedMoovFromFile(source)
        } else {
            mp4Extractor.loadParsedMoov(source, stableKey = cacheKey, includeFragments = true)
        }
        if (parsed == null) {
            Logger.w("BUG4_SpriteSheet", "parsed is null for ${source.take(100)}, falling back to MediaExtractor")
            Logger.w(TAG, "Failed to parse moov for $source, falling back to MediaExtractor")
            return generateFromMediaExtractor(source, durationMs, spriteFile, metaFile, cacheDir, cacheKey)
        }

        val moovInfo = parsed.moovInfo
        Logger.d("BUG4_SpriteSheet", "parsed OK: moovInfo=${moovInfo != null} keyframes=${moovInfo?.keyframes?.size ?: 0} durationMs=${parsed.durationMs}")
        if (moovInfo == null || moovInfo.keyframes.isEmpty()) {
            Logger.w("BUG4_SpriteSheet", "No keyframes in moov for ${source.take(100)}, falling back to MediaExtractor")
            Logger.w(TAG, "No keyframe index in moov for $source, falling back to MediaExtractor")
            return generateFromMediaExtractor(source, durationMs, spriteFile, metaFile, cacheDir, cacheKey)
        }

        val allKeyframes = moovInfo.keyframes
        var displayW = moovInfo.width
        var displayH = moovInfo.height
        val needsRotation = moovInfo.rotation == 90 || moovInfo.rotation == 270
        if (needsRotation) {
            displayW = moovInfo.height
            displayH = moovInfo.width
        }
        val (thumbW, thumbH) = computeThumbDimensions(displayW, displayH)

        val (scaleW, scaleH) = computeThumbDimensions(moovInfo.width, moovInfo.height)
        Logger.d(
            TAG,
            "Moov parsed: ${allKeyframes.size} keyframes, codec=${moovInfo.codecType}, " +
                "video=${moovInfo.width}x${moovInfo.height} rotation=${moovInfo.rotation}° thumb=${thumbW}x$thumbH " +
                "scale=${scaleW}x$scaleH, local=$isLocal",
        )

        val startTime = System.currentTimeMillis()

        val maxFrames = if (isLocal) FRAME_COUNT else REMOTE_MAX_FRAMES
        val actualFrameCount = if (durationMs < 10000) {
            ((durationMs / 100).toInt()).coerceIn(10, maxFrames)
        } else {
            maxFrames
        }

        val gridCols = findBestGrid(actualFrameCount).first
        val gridRows = findBestGrid(actualFrameCount).second
        val intervalMs = durationMs.toDouble() / actualFrameCount
        var metadata = YumeSpriteMetadata(
            cols = gridCols,
            rows = gridRows,
            frameCount = actualFrameCount,
            thumbWidth = thumbW,
            thumbHeight = thumbH,
            intervalMs = intervalMs,
            durationMs = durationMs,
            fragmentIndexVersion = if (parsed.videoTrackNumber > 0) 1 else 0,
        )

        val spriteSheet = Bitmap.createBitmap(
            gridCols * thumbW,
            gridRows * thumbH,
            Bitmap.Config.ARGB_8888,
        )

        val targets = selectFrameTargets(allKeyframes, actualFrameCount, intervalMs)
        val pendingGridIndices = targets.mapTo(LinkedHashSet<Int>()) { it.gridIndex }
        var framesComposited = 0
        var bytesDownloaded = 0L
        var decodedFrames = 0
        val readStart = System.currentTimeMillis()
        val decodeStart = System.currentTimeMillis()

        for (round in 0..FRAME_CANDIDATE_RADIUS) {
            val pendingTargets = targets.filter { it.gridIndex in pendingGridIndices }
            if (pendingTargets.isEmpty()) break

            val allowFlatFallback = round == FRAME_CANDIDATE_RADIUS
            val uniqueTargets = selectUniqueFrameTargetsForRound(
                keyframes = allKeyframes,
                targets = pendingTargets,
                round = round,
                allowFlatFallback = allowFlatFallback,
            )
            Logger.d(
                TAG,
                "Round $round selected ${uniqueTargets.size} unique candidates " +
                    "for ${pendingTargets.size} pending cells",
            )

            var roundNativeSolidSkipped = 0
            var roundBitmapSolidSkipped = 0
            // 边下边解码：每批下载完立即解码合成并释放 ByteArray，
            // 避免 100 帧数据同时驻留内存造成 OOM。
            for (batch in uniqueTargets.chunked(MAX_CONCURRENT_DOWNLOADS)) {
                val pairedData = coroutineScope {
                    batch.map { target ->
                        async {
                            val data = if (isLocal) {
                                mp4Extractor.readFileRange(source, target.kf.byteOffset, target.kf.byteSize)
                            } else {
                                mp4Extractor.httpRange(source, target.kf.byteOffset, target.kf.byteSize)
                            }

                            target to data
                        }
                    }.awaitAll()
                }
                bytesDownloaded += pairedData.sumOf { it.second?.size?.toLong() ?: 0L }

                val successEntries = pairedData.mapNotNull { (target, data) ->
                    data?.let { target to it }
                }
                decodedFrames += successEntries.size
                val batchResult = decodeAndCompositeCandidates(
                    mp4Extractor = mp4Extractor,
                    moovInfo = moovInfo,
                    successEntries = successEntries,
                    spriteSheet = spriteSheet,
                    scaleW = scaleW,
                    scaleH = scaleH,
                    thumbW = thumbW,
                    thumbH = thumbH,
                    gridCols = gridCols,
                    needsRotation = needsRotation,
                )
                framesComposited += batchResult.compositedGridIndices.size
                pendingGridIndices.removeAll(batchResult.compositedGridIndices)
                roundNativeSolidSkipped += batchResult.nativeSolidSkippedCells
                roundBitmapSolidSkipped += batchResult.bitmapSolidSkippedCells
            }
            Logger.d(
                TAG,
                "Round $round result: decoded=$decodedFrames cumulative, " +
                    "nativeSolidSkipped=$roundNativeSolidSkipped, " +
                    "bitmapSolidSkipped=$roundBitmapSolidSkipped, " +
                    "composited=$framesComposited cumulative",
            )
        }

        Logger.d(TAG, "Read ${bytesDownloaded / 1024}KB in ${System.currentTimeMillis() - readStart}ms")
        Logger.d(
            TAG,
            "Decoded $decodedFrames unique candidates, composited " +
                "$framesComposited/${targets.size} cells in ${System.currentTimeMillis() - decodeStart}ms",
        )
        Logger.d(
            "BUG4_SpriteSheet",
            "Decoded $decodedFrames unique candidates, composited " +
                "$framesComposited/${targets.size}",
        )

        if (framesComposited == 0) {
            spriteSheet.recycle()
            Logger.w("BUG4_SpriteSheet", "No frames downloaded for ${source.take(100)}")
            Logger.w(TAG, "No frames downloaded for $source")
            return null
        }

        val saveStart = System.currentTimeMillis()
        FileOutputStream(spriteFile).use { out ->
            spriteSheet.compress((if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP), WEBP_QUALITY, out)
        }
        spriteSheet.recycle()

        metadata = metadata.copy(validCells = targets.map { it.gridIndex }.filter { it !in pendingGridIndices })
        metadata.saveTo(metaFile)
        CloudTrace.event("sprite.saved", detail = "frames=${metadata.frameCount} bytes=${spriteFile.length()}")

        val totalElapsed = System.currentTimeMillis() - startTime
        Logger.d(
            TAG,
            "Saved ${spriteFile.length() / 1024}KB in ${System.currentTimeMillis() - saveStart}ms. " +
                "Total: ${totalElapsed}ms, $framesComposited frames, ${bytesDownloaded / 1024}KB downloaded",
        )
        Logger.d("BUG4_SpriteSheet", "SUCCESS: ${spriteFile.length() / 1024}KB sprite sheet in ${totalElapsed}ms")
        return YumeSpriteResult(spriteFile, metadata)
    }

    private suspend fun generateFromMkv(
        source: String,
        httpHeaders: Map<String, String>?,
        durationMs: Long,
        cacheDir: File,
        cacheKey: String,
        context: Context? = null,
    ): YumeSpriteResult? {
        val spriteFile = File(cacheDir, "$cacheKey.webp")
        val metaFile = File(cacheDir, "$cacheKey.json")

        Logger.d("BUG4_SpriteSheet", "generateFromMkv: source=${source.take(100)}")
        val okHttpClient = buildRequestClient(httpHeaders)
        val mkvExtractor = MkvKeyframeExtractor(okHttpClient)
        val mp4Extractor = Mp4KeyframeExtractor(okHttpClient)

        val parsed = mkvExtractor.loadParsedMkv(source)
        if (parsed == null) {
            Logger.w("BUG4_SpriteSheet", "MKV parsed is null for ${source.take(100)}, falling back to MediaExtractor")
            Logger.w(TAG, "Failed to parse MKV for $source, falling back to MediaExtractor")
            return generateFromMediaExtractor(source, durationMs, spriteFile, metaFile, cacheDir, cacheKey, context)
        }

        val moovInfo = parsed.moovInfo
        Logger.d("BUG4_SpriteSheet", "MKV parsed OK: moovInfo=${moovInfo != null} keyframes=${moovInfo?.keyframes?.size ?: 0}")
        if (moovInfo == null || moovInfo.keyframes.isEmpty()) {
            Logger.w("BUG4_SpriteSheet", "No keyframes in MKV for ${source.take(100)}, falling back to MediaExtractor")
            Logger.w(TAG, "No keyframe index in MKV for $source, falling back to MediaExtractor")
            return generateFromMediaExtractor(source, durationMs, spriteFile, metaFile, cacheDir, cacheKey, context)
        }

        val allKeyframes = moovInfo.keyframes
        var displayW = moovInfo.width
        var displayH = moovInfo.height
        val needsRotation = moovInfo.rotation == 90 || moovInfo.rotation == 270
        if (needsRotation) {
            displayW = moovInfo.height
            displayH = moovInfo.width
        }
        val (thumbW, thumbH) = computeThumbDimensions(displayW, displayH)
        val (scaleW, scaleH) = computeThumbDimensions(moovInfo.width, moovInfo.height)
        Logger.d(
            TAG,
            "MKV parsed: ${allKeyframes.size} keyframes, codec=${moovInfo.codecType}, " +
                "video=${moovInfo.width}x${moovInfo.height} thumb=${thumbW}x$thumbH scale=${scaleW}x$scaleH",
        )

        val startTime = System.currentTimeMillis()

        val maxFrames = REMOTE_MAX_FRAMES
        val actualFrameCount = if (durationMs < 10000) {
            ((durationMs / 100).toInt()).coerceIn(10, maxFrames)
        } else {
            maxFrames
        }

        val gridCols = findBestGrid(actualFrameCount).first
        val gridRows = findBestGrid(actualFrameCount).second
        val intervalMs = durationMs.toDouble() / actualFrameCount
        var metadata = YumeSpriteMetadata(
            cols = gridCols,
            rows = gridRows,
            frameCount = actualFrameCount,
            thumbWidth = thumbW,
            thumbHeight = thumbH,
            intervalMs = intervalMs,
            durationMs = durationMs,
        )

        val spriteSheet = Bitmap.createBitmap(
            gridCols * thumbW,
            gridRows * thumbH,
            Bitmap.Config.ARGB_8888,
        )

        val targets = selectFrameTargets(allKeyframes, actualFrameCount, intervalMs)
        val pendingGridIndices = targets.mapTo(LinkedHashSet<Int>()) { it.gridIndex }
        var framesComposited = 0
        var bytesDownloaded = 0L
        var decodedFrames = 0
        val trackNumber = parsed.videoTrackNumber
        val readStart = System.currentTimeMillis()
        val decodeStart = System.currentTimeMillis()

        for (round in 0..FRAME_CANDIDATE_RADIUS) {
            val pendingTargets = targets.filter { it.gridIndex in pendingGridIndices }
            if (pendingTargets.isEmpty()) break

            val allowFlatFallback = round == FRAME_CANDIDATE_RADIUS
            val uniqueTargets = selectUniqueFrameTargetsForRound(
                keyframes = allKeyframes,
                targets = pendingTargets,
                round = round,
                allowFlatFallback = allowFlatFallback,
            )
            Logger.d(
                TAG,
                "MKV round $round selected ${uniqueTargets.size} unique candidates " +
                    "for ${pendingTargets.size} pending cells",
            )

            var roundNativeSolidSkipped = 0
            var roundBitmapSolidSkipped = 0
            // 边下边解码：每批下载完立即解码合成并释放 ByteArray，
            // 避免 100 帧数据同时驻留内存造成 OOM。
            for (batch in uniqueTargets.chunked(MAX_CONCURRENT_DOWNLOADS)) {
                val pairedData = coroutineScope {
                    batch.map { target ->
                        async {
                            val data = mkvExtractor.downloadMkvKeyframe(
                                source,
                                target.kf.byteOffset,
                                target.kf.byteSize,
                                trackNumber,
                            )
                            target to data
                        }
                    }.awaitAll()
                }
                bytesDownloaded += pairedData.sumOf { it.second?.size?.toLong() ?: 0L }

                val successEntries = pairedData.mapNotNull { (target, data) ->
                    data?.let { target to it }
                }
                decodedFrames += successEntries.size
                val batchResult = decodeAndCompositeCandidates(
                    mp4Extractor = mp4Extractor,
                    moovInfo = moovInfo,
                    successEntries = successEntries,
                    spriteSheet = spriteSheet,
                    scaleW = scaleW,
                    scaleH = scaleH,
                    thumbW = thumbW,
                    thumbH = thumbH,
                    gridCols = gridCols,
                    needsRotation = needsRotation,
                )
                framesComposited += batchResult.compositedGridIndices.size
                pendingGridIndices.removeAll(batchResult.compositedGridIndices)
                roundNativeSolidSkipped += batchResult.nativeSolidSkippedCells
                roundBitmapSolidSkipped += batchResult.bitmapSolidSkippedCells
            }
            Logger.d(
                TAG,
                "MKV round $round result: decoded=$decodedFrames cumulative, " +
                    "nativeSolidSkipped=$roundNativeSolidSkipped, " +
                    "bitmapSolidSkipped=$roundBitmapSolidSkipped, " +
                    "composited=$framesComposited cumulative",
            )
        }

        Logger.d(TAG, "MKV read ${bytesDownloaded / 1024}KB in ${System.currentTimeMillis() - readStart}ms")
        Logger.d(
            TAG,
            "MKV decoded $decodedFrames unique candidates, composited " +
                "$framesComposited/${targets.size} cells in ${System.currentTimeMillis() - decodeStart}ms",
        )
        Logger.d(
            "BUG4_SpriteSheet",
            "MKV decoded $decodedFrames unique candidates, composited " +
                "$framesComposited/${targets.size}",
        )

        if (framesComposited == 0) {
            spriteSheet.recycle()
            Logger.w("BUG4_SpriteSheet", "MKV no frames downloaded for ${source.take(100)}")
            Logger.w(TAG, "MKV no frames for $source")
            return null
        }

        val saveStart = System.currentTimeMillis()
        FileOutputStream(spriteFile).use { out ->
            spriteSheet.compress((if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP), WEBP_QUALITY, out)
        }
        spriteSheet.recycle()

        metadata = metadata.copy(validCells = targets.map { it.gridIndex }.filter { it !in pendingGridIndices })
        metadata.saveTo(metaFile)
        CloudTrace.event("sprite.saved", detail = "frames=${metadata.frameCount} bytes=${spriteFile.length()}")

        val totalElapsed = System.currentTimeMillis() - startTime
        Logger.d(
            TAG,
            "MKV saved ${spriteFile.length() / 1024}KB in ${System.currentTimeMillis() - saveStart}ms. " +
                "Total: ${totalElapsed}ms, $framesComposited frames, ${bytesDownloaded / 1024}KB downloaded",
        )
        Logger.d("BUG4_SpriteSheet", "MKV SUCCESS: ${spriteFile.length() / 1024}KB sprite sheet in ${totalElapsed}ms")
        return YumeSpriteResult(spriteFile, metadata)
    }

    private fun selectFrameTargets(
        keyframes: List<Mp4KeyframeExtractor.KeyframeEntry>,
        frameCount: Int,
        intervalMs: Double,
    ): List<FrameTarget> {
        if (keyframes.isEmpty()) return emptyList()
        var searchStartIndex = 0
        return (0 until frameCount).map { gridIndex ->
            val targetTimeMs = (gridIndex * intervalMs).toLong()
            val keyframeIndex = findNearestKeyframeIndex(
                keyframes = keyframes,
                targetTimeMs = targetTimeMs,
                startIndex = searchStartIndex,
            )
            searchStartIndex = keyframeIndex
            FrameTarget(
                gridIndex = gridIndex,
                targetTimeMs = targetTimeMs,
                keyframeIndex = keyframeIndex,
            )
        }
    }

    private fun selectUniqueFrameTargetsForRound(
        keyframes: List<Mp4KeyframeExtractor.KeyframeEntry>,
        targets: List<FrameTarget>,
        round: Int,
        allowFlatFallback: Boolean,
    ): List<UniqueFrameTarget> = targets.flatMap { target ->
        candidateIndicesForRound(keyframes, target, round).map { candidateIndex ->
            keyframes[candidateIndex] to CandidateAssignment(
                gridIndex = target.gridIndex,
                allowFlatFallback = allowFlatFallback,
            )
        }
    }.groupBy { (kf, _) -> kf.byteOffset to kf.byteSize }
        .values
        .map { grouped ->
            UniqueFrameTarget(
                assignments = grouped.map { it.second }.distinctBy { it.gridIndex },
                kf = grouped.first().first,
            )
        }

    private fun candidateIndicesForRound(
        keyframes: List<Mp4KeyframeExtractor.KeyframeEntry>,
        target: FrameTarget,
        round: Int,
    ): List<Int> {
        if (round == 0) return listOf(target.keyframeIndex)

        val indices = buildList {
            val previous = target.keyframeIndex - round
            val next = target.keyframeIndex + round
            if (previous >= 0) add(previous)
            if (next <= keyframes.lastIndex) add(next)
        }
        return indices.sortedBy { kotlin.math.abs(keyframes[it].timeMs - target.targetTimeMs) }
    }

    private fun findNearestKeyframeIndex(
        keyframes: List<Mp4KeyframeExtractor.KeyframeEntry>,
        targetTimeMs: Long,
        startIndex: Int,
    ): Int {
        var low = startIndex.coerceIn(0, keyframes.lastIndex)
        var high = keyframes.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (keyframes[mid].timeMs < targetTimeMs) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        val nextIndex = low
        val previousIndex = (low - 1).coerceAtLeast(0)
        val previousDelta = kotlin.math.abs(keyframes[previousIndex].timeMs - targetTimeMs)
        val nextDelta = kotlin.math.abs(keyframes[nextIndex].timeMs - targetTimeMs)
        return if (previousDelta <= nextDelta) previousIndex else nextIndex
    }

    private fun decodeAndCompositeCandidates(
        mp4Extractor: Mp4KeyframeExtractor,
        moovInfo: Mp4KeyframeExtractor.MoovInfo,
        successEntries: List<Pair<UniqueFrameTarget, ByteArray>>,
        spriteSheet: Bitmap,
        scaleW: Int,
        scaleH: Int,
        thumbW: Int,
        thumbH: Int,
        gridCols: Int,
        needsRotation: Boolean,
    ): SpriteDecodeBatchResult {
        if (successEntries.isEmpty()) return SpriteDecodeBatchResult()

        val compositedGridIndices = LinkedHashSet<Int>()
        var nativeSolidSkippedCells = 0
        var bitmapSolidSkippedCells = 0
        val successDataOnly = successEntries.map { it.second }
        mp4Extractor.decodeKeyframesRawImage(moovInfo, successDataOnly) { image, outputFormat, codecName, frameIndex ->
            CloudTrace.event("sprite.decode", detail = "codec=$codecName image=${image.width}x${image.height} crop=${image.cropRect.width()}x${image.cropRect.height()} strides=${image.planes.joinToString(",") { "${it.rowStride}/${it.pixelStride}" }} output=${scaleW}x$scaleH")
            val target = successEntries.getOrNull(frameIndex)?.first ?: return@decodeKeyframesRawImage

            val forceNV21 = codecName.startsWith("OMX.qcom.", ignoreCase = true)
            val midW = scaleW * 2
            val midH = scaleH * 2
            val scaled = YuvToBitmapBridge.scaleTwoPassFromImage(
                image,
                midWidth = midW,
                midHeight = midH,
                dstWidth = scaleW,
                dstHeight = scaleH,
                forceNV21 = forceNV21,
            ) ?: return@decodeKeyframesRawImage

            val nativeIsSolid = isMostlySolidColorNative(scaled)
            if (nativeIsSolid == true && target.assignments.none { it.allowFlatFallback }) {
                nativeSolidSkippedCells += target.assignments.size
                return@decodeKeyframesRawImage
            }

            val colorStandard = outputFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD, 1)
            val colorRange = outputFormat.getInteger(MediaFormat.KEY_COLOR_RANGE, 2)

            var bitmap = YuvToBitmapBridge.imageToBitmap(
                yBuf = scaled.y,
                yRowStride = scaled.strideY,
                yPixelStride = 1,
                uBuf = scaled.u,
                uRowStride = scaled.strideU,
                uPixelStride = 1,
                vBuf = scaled.v,
                vRowStride = scaled.strideV,
                vPixelStride = 1,
                cropLeft = 0,
                cropTop = 0,
                cropWidth = scaled.width,
                cropHeight = scaled.height,
                colorStandard = colorStandard,
                colorRange = colorRange,
                forceNV21 = false,
            ) ?: return@decodeKeyframesRawImage

            if (needsRotation) {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(moovInfo.rotation.toFloat())
                val rotated = android.graphics.Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    true,
                )
                bitmap.recycle()
                // 90/270 swaps w/h; fit the rotated frame back into the sprite cell.
                bitmap = if (rotated.width == thumbW && rotated.height == thumbH) {
                    rotated
                } else {
                    val fitted = android.graphics.Bitmap.createScaledBitmap(
                        rotated,
                        thumbW,
                        thumbH,
                        true,
                    )
                    if (fitted !== rotated) rotated.recycle()
                    fitted
                }
            }

            val isSolid = nativeIsSolid ?: bitmap.isMostlySolidColor()
            val usableAssignments = target.assignments.filter { assignment ->
                assignment.gridIndex !in compositedGridIndices &&
                    (!isSolid || assignment.allowFlatFallback)
            }
            if (usableAssignments.isEmpty()) {
                if (nativeIsSolid == null && isSolid) {
                    bitmapSolidSkippedCells += target.assignments.size
                }
                bitmap.recycle()
                return@decodeKeyframesRawImage
            }

            for (assignment in usableAssignments) {
                val col = assignment.gridIndex % gridCols
                val row = assignment.gridIndex / gridCols
                if (YuvToBitmapBridge.compositeToSheet(bitmap, spriteSheet, col, row, thumbW, thumbH, gridCols)) {
                    compositedGridIndices += assignment.gridIndex
                }
            }
            bitmap.recycle()
        }
        return SpriteDecodeBatchResult(
            compositedGridIndices = compositedGridIndices,
            nativeSolidSkippedCells = nativeSolidSkippedCells,
            bitmapSolidSkippedCells = bitmapSolidSkippedCells,
        )
    }

    private fun isMostlySolidColorNative(scaled: ScaledYuv): Boolean? {
        if (!nativeSolidColorCheckAvailable) return null
        return try {
            YuvToBitmapBridge.i420IsMostlySolidColor(
                yBuf = scaled.y,
                yRowStride = scaled.strideY,
                uBuf = scaled.u,
                uRowStride = scaled.strideU,
                vBuf = scaled.v,
                vRowStride = scaled.strideV,
                width = scaled.width,
                height = scaled.height,
                threshold = 0.7f,
                tolerance = 30,
            )
        } catch (_: UnsatisfiedLinkError) {
            nativeSolidColorCheckAvailable = false
            null
        }
    }

    private fun buildRequestClient(httpHeaders: Map<String, String>?): OkHttpClient = sharedOkHttpClient.newBuilder()
        .apply {
            if (httpHeaders != null) {
                addInterceptor { chain ->
                    var request = chain.request()
                    for ((key, value) in httpHeaders) {
                        request = request.newBuilder().header(key, value).build()
                    }
                    chain.proceed(request)
                }
            }
        }
        .build()

    private fun generateFromMediaExtractor(
        source: String,
        durationMs: Long,
        spriteFile: File,
        metaFile: File,
        cacheDir: File,
        cacheKey: String,
        context: Context? = null,
    ): YumeSpriteResult? {
        val actualFrameCount = if (durationMs < 10000) {
            ((durationMs / 100).toInt()).coerceIn(10, FRAME_COUNT)
        } else {
            FRAME_COUNT
        }

        val gridCols = findBestGrid(actualFrameCount).first
        val gridRows = findBestGrid(actualFrameCount).second
        val intervalMs = durationMs.toDouble() / actualFrameCount
        val extractor = MediaExtractor()
        if (source.startsWith("content://") && context != null) {
            extractor.setDataSource(context, Uri.parse(source), null)
            Logger.d(TAG, "MediaExtractor setDataSource with content:// URI: $source")
        } else {
            extractor.setDataSource(source)
        }

        val videoTrackIndex = findVideoTrack(extractor)
        if (videoTrackIndex < 0) {
            extractor.release()
            Logger.w(TAG, "No video track found in $source")
            return null
        }
        extractor.selectTrack(videoTrackIndex)
        val trackFormat = extractor.getTrackFormat(videoTrackIndex)

        val mime = trackFormat.getString(MediaFormat.KEY_MIME)
            ?: run {
                extractor.release()
                return null
            }
        val codecName = findBestDecoderForMime(mime)
            ?: run {
                extractor.release()
                Logger.w(TAG, "No decoder for $mime")
                return null
            }

        val encodedW = trackFormat.getInteger(MediaFormat.KEY_WIDTH, 0)
        val encodedH = trackFormat.getInteger(MediaFormat.KEY_HEIGHT, 0)
        val rotation = try {
            trackFormat.getInteger("rotation-degrees")
        } catch (_: Exception) {
            0
        }
        val needsRot = rotation == 90 || rotation == 270
        val displayW = if (needsRot) encodedH else encodedW
        val displayH = if (needsRot) encodedW else encodedH
        val (thumbW, thumbH) = computeThumbDimensions(displayW, displayH)
        val (scaleW, scaleH) = computeThumbDimensions(encodedW, encodedH)

        var metadata = YumeSpriteMetadata(
            cols = gridCols,
            rows = gridRows,
            frameCount = actualFrameCount,
            thumbWidth = thumbW,
            thumbHeight = thumbH,
            intervalMs = intervalMs,
            durationMs = durationMs,
        )

        val spriteSheet = Bitmap.createBitmap(
            gridCols * thumbW,
            gridRows * thumbH,
            Bitmap.Config.ARGB_8888,
        )

        val codec = MediaCodec.createByCodecName(codecName)
        codec.configure(trackFormat, null, null, 0)
        codec.start()

        try {
            val processedPts = HashMap<Long, Int>()
            val validCells = LinkedHashSet<Int>()

            for (i in 0 until actualFrameCount) {
                val targetTimeUs = (i * intervalMs * 1000).toLong()
                extractor.seekTo(targetTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                val sampleTime = extractor.sampleTime
                val previousCell = processedPts[sampleTime]
                if (previousCell != null && sampleTime >= 0) {
                    val previous = Bitmap.createBitmap(spriteSheet,
                        previousCell % gridCols * thumbW, previousCell / gridCols * thumbH, thumbW, thumbH)
                    if (YuvToBitmapBridge.compositeToSheet(previous, spriteSheet, i % gridCols, i / gridCols, thumbW, thumbH, gridCols)) {
                        validCells.add(i)
                    }
                    previous.recycle()
                    continue
                }

                val col = i % gridCols
                val row = i / gridCols

                val isQcom = codecName.startsWith("OMX.qcom.", ignoreCase = true)
                val midW = scaleW * 2
                val midH = scaleH * 2
                decodeOneFrameToImage(codec, extractor) { image, outputFormat ->
                    val scaled = YuvToBitmapBridge.scaleTwoPassFromImage(
                        image,
                        midWidth = midW,
                        midHeight = midH,
                        dstWidth = scaleW,
                        dstHeight = scaleH,
                        forceNV21 = isQcom,
                    ) ?: return@decodeOneFrameToImage

                    val colorStandard = outputFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD, 1)
                    val colorRange = outputFormat.getInteger(MediaFormat.KEY_COLOR_RANGE, 2)

                    var bitmap = YuvToBitmapBridge.imageToBitmap(
                        yBuf = scaled.y, yRowStride = scaled.strideY, yPixelStride = 1,
                        uBuf = scaled.u, uRowStride = scaled.strideU, uPixelStride = 1,
                        vBuf = scaled.v, vRowStride = scaled.strideV, vPixelStride = 1,
                        cropLeft = 0, cropTop = 0,
                        cropWidth = scaled.width, cropHeight = scaled.height,
                        colorStandard = colorStandard, colorRange = colorRange,
                        forceNV21 = false,
                    ) ?: return@decodeOneFrameToImage

                    if (bitmap.isMostlySolidColor()) {
                        bitmap.recycle()
                        return@decodeOneFrameToImage
                    }

                    if (needsRot) {
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(rotation.toFloat())
                        val rotated = android.graphics.Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            bitmap.width,
                            bitmap.height,
                            matrix,
                            true,
                        )
                        bitmap.recycle()
                        // 90/270 swaps w/h; fit the rotated frame back into the sprite cell.
                        bitmap = if (rotated.width == thumbW && rotated.height == thumbH) {
                            rotated
                        } else {
                            val fitted = android.graphics.Bitmap.createScaledBitmap(
                                rotated,
                                thumbW,
                                thumbH,
                                true,
                            )
                            if (fitted !== rotated) rotated.recycle()
                            fitted
                        }
                    }

                    if (YuvToBitmapBridge.compositeToSheet(bitmap, spriteSheet, col, row, thumbW, thumbH, gridCols)) {
                        validCells.add(i)
                        if (sampleTime >= 0) processedPts[sampleTime] = i
                    }
                    bitmap.recycle()
                }
            }

            if (validCells.isEmpty()) {
                spriteSheet.recycle()
                return null
            }
            FileOutputStream(spriteFile).use { out ->
                spriteSheet.compress((if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP), WEBP_QUALITY, out)
            }
            spriteSheet.recycle()

            metadata = metadata.copy(validCells = validCells.toList())
            metadata.saveTo(metaFile)
        CloudTrace.event("sprite.saved", detail = "frames=${metadata.frameCount} bytes=${spriteFile.length()}")

            Logger.d(TAG, "Sprite sheet saved: ${spriteFile.length()} bytes, $actualFrameCount frames")
            return YumeSpriteResult(spriteFile, metadata)
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
    }

    private fun computeThumbDimensions(videoWidth: Int, videoHeight: Int): Pair<Int, Int> {
        if (videoWidth <= 0 || videoHeight <= 0) return THUMB_WIDTH to THUMB_HEIGHT
        val maxDim = thumbnailMaxDimension.coerceIn(160, 480)
        return if (videoWidth >= videoHeight) {
            maxDim to maxOf(1, (maxDim * videoHeight) / videoWidth)
        } else {
            maxOf(1, (maxDim * videoWidth) / videoHeight) to maxDim
        }
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                return i
            }
        }
        return -1
    }

    private fun decodeOneFrameToImage(
        codec: MediaCodec,
        extractor: MediaExtractor,
        onImage: (image: Image, outputFormat: MediaFormat) -> Unit,
    ) {
        try {
            codec.flush()

            val inputIndex = codec.dequeueInputBuffer(500_000)
            if (inputIndex < 0) return
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize <= 0) return
            val sampleTime = extractor.sampleTime
            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM)

            val deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MS
            var outputFormat: MediaFormat? = null
            val info = MediaCodec.BufferInfo()

            while (System.currentTimeMillis() < deadline) {
                val outputIndex = codec.dequeueOutputBuffer(info, 100_000)
                when {
                    outputIndex >= 0 -> {
                        if (info.size <= 0 || (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            codec.releaseOutputBuffer(outputIndex, false)
                            continue
                        }

                        val image = runCatching { codec.getOutputImage(outputIndex) }.getOrNull()
                        if (image != null) {
                            try {
                                onImage(image, outputFormat ?: codec.outputFormat)
                            } finally {
                                image.close()
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        return
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    }
                    else -> {
                        Logger.w(TAG, "Unexpected dequeue result: $outputIndex")
                        return
                    }
                }
            }

            Logger.w(TAG, "Decode timeout")
        } catch (e: Exception) {
            Logger.w(TAG, "decodeOneFrameToImage failed: ${e.message}")
        }
    }

    private fun findBestDecoderForMime(mime: String): String? {
        decoderNameCache[mime]?.let { return it }
        val result = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .sortedWith(
                compareBy<MediaCodecInfo> {
                    it.name.startsWith("OMX.google.", ignoreCase = true) ||
                        it.name.startsWith("c2.android.", ignoreCase = true)
                }.thenBy { it.name },
            )
            .firstOrNull()
            ?.name ?: return null
        decoderNameCache[mime] = result
        return result
    }

    private fun findBestGrid(count: Int): Pair<Int, Int> {
        for (cols in COLS downTo 1) {
            val rows = (count + cols - 1) / cols
            if (rows <= ROWS) return cols to rows
        }
        return COLS to ROWS
    }

    private fun readMetadata(file: File): YumeSpriteMetadata? = try {
        YumeSpriteMetadata.fromJson(file.readText())
    } catch (_: Exception) {
        null
    }
}

data class YumeSpriteMetadata(
    val cols: Int,
    val rows: Int,
    val frameCount: Int,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val intervalMs: Double,
    val durationMs: Long,
    val validCells: List<Int>? = null,
    val fragmentIndexVersion: Int = 0,
) {
    fun isComplete(): Boolean = frameCount in 1..(cols * rows) &&
        validCells?.size == frameCount && validCells?.toSet() == (0 until frameCount).toSet()

    fun toJson(): String = buildString {
        append("{")
        append("\"cols\":$cols,")
        append("\"rows\":$rows,")
        append("\"frameCount\":$frameCount,")
        append("\"thumbWidth\":$thumbWidth,")
        append("\"thumbHeight\":$thumbHeight,")
        append("\"intervalMs\":$intervalMs,")
        append("\"durationMs\":$durationMs,")
        append("\"fragmentIndexVersion\":$fragmentIndexVersion,")
        append("\"validCells\":\"${validCells?.joinToString(";").orEmpty()}\"")
        append("}")
    }

    fun saveTo(file: File) {
        file.writeText(toJson())
    }

    companion object {
        fun fromJson(json: String): YumeSpriteMetadata {
            val map = json.trim('{', '}').split(",").associate {
                val (k, v) = it.split(":", limit = 2)
                k.trim('"') to v.trim()
            }
            return YumeSpriteMetadata(
                cols = map["cols"]?.toInt() ?: 10,
                rows = map["rows"]?.toInt() ?: 10,
                frameCount = map["frameCount"]?.toInt() ?: 100,
                thumbWidth = map["thumbWidth"]?.toInt() ?: YumeSpriteSheetGenerator.THUMB_WIDTH,
                thumbHeight = map["thumbHeight"]?.toInt() ?: YumeSpriteSheetGenerator.THUMB_HEIGHT,
                intervalMs = map["intervalMs"]?.toDouble() ?: 0.0,
                durationMs = map["durationMs"]?.toLong() ?: 0L,
                fragmentIndexVersion = map["fragmentIndexVersion"]?.toInt() ?: 0,
                validCells = map["validCells"]?.trim('"')?.split(';')?.mapNotNull { it.toIntOrNull() },
            )
        }
    }
}

data class YumeSpriteResult(
    val file: File,
    val metadata: YumeSpriteMetadata,
)

private data class FrameTarget(
    val gridIndex: Int,
    val targetTimeMs: Long,
    val keyframeIndex: Int,
)

private data class UniqueFrameTarget(
    val assignments: List<CandidateAssignment>,
    val kf: Mp4KeyframeExtractor.KeyframeEntry,
)

private data class CandidateAssignment(
    val gridIndex: Int,
    val allowFlatFallback: Boolean,
)

private data class SpriteDecodeBatchResult(
    val compositedGridIndices: Set<Int> = emptySet(),
    val nativeSolidSkippedCells: Int = 0,
    val bitmapSolidSkippedCells: Int = 0,
)
