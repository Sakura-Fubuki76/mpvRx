package app.gyrolet.mpvrx.ui.browser.networkstreaming

import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.preferences.FolderViewMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudViewModeTest {
  private fun file(path: String, directory: Boolean = false) = NetworkFile(path.substringAfterLast('/'), path, 100, directory)
  private val files = listOf(file("/anime", true), file("/anime/season", true),
    file("/anime/season/ep1.mkv"), file("/anime/movie.mp4"), file("/loose.mp4"),
    file("/images", true), file("/images/cover.jpg"), file("/song.flac"), file("/empty", true))

  @Test fun treeKeepsImmediateFoldersAndVideos() {
    assertEquals(listOf("/anime", "/loose.mp4", "/images", "/empty"),
      cloudFilesForViewMode(files, "/", FolderViewMode.FileManager).map { it.path })
  }
  @Test fun libraryIncludesDescendantVideosWithoutFoldersImagesOrAudio() {
    assertEquals(listOf("/anime/season/ep1.mkv", "/anime/movie.mp4", "/loose.mp4"),
      cloudFilesForViewMode(files, "/", FolderViewMode.MediaLibrary).map { it.path })
  }
  @Test fun folderModeFlattensVideoContainingAlbumsAndKeepsLooseVideos() {
    assertEquals(listOf("/anime", "/anime/season", "/loose.mp4"),
      cloudFilesForViewMode(files, "/", FolderViewMode.AlbumView).map { it.path })
  }
  @Test fun nestedLibraryDoesNotEscapeItsFolderOrMatchSimilarPrefixes() {
    assertEquals(listOf("/anime/season/ep1.mkv", "/anime/movie.mp4"),
      cloudFilesForViewMode(files + file("/anime-other/ep2.mp4"), "/anime", FolderViewMode.MediaLibrary).map { it.path })
  }
  @Test fun changingModesPreservesKnownMetadataAndStablePaths() {
    val video = file("/anime/season/ep1.mkv").copy(durationMs = 120_000, width = 1920, height = 1080)
    for (mode in FolderViewMode.entries) {
      assertEquals(video, cloudFilesForViewMode(listOf(video), "/anime/season", mode).single())
    }
  }
}
