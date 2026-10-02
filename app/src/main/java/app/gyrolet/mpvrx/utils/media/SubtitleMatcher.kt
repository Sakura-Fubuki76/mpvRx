package app.gyrolet.mpvrx.utils.media

internal val EP_REGEX = Regex(
    """[\[【\s](\d{1,4})(?:v\d+)?[]】\s]|""" +
        """[-–~]\s*(\d{1,4})(?:v\d+)?(?:[.\s]|$)|""" +
        """[eE][pP]?\s*(\d{1,4})(?:[.\s]|$)|""" +
        """[#＃](\d{1,4})(?:[.\s]|$)""",
)

internal val TAG_CLEAN_REGEX = Regex(
    """[\[(].*?[])]|""" +
        """\b\d{3,4}[xp].*?(?:[.\s]|$)|""" +
        """\b(?:x264|x265|HEVC|AVC|AV1|AAC|FLAC|MP3|opus|vorbis|10bit|8bit|HDR|SDR|SRTx?\d*|WebRip|BDrip|WEB-DL|BDRip|Ma\d+p)\b|""" +
        """\.\w+$""",
    setOf(RegexOption.IGNORE_CASE),
)

fun fuzzyMatchNames(videoName: String, subtitleName: String): Boolean {
    val videoEp = extractEpisode(videoName)
    val subEp = extractEpisode(subtitleName)
    if (videoEp != null && subEp != null && videoEp != subEp) return false

    val vTokens = extractTitleTokens(videoName)
    val sTokens = extractTitleTokens(subtitleName)
    if (vTokens.isEmpty() || sTokens.isEmpty()) return false

    val common = vTokens.count { it in sTokens }
    val smaller = minOf(vTokens.size, sTokens.size)
    return common.toDouble() / smaller >= 0.5
}

internal fun extractEpisode(name: String): Int? {
    for (mr in EP_REGEX.findAll(name)) {
        for (g in 1..4) {
            mr.groupValues.getOrNull(g)?.toIntOrNull()?.let { return it }
        }
    }
    return null
}

internal fun extractTitleTokens(name: String): Set<String> = name.replace(TAG_CLEAN_REGEX, " ")
    .replace(Regex("""[～~\-_.,!?+/&|\[\]【】()（）]"""), " ")
    .replace(Regex("""\s+"""), " ")
    .trim()
    .lowercase()
    .split(" ")
    .filter { it.length >= 2 }
    .toSet()
