package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.PreferenceStore

/** Anime fields never inherit or write the local/network browser's field preferences. */
class AnimeFieldPreferences(store: PreferenceStore) {
  val unlimitedNameLines = store.getBoolean("anime_unlimited_name_lines", false)
  val showVideoThumbnails = store.getBoolean("anime_show_video_thumbnails", true)
  val showFolderThumbnails = store.getBoolean("anime_show_posters", true)
  val showTotalVideosChip = store.getBoolean("anime_show_total_videos_chip", true)
  val showTotalSizeChip = store.getBoolean("anime_show_total_size_chip", false)
  val showFolderPath = store.getBoolean("anime_show_folder_path", false)
  val showSizeChip = store.getBoolean("anime_show_size_chip", true)
  val showResolutionChip = store.getBoolean("anime_show_resolution_chip", false)
  val showFramerateInResolution = store.getBoolean("anime_show_framerate_in_resolution", false)
  val showCodecSupportIndicator = store.getBoolean("anime_show_codec_support_indicator", false)
  val showProgressBar = store.getBoolean("anime_show_progress_bar", true)
  val showSubtitleIndicator = store.getBoolean("anime_show_subtitle_indicator", false)
  val showExtensionField = store.getBoolean("anime_show_extension_field", false)
  val showDurationField = store.getBoolean("anime_show_duration_field", true)
  val showTotalDurationChip = store.getBoolean("anime_show_total_duration_chip", false)
  val showDateChip = store.getBoolean("anime_show_date_chip", false)
  val centerGridTitles = store.getBoolean("anime_center_grid_titles", true)
}
