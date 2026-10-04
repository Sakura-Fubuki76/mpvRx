package app.gyrolet.mpvrx.ui.browser.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R

/** Localize display labels while leaving preference and server sort identifiers unchanged. */
@Composable
internal fun browserOptionLabel(label: String): String {
  val resource = when (label) {
    "Title" -> R.string.ui_title
    "Name" -> R.string.ui_name
    "Duration" -> R.string.ui_duration
    "Date" -> R.string.ui_date
    "Size" -> R.string.update_size
    "Count" -> R.string.ui_count
    "Progress" -> R.string.ui_progress
    "Author" -> R.string.audiobook_author
    "Last Played" -> R.string.ui_last_played
    "Date Added" -> R.string.ui_date_added
    "Album" -> R.string.ui_album
    "Artist" -> R.string.ui_artist
    "Rating" -> R.string.ui_rating
    "Runtime" -> R.string.ui_runtime
    "A-Z" -> R.string.ui_a_z
    "Z-A" -> R.string.ui_z_a
    "Oldest" -> R.string.ui_oldest
    "Newest" -> R.string.ui_newest
    "Smallest" -> R.string.ui_smallest
    "Largest" -> R.string.ui_largest
    "Biggest" -> R.string.ui_biggest
    "Fewest" -> R.string.ui_fewest
    "Least" -> R.string.ui_least
    "Most" -> R.string.ui_most
    "Asc" -> R.string.ui_asc
    "Desc" -> R.string.ui_desc
    "Shortest" -> R.string.ui_shortest
    "Longest" -> R.string.ui_longest
    "Lowest" -> R.string.ui_lowest
    "Highest" -> R.string.ui_highest
    "View Mode" -> R.string.ui_view_mode
    "Folder" -> R.string.ui_folder
    "Tree" -> R.string.ui_tree
    "Library" -> R.string.ui_library
    "Layout" -> R.string.playlist_layout
    "List" -> R.string.playlist_view_list
    "Grid" -> R.string.playlist_view_grid
    "Full Name" -> R.string.ui_full_name
    "Path" -> R.string.ui_path
    "Total Media" -> R.string.ui_total_media
    "Total Duration" -> R.string.ui_total_duration
    "Folder Size" -> R.string.ui_folder_size
    "Folder Thumbnails" -> R.string.ui_folder_thumbnails
    "Center Titles" -> R.string.ui_center_titles
    "Manual Grid" -> R.string.ui_manual_grid
    "Thumbnails" -> R.string.pref_appearance_category_thumbnails
    "Video Thumbnails" -> R.string.ui_video_thumbnails
    "Extension" -> R.string.ui_extension
    "Subtitle Indicator" -> R.string.ui_subtitle_indicator
    "Resolution" -> R.string.ui_resolution
    "Framerate" -> R.string.ui_framerate
    "Codec support" -> R.string.ui_codec_support
    "Progress Bar" -> R.string.ui_progress_bar
    "Subtitle" -> R.string.audiobook_subtitle
    "Videos" -> R.string.ui_videos
    "Images" -> R.string.ui_images
    "Only for folder list" -> R.string.ui_only_for_folder_list
    "Grid Columns" -> R.string.ui_grid_columns
    "Video" -> R.string.watch_stats_video
    "Cover Art Size" -> R.string.cover_art_size
    "Unplayed Only" -> R.string.ui_unplayed_only
    "Landscape" -> R.string.pref_player_orientation_landscape
    "Portrait" -> R.string.pref_player_orientation_portrait
    else -> null
  }
  if (resource != null) return stringResource(resource)
  val orientation = when {
    label.endsWith(" (Landscape)") -> stringResource(R.string.pref_player_orientation_landscape)
    label.endsWith(" (Portrait)") -> stringResource(R.string.pref_player_orientation_portrait)
    else -> return label
  }
  return stringResource(R.string.browser_grid_orientation, browserOptionLabel(label.substringBefore(" (")), orientation)
}
