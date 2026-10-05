package app.gyrolet.mpvrx.domain.cloud

enum class AnimeArtworkTarget { DETAIL_POSTER, LIBRARY_POSTER, LOGO }
enum class AnimeArtworkQueryKind { NAME, BANGUMI_ID, TMDB_ID }
data class AnimeArtworkHit(val provider: String, val id: Long, val type: String, val title: String, val preview: String)
data class AnimeArtworkChoice(val url: String, val preview: String, val label: String, val language: String? = null)
data class AnimeArtworkSearchPage(val hits: List<AnimeArtworkHit>, val tmdbAvailable: Boolean, val failedSources: List<String> = emptyList())
