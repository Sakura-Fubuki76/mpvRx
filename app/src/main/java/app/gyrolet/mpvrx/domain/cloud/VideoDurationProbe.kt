package app.gyrolet.mpvrx.domain.cloud

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

suspend fun probeVideoDurationMs(
    url: String,
    okHttpClient: okhttp3.OkHttpClient,
    extension: String = "",
    mp4Duration: (suspend (String) -> Long?)? = null,
): Long? {
    return try {
        val ext = extension.lowercase()
        val probeSize = when (ext) {
            "flv" -> 4096
            "mp4", "mov" -> 16384
            else -> 16384
        }

        val data = fetchWithRetry(url, probeSize, okHttpClient)
            ?: return null

        val result = when {
            isMkv(data) -> parseMkvDuration(data)
            isMp4(data) -> parseMp4Duration(data) ?: mp4Duration?.invoke(url)
            isFlv(data) -> parseFlvDuration(data)
            isAvi(data) -> parseAviDuration(data)
            isTs(data) -> {
                // MPEG-TS carries no container duration; estimate from PTS at both ends.
                val tail = fetchTailWithRetry(url, TS_PROBE_TAIL_BYTES, okHttpClient)
                tail?.let { parseTsDuration(data, it) }
            }
            else -> null
        }
        result?.takeIf { it > 0L }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // A malformed/failed video must not cancel metadata collection for its siblings.
        null
    }
}

private const val TS_PACKET_SIZE = 188
private const val TS_PROBE_TAIL_BYTES = 256 * 1024

private fun isTs(data: ByteArray): Boolean = data.size >= TS_PACKET_SIZE * 2 &&
    data[0] == 0x47.toByte() &&
    data[TS_PACKET_SIZE] == 0x47.toByte()

private suspend fun fetchTailWithRetry(
    url: String,
    tailBytes: Int,
    okHttpClient: okhttp3.OkHttpClient,
    maxRetries: Int = 2,
): ByteArray? {
    for (attempt in 0..maxRetries) {
        currentCoroutineContext().ensureActive()
        if (attempt > 0) {
            delay(500L * (1 shl (attempt - 1)))
        }
        try {
            val builder = videoMetadataRequest(url)
                .header("Range", "bytes=-$tailBytes")
                .header("Accept", "*/*")
            if (url.toHttpUrlOrNull()?.host?.let { it == "baidu.com" || it.endsWith(".baidu.com") || it == "baidupcs.com" || it.endsWith(".baidupcs.com") } == true) {
                builder.header("User-Agent", "pan.baidu.com")
            }
            val request = builder.build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body
                // Only trust a real suffix range; a 200 body would be the head, not the tail.
                if (response.code != 206 || body == null) return null
                val bytes = body.byteStream().use { it.readNBytes(tailBytes) }
                return bytes.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }
    return null
}

private fun parseTsDuration(headData: ByteArray, tailData: ByteArray): Long? {
    val firstPts = extractTsPts(headData, wantMax = false) ?: return null
    val lastPts = extractTsPts(tailData, wantMax = true) ?: return null
    var delta = lastPts - firstPts
    if (delta < 0) delta += 1L shl 33 // 33-bit PTS wrap (clips longer than ~26.5h)
    // Overlapping head/tail on tiny files yields a bogus sub-second delta.
    val durationMs = delta * 1000L / 90_000L
    return durationMs.takeIf { it > 1_000L }
}

/** Min (head) or max (tail) PTS found in video PES packets within [data]. */
private fun extractTsPts(data: ByteArray, wantMax: Boolean): Long? {
    var best: Long? = null
    var i = 0
    val limit = data.size - 9
    while (i < limit) {
        if (data[i] == 0x00.toByte() && data[i + 1] == 0x00.toByte() && data[i + 2] == 0x01.toByte()) {
            val streamId = data[i + 3].toInt() and 0xFF
            // PES header: [00 00 01 sid][len 2B]['10' flags][PTS flags][header len][fields...]
            val isVideoStream = streamId in 0xE0..0xEF
            // data[i+4..5] is PES_packet_length; the '10' flags byte sits at i+6.
            val hasPesHeader = (data[i + 6].toInt() and 0xC0) == 0x80
            if (isVideoStream && hasPesHeader) {
                val ptsFlags = data[i + 7].toInt() and 0xC0
                if (ptsFlags == 0x80 || ptsFlags == 0xC0) {
                    val pts = decodeTsPts(data, i + 9)
                    if (pts != null && (best == null || (if (wantMax) pts > best else pts < best))) {
                        best = pts
                    }
                }
            }
            i += 3
        }
        i++
    }
    return best
}

private fun decodeTsPts(data: ByteArray, pos: Int): Long? {
    if (pos + 5 > data.size) return null
    val first = data[pos].toInt() and 0xFF
    // '0010' (PTS) or '0011' (PTS+DTS) prefix with marker bit.
    if ((first and 0xC0) != 0 || (first and 0x20) == 0) return null
    return ((first shr 1 and 0x07).toLong() shl 30) or
        ((data[pos + 1].toLong() and 0xFF) shl 22) or
        (((data[pos + 2].toLong() and 0xFF) shr 1) shl 15) or
        ((data[pos + 3].toLong() and 0xFF) shl 7) or
        ((data[pos + 4].toLong() and 0xFF) shr 1)
}
private suspend fun fetchWithRetry(
    url: String,
    probeSize: Int,
    okHttpClient: okhttp3.OkHttpClient,
    maxRetries: Int = 2,
): ByteArray? {
    var lastException: Exception? = null
    for (attempt in 0..maxRetries) {
        currentCoroutineContext().ensureActive()
        if (attempt > 0) {
            val delayMs = 500L * (1 shl (attempt - 1))
            delay(delayMs)
        }
        try {
            val builder = videoMetadataRequest(url)
                .header("Range", "bytes=0-${probeSize - 1}")
                .header("Accept", "*/*")
            if (url.toHttpUrlOrNull()?.host?.let { it == "baidu.com" || it.endsWith(".baidu.com") || it == "baidupcs.com" || it.endsWith(".baidupcs.com") } == true) {
                builder.header("User-Agent", "pan.baidu.com")
            }
            val request = builder.build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    if (response.code == 403 || response.code == 429 || response.code >= 500) {
                        return@use
                    }
                    return null
                }
                // Some WebDAV servers ignore Range and return the entire video.
                val bytes = body.byteStream().use { it.readNBytes(probeSize) }
                if (bytes.size < 32) {
                    return null
                }
                return bytes
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            lastException = e
        }
    }
    return null
}

private fun isMkv(data: ByteArray): Boolean = data.size >= 4 &&
    data[0] == 0x1A.toByte() &&
    data[1] == 0x45.toByte() &&
    data[2] == 0xDF.toByte() &&
    data[3] == 0xA3.toByte()

private fun isMp4(data: ByteArray): Boolean = data.size >= 12 &&
    data[4] == 'f'.code.toByte() &&
    data[5] == 't'.code.toByte() &&
    data[6] == 'y'.code.toByte() &&
    data[7] == 'p'.code.toByte()

private fun isFlv(data: ByteArray): Boolean = data.size >= 3 &&
    data[0] == 'F'.code.toByte() &&
    data[1] == 'L'.code.toByte() &&
    data[2] == 'V'.code.toByte()

private fun isAvi(data: ByteArray): Boolean = data.size >= 12 &&
    data[0] == 'R'.code.toByte() &&
    data[1] == 'I'.code.toByte() &&
    data[2] == 'F'.code.toByte() &&
    data[3] == 'F'.code.toByte() &&
    data[8] == 'A'.code.toByte() &&
    data[9] == 'V'.code.toByte() &&
    data[10] == 'I'.code.toByte() &&
    data[11] == ' '.code.toByte()

private fun Long.safeToInt(ceiling: Int = Int.MAX_VALUE): Int = coerceAtMost(ceiling.toLong()).toInt()

private fun parseMkvDuration(data: ByteArray): Long? {
    try {
        var offset = 0L
        val (headerId, headerIdLen) = readEbmlId(data, offset.toInt()) ?: return null
        val (headerSize, headerSizeLen) = readEbmlSize(data, offset.toInt() + headerIdLen) ?: return null
        offset += headerIdLen + headerSizeLen + headerSize.safeToInt(data.size)

        val segmentId = 0x18538067L
        var segOffset = -1L
        var segSize = 0L
        while (offset < data.size - 4) {
            val (eid, idLen) = readEbmlId(data, offset.toInt()) ?: break
            val (size, sizeLen) = readEbmlSize(data, offset.toInt() + idLen) ?: break
            if (eid == segmentId) {
                segOffset = offset + idLen + sizeLen
                segSize = size
                break
            }
            offset += idLen + sizeLen + size.safeToInt(data.size)
        }
        if (segOffset < 0) return null

        val searchEnd = minOf(segOffset + segSize.safeToInt(data.size), data.size.toLong()).toInt()
        if (searchEnd <= 0) return null

        offset = segOffset
        while (offset < searchEnd - 4) {
            val (eid, idLen) = readEbmlId(data, offset.toInt()) ?: break
            val (size, sizeLen) = readEbmlSize(data, offset.toInt() + idLen) ?: break
            if (eid == 0x1549A966L) {
                val infoDataStart = offset + idLen + sizeLen
                val infoDataEnd = minOf(infoDataStart + size.safeToInt(data.size), data.size.toLong()).toInt()
                var infoOffset = infoDataStart
                var durationMs: Long? = null
                var timescaleNs = 1_000_000L
                while (infoOffset < infoDataEnd - 4) {
                    val (ieid, iidLen) = readEbmlId(data, infoOffset.toInt()) ?: break
                    val (isize, isizeLen) = readEbmlSize(data, infoOffset.toInt() + iidLen) ?: break
                    when (ieid) {
                        0x2AD7B1L -> {
                            if (isize >= 1) {
                                val raw = readUintInfoValue(data, infoOffset.toInt() + iidLen + isizeLen, isize.toInt())
                                if (raw > 0) timescaleNs = raw
                            }
                        }
                        0x4489L -> {
                            if (isize == 4L || isize == 8L) {
                                val durStart = (infoOffset + iidLen + isizeLen).toInt()
                                val dur = when (isize.toInt()) {
                                    4 -> {
                                        val bits = readInt32BE(data, durStart)
                                        java.lang.Float.intBitsToFloat(bits).toDouble()
                                    }
                                    else -> {
                                        val raw = readInt64BE(data, durStart)
                                        java.lang.Double.longBitsToDouble(raw)
                                    }
                                }
                                durationMs = (dur * timescaleNs / 1_000_000.0).toLong().takeIf { it > 0 }
                            }
                        }
                    }
                    if (durationMs != null) break
                    infoOffset += iidLen + isizeLen + isize.safeToInt(data.size)
                }
                return durationMs
            }
            offset += idLen + sizeLen + size.safeToInt(data.size)
        }
    } catch (_: Exception) { }
    return null
}

private fun readUintInfoValue(data: ByteArray, offset: Int, size: Int): Long {
    var v = 0L
    for (i in 0 until minOf(size, 8)) {
        v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
    }
    return v
}

private fun readEbmlId(data: ByteArray, offset: Int): Pair<Long, Int>? {
    if (offset >= data.size) return null
    val first = data[offset].toInt() and 0xFF
    val len = when {
        first and 0x80 != 0 -> 1
        first and 0x40 != 0 -> 2
        first and 0x20 != 0 -> 3
        first and 0x10 != 0 -> 4
        else -> return null
    }
    if (offset + len > data.size) return null
    var id = 0L
    for (i in 0 until len) {
        id = (id shl 8) or (data[offset + i].toLong() and 0xFF)
    }
    return id to len
}

private fun readEbmlSize(data: ByteArray, offset: Int): Pair<Long, Int>? {
    if (offset >= data.size) return null
    val first = data[offset].toInt() and 0xFF
    var mask = 0x80
    var len = 1
    while (len <= 8 && (first and mask) == 0) {
        mask = mask shr 1
        len++
    }
    if (len > 8 || offset + len > data.size) return null
    var value = (first and (mask - 1)).toLong()
    for (i in 1 until len) {
        value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
    }
    return value to len
}

private fun parseMp4Duration(data: ByteArray): Long? {
    try {
        val (moovOffset, moovSize) = findMp4Atom(data, 0, data.size, "moov") ?: return null
        val moovEnd = minOf((moovOffset + moovSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), data.size)
        val (mvhdOffset, _) = findMp4Atom(data, moovOffset + 8, moovEnd, "mvhd") ?: return null
        if (mvhdOffset + 32 > data.size) return null

        val version = data[mvhdOffset + 8].toInt() and 0xFF
        val timescaleOffset: Int
        val durationOffset: Int
        if (version == 1) {
            timescaleOffset = mvhdOffset + 28
            durationOffset = mvhdOffset + 32
            if (durationOffset + 8 > data.size) return null
        } else {
            timescaleOffset = mvhdOffset + 20
            durationOffset = mvhdOffset + 24
            if (durationOffset + 4 > data.size) return null
        }

        val timescale = readInt32BE(data, timescaleOffset)
        val duration = if (version == 1) {
            readInt64BE(data, durationOffset)
        } else {
            readUInt32BE(data, durationOffset)
        }
        if (timescale > 0 && duration > 0) {
            return (duration * 1000L / timescale).takeIf { it > 0 }
        }
    } catch (_: Exception) { }
    return null
}

private fun findMp4Atom(data: ByteArray, startOffset: Int, endOffset: Int, targetType: String): Pair<Int, Long>? {
    if (targetType.length != 4) return null
    val typeBytes = targetType.encodeToByteArray()
    val boundedEnd = minOf(endOffset, data.size)
    var offset = startOffset
    while (offset + 8 <= boundedEnd) {
        val size32 = readUInt32BE(data, offset)
        val atomSize = if (size32 == 0L) {
            (boundedEnd - offset).toLong()
        } else if (size32 == 1L) {
            if (offset + 16 > boundedEnd) return null
            readInt64BE(data, offset + 8)
        } else {
            size32
        }
        if (atomSize < 8L) return null
        val matches = (0..3).all { data[offset + 4 + it] == typeBytes[it] }
        if (matches) return offset to atomSize
        val nextOffset = offset + atomSize
        if (nextOffset <= offset || nextOffset > Int.MAX_VALUE) return null
        offset = nextOffset.toInt()
    }
    return null
}

private fun parseFlvDuration(data: ByteArray): Long? {
    try {
        var offset = 9
        offset += 4
        while (offset + 15 < data.size) {
            val tagType = data[offset].toInt() and 0xFF
            val dataSize = readInt24BE(data, offset + 1)

            if (tagType == 0x12) {
                val bodyStart = offset + 11
                val bodyEnd = minOf(bodyStart + dataSize, data.size)
                var pos = bodyStart

                val skipped = skipAmf0Value(data, pos, bodyEnd)
                if (skipped == null || skipped <= pos) break
                pos = skipped

                if (pos + 1 < bodyEnd) {
                    val marker = data[pos].toInt() and 0xFF
                    if (marker == 0x08) {
                        pos += 4
                        pos = skipAmf0Value(data, pos, bodyEnd) ?: break
                    } else {
                        pos = skipAmf0Value(data, pos, bodyEnd) ?: break
                    }
                }

                pos = bodyStart

                val afterFirst = skipAmf0Value(data, pos, bodyEnd) ?: break

                pos = afterFirst
                val duration = findAmf0KeyDuration(data, pos, bodyEnd)
                if (duration != null) return duration
                break
            }
            offset += 11 + dataSize + 4
        }
    } catch (_: Exception) { }
    return null
}

private fun skipAmf0Value(data: ByteArray, pos: Int, end: Int): Int? {
    if (pos >= end) return null
    val marker = data[pos].toInt() and 0xFF
    return when (marker) {
        0x00 -> {
            val next = pos + 1 + 8
            if (next > end) null else next
        }
        0x01 -> {
            val next = pos + 2
            if (next > end) null else next
        }
        0x02 -> {
            if (pos + 3 > end) return null
            val len = ((data[pos + 1].toInt() and 0xFF) shl 8) or (data[pos + 2].toInt() and 0xFF)
            val next = pos + 3 + len
            if (next > end) null else next
        }
        0x03 -> {
            skipAmf0ObjectBody(data, pos + 1, end)
        }
        0x08 -> {
            if (pos + 5 > end) return null
            skipAmf0ObjectBody(data, pos + 5, end)
        }
        0x05 -> pos + 1
        0x0B -> {
            val next = pos + 1 + 8 + 2
            if (next > end) null else next
        }
        0x0C -> {
            if (pos + 5 > end) return null
            val len = ((data[pos + 1].toInt() and 0xFF) shl 24) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 8) or
                (data[pos + 4].toInt() and 0xFF)
            val next = pos + 5 + len
            if (next > end || len < 0) null else next
        }
        0x0A -> {
            if (pos + 5 > end) return null
            val count = ((data[pos + 1].toInt() and 0xFF) shl 24) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 8) or
                (data[pos + 4].toInt() and 0xFF)
            var p = pos + 5
            for (i in 0 until minOf(count, 1000)) {
                p = skipAmf0Value(data, p, end) ?: return null
            }
            p
        }
        else -> null
    }
}

private fun skipAmf0ObjectBody(data: ByteArray, pos: Int, end: Int): Int? {
    var p = pos
    while (p + 3 <= end) {
        val keyLen = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
        if (keyLen == 0) {
            val termMarker = data[p + 2].toInt() and 0xFF
            if (termMarker == 0x09) return p + 3
        }

        if (p + 2 + keyLen >= end) return null
        p += 2 + keyLen
        p = skipAmf0Value(data, p, end) ?: return null
    }
    return null
}

private fun findAmf0KeyDuration(data: ByteArray, pos: Int, end: Int): Long? {
    var p = pos
    while (p + 3 <= end) {
        val marker = data[p].toInt() and 0xFF
        if (marker == 0x03) {
            p++
            continue
        }
        if (marker == 0x08) {
            if (p + 5 > end) return null
            p += 5
            continue
        }
        val keyLen = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
        if (keyLen == 0) {
            val termMarker = data[p + 2].toInt() and 0xFF
            if (termMarker == 0x09) return null
            return null
        }
        if (p + 2 + keyLen >= end) return null
        val keyBytes = data.copyOfRange(p + 2, p + 2 + keyLen)
        val key = String(keyBytes, Charsets.UTF_8)
        p += 2 + keyLen
        if (p >= end) return null
        if (key == "duration") {
            val valueMarker = data[p].toInt() and 0xFF
            if (valueMarker == 0x00 && p + 9 <= end) {
                val raw = readInt64BE(data, p + 1)
                val seconds = java.lang.Double.longBitsToDouble(raw)
                return (seconds * 1000).toLong().takeIf { it > 0 }
            }
            return null
        } else {
            p = skipAmf0Value(data, p, end) ?: return null
        }
    }
    return null
}

private fun parseAviDuration(data: ByteArray): Long? {
    try {
        var offset = 12L

        while (offset + 8 < data.size) {
            val o = offset.toInt()
            val chunkId = String(data, o, 4)
            val chunkSize = readUInt32LE(data, o + 4)
            if (chunkId == "LIST") {
                val listType = String(data, o + 8, 4)
                if (listType == "hdrl") {
                    val hdrlEnd = minOf(offset + 12 + chunkSize - 4, data.size.toLong())
                    var hdrlOffset = offset + 12
                    while (hdrlOffset + 8 < hdrlEnd) {
                        val ho = hdrlOffset.toInt()
                        if (ho + 8 > data.size) break
                        val subId = String(data, ho, 4)
                        val subSize = readUInt32LE(data, ho + 4)
                        if (subId == "avih" && subSize >= 56) {
                            val dataStart = ho + 8

                            if (dataStart + 20 > data.size) return null
                            val usPerFrame = readUInt32LE(data, dataStart + 8)
                            val totalFrames = readUInt32LE(data, dataStart + 16)
                            if (usPerFrame > 0 && totalFrames > 0) {
                                return (usPerFrame * totalFrames / 1000).takeIf { it > 0 }
                            }
                            return null
                        }
                        hdrlOffset += 8 + subSize
                    }
                }
                offset += 12 + chunkSize - 4
            } else {
                offset += 8 + chunkSize
            }

            if (offset < 0 || offset > data.size) return null
        }
    } catch (_: Exception) { }
    return null
}

private fun readInt32BE(data: ByteArray, offset: Int): Int = ((data[offset].toInt() and 0xFF) shl 24) or
    ((data[offset + 1].toInt() and 0xFF) shl 16) or
    ((data[offset + 2].toInt() and 0xFF) shl 8) or
    (data[offset + 3].toInt() and 0xFF)

private fun readUInt32BE(data: ByteArray, offset: Int): Long = readInt32BE(data, offset).toLong() and 0xFFFFFFFFL

private fun readInt64BE(data: ByteArray, offset: Int): Long = ((data[offset].toLong() and 0xFF) shl 56) or
    ((data[offset + 1].toLong() and 0xFF) shl 48) or
    ((data[offset + 2].toLong() and 0xFF) shl 40) or
    ((data[offset + 3].toLong() and 0xFF) shl 32) or
    ((data[offset + 4].toLong() and 0xFF) shl 24) or
    ((data[offset + 5].toLong() and 0xFF) shl 16) or
    ((data[offset + 6].toLong() and 0xFF) shl 8) or
    (data[offset + 7].toLong() and 0xFF)

private fun readInt24BE(data: ByteArray, offset: Int): Int = ((data[offset].toInt() and 0xFF) shl 16) or
    ((data[offset + 1].toInt() and 0xFF) shl 8) or
    (data[offset + 2].toInt() and 0xFF)

private fun readInt32LE(data: ByteArray, offset: Int): Int = (data[offset].toInt() and 0xFF) or
    ((data[offset + 1].toInt() and 0xFF) shl 8) or
    ((data[offset + 2].toInt() and 0xFF) shl 16) or
    ((data[offset + 3].toInt() and 0xFF) shl 24)

private fun readUInt32LE(data: ByteArray, offset: Int): Long = readInt32LE(data, offset).toLong() and 0xFFFFFFFFL
