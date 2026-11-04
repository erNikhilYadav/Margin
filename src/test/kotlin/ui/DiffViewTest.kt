package margin.ui

import margin.git.DiffLine
import margin.git.LineType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffViewTest {
    private val hunk = DiffLine(LineType.Hunk, "@@ -1,7 +1,7 @@", null, null)
    private fun ctx(o: Int, n: Int) = DiffLine(LineType.Context, "c$o", o, n)
    private fun del(o: Int) = DiffLine(LineType.Removed, "r$o", o, null)
    private fun add(n: Int) = DiffLine(LineType.Added, "a$n", null, n)

    // hunk, ctx, [-2 -3 +2], ctx, [+4 +5], ctx, [-6], hunk, [-20 +20]
    private val hunk2 = DiffLine(LineType.Hunk, "@@ -20 +20 @@", null, null)
    private val lines = listOf(
        hunk, ctx(1, 1), del(2), del(3), add(2), ctx(4, 3), add(4), add(5), ctx(5, 6), del(6),
        hunk2, del(20), add(20),
    )

    @Test fun pairsRemovedWithAddedAndFillsTheShortSide() {
        val rows = sideBySide(lines)
        assertEquals(
            listOf(
                SideBySideRow(hunk, hunk),
                SideBySideRow(ctx(1, 1), ctx(1, 1)),
                SideBySideRow(del(2), add(2)),
                SideBySideRow(del(3), null),
                SideBySideRow(ctx(4, 3), ctx(4, 3)),
                SideBySideRow(null, add(4)),
                SideBySideRow(null, add(5)),
                SideBySideRow(ctx(5, 6), ctx(5, 6)),
                SideBySideRow(del(6), null),
                SideBySideRow(hunk2, hunk2),
                SideBySideRow(del(20), add(20)),
            ),
            rows,
        )
        assertTrue(sideBySide(emptyList()).isEmpty())
        // an added line before a removed one in the same block still pairs up
        assertEquals(listOf(SideBySideRow(del(1), add(1))), sideBySide(listOf(add(1), del(1))))
    }

    @Test fun changeBlocksInBothRowSpaces() {
        assertEquals(listOf(2..4, 6..7, 9..9, 11..12), unifiedBlocks(lines))
        assertEquals(listOf(2..3, 5..6, 8..8, 10..10), sideBySideBlocks(sideBySide(lines)))
        assertEquals(listOf(0..1), unifiedBlocks(listOf(add(1), add(2)))) // starts and ends at the edges
        assertTrue(unifiedBlocks(listOf(hunk, ctx(1, 1))).isEmpty())
        assertTrue(sideBySideBlocks(emptyList()).isEmpty())
    }
}
