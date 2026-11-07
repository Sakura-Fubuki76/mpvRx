package app.gyrolet.mpvrx.domain.network

import org.junit.Assert.*
import org.junit.Test

class DlnaPathTest {
  @Test fun rootIsContentDirectoryRootRatherThanDescriptionPath() {
    assertEquals("0", DlnaPath.objectId(NetworkPath.from("/")))
  }

  @Test fun opaqueIdsRoundTripWithoutBecomingFilePaths() {
    for (id in listOf("1/2:track?x=#%", "番剧 & 音楽", "../not-a-path", "id~repeat")) {
      val path = DlnaPath.child(NetworkPath.from("/"), id, 'v')
      assertEquals(1, path.segments.size)
      assertEquals(id, DlnaPath.objectId(path))
    }
  }

  @Test fun duplicatesKeepDistinctStablePathsButResolveTheSameObject() {
    val album = DlnaPath.child(NetworkPath.from("/"), "album", 'o')
    val first = DlnaPath.child(album, "track", 'a')
    val repeat = DlnaPath.child(album, "track", 'a', 1)
    assertNotEquals(first, repeat)
    assertEquals(DlnaPath.objectId(first), DlnaPath.objectId(repeat))
    assertTrue(DlnaPath.isOrderedContainer(album))
    assertTrue(DlnaPath.isAudio(repeat))
    assertFalse(DlnaPath.isOrderedContainer(repeat))
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsFilesystemNamesAsObjectReferences() {
    DlnaPath.objectId(NetworkPath.from("/movie.mkv"))
  }
}
