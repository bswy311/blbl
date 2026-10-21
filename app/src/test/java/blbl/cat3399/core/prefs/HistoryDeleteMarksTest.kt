package blbl.cat3399.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDeleteMarksTest {
    @Test
    fun withoutDeleted_dropsRecordsOlderThanDeletion() {
        val viewAt = mapOf("a" to 1_000L, "b" to 2_000L)

        val filtered = HistoryDeleteMarks.withoutDeleted(viewAt, mapOf("a" to 1_500L))

        assertEquals(mapOf("b" to 2_000L), filtered)
    }

    @Test
    fun withoutDeleted_keepsRecordWhenWatchedAgainAfterDeletion() {
        val viewAt = mapOf("a" to 9_000L)

        val filtered = HistoryDeleteMarks.withoutDeleted(viewAt, mapOf("a" to 1_500L))

        assertEquals(mapOf("a" to 9_000L), filtered)
    }

    @Test
    fun withoutDeleted_keepsRecentReportWithinGraceWindow() {
        // 删除后紧跟着的一次上报（同一秒）也要当成没看过。
        val viewAt = mapOf("a" to 1_010L)

        val filtered = HistoryDeleteMarks.withoutDeleted(viewAt, mapOf("a" to 1_000L))

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun withoutDeleted_returnsInputWhenNoMarks() {
        val viewAt = mapOf("a" to 1_000L)

        assertEquals(viewAt, HistoryDeleteMarks.withoutDeleted(viewAt, emptyMap()))
        assertEquals(viewAt, HistoryDeleteMarks.withoutDeleted(viewAt, mapOf("b" to 5_000L)))
    }

    @Test
    fun mark_recordsTimestampAndOverwritesPrevious() {
        val marked = HistoryDeleteMarks.mark(mapOf("a" to 100L), "a", 900L)

        assertEquals(mapOf("a" to 900L), marked)
    }

    @Test
    fun mark_prunesExpiredEntries() {
        val old = 1_000_000L
        val now = old + 31L * 24 * 60 * 60

        val marked = HistoryDeleteMarks.mark(mapOf("old" to old, "kept" to now - 10L), "fresh", now)

        assertEquals(setOf("kept", "fresh"), marked.keys)
    }

    @Test
    fun mark_capsEntryCountKeepingNewest() {
        val now = 5_000L
        val existing = (0 until 400).associate { "v$it" to now - it }

        val marked = HistoryDeleteMarks.mark(existing, "newest", now)

        assertEquals(300, marked.size)
        assertEquals(now, marked["newest"])
        assertTrue(marked.values.min() > now - 400)
    }

    @Test
    fun mark_ignoresBlankBvid() {
        val existing = mapOf("a" to 1L)

        assertEquals(existing, HistoryDeleteMarks.mark(existing, "  ", 900L))
    }
}
