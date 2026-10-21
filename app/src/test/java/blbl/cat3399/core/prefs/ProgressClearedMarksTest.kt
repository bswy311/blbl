package blbl.cat3399.core.prefs

import blbl.cat3399.core.model.VideoCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressClearedMarksTest {
    private fun card(
        bvid: String,
        progressSec: Long? = null,
        progressFinished: Boolean = false,
    ): VideoCard =
        VideoCard(
            bvid = bvid,
            cid = 1L,
            title = bvid,
            coverUrl = "",
            durationSec = 100,
            ownerName = "",
            ownerFace = null,
            view = null,
            danmaku = null,
            pubDate = null,
            pubDateText = null,
            progressSec = progressSec,
            progressFinished = progressFinished,
        )

    @Test
    fun hideProgress_clearsMarkedCardOnly() {
        val cards = listOf(card("a", progressSec = 30L), card("b", progressSec = 40L))

        val result = ProgressClearedMarks.hideProgress(cards, mapOf("a" to 1L))

        assertNull(result[0].progressSec)
        assertEquals(40L, result[1].progressSec)
    }

    @Test
    fun hideProgress_alsoClearsFinishedFlag() {
        val cards = listOf(card("a", progressFinished = true))

        val result = ProgressClearedMarks.hideProgress(cards, mapOf("a" to 1L))

        assertEquals(false, result[0].progressFinished)
        assertNull(result[0].progressSec)
    }

    @Test
    fun hideProgress_returnsInputWhenNothingMarked() {
        val cards = listOf(card("a", progressSec = 30L))

        assertSame(cards, ProgressClearedMarks.hideProgress(cards, emptyMap()))
        assertEquals(cards, ProgressClearedMarks.hideProgress(cards, mapOf("other" to 1L)))
    }

    @Test
    fun mark_keepsNewestEntryAndOverwrites() {
        val marked = ProgressClearedMarks.mark(mapOf("a" to 10L), "a", 900L)

        assertEquals(mapOf("a" to 900L), marked)
        assertEquals(mapOf("a" to 10L, "b" to 900L), ProgressClearedMarks.mark(mapOf("a" to 10L), "b", 900L))
    }

    @Test
    fun mark_dropsEntriesOlderThanNinetyDays() {
        val now = 20_000_000L
        val tooOld = now - 91L * 24 * 60 * 60

        val marked = ProgressClearedMarks.mark(mapOf("old" to tooOld, "kept" to now - 10L), "fresh", now)

        assertEquals(setOf("kept", "fresh"), marked.keys)
    }

    @Test
    fun remove_dropsOnlyThatBvid() {
        val remaining = ProgressClearedMarks.remove(mapOf("a" to 1L, "b" to 2L), "a")

        assertEquals(mapOf("b" to 2L), remaining)
        assertTrue(ProgressClearedMarks.remove(mapOf("a" to 1L), "   ").containsKey("a"))
    }
}
