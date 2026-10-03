package app.gyrolet.mpvrx.utils.sort

import org.junit.Assert.*
import org.junit.Test

class NaturalOrderComparatorTest {
  private val comparator = SortUtils.NaturalOrderComparator.DEFAULT
  @Test fun numericChunksDoNotOverflow() {
    val values = listOf("第999999999999999999999集", "第1000000000000000000000集", "第10集", "第2集")
    assertEquals(listOf(values[3], values[2], values[0], values[1]), values.sortedWith(comparator))
  }
  @Test fun preservesYumeLeadingZerosCaseAndPunctuation() {
    assertTrue(comparator.compare("EP2", "ep02") < 0)
    assertEquals(0, comparator.compare("ABC2", "abc2"))
    assertTrue(comparator.compare("a b", "ab") < 0)
    assertTrue(comparator.compare("片段[2]", "片段[10]") < 0)
  }
  @Test fun comparatorIsAntisymmetricAndTransitive() {
    val values = listOf("", "a", "A", "a 2", "a2", "a02", "a10", "a2147483648", "a99999999999999999999", "视频2", "视频10", "[2]")
    for (a in values) for (b in values) {
      assertEquals(-comparator.compare(b, a).sign(), comparator.compare(a, b).sign())
      for (c in values) if (comparator.compare(a, b) <= 0 && comparator.compare(b, c) <= 0) assertTrue(comparator.compare(a, c) <= 0)
    }
  }
  private fun Int.sign() = compareTo(0)
}
