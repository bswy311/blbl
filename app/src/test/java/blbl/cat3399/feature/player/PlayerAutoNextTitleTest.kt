package blbl.cat3399.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerAutoNextTitleTest {
    /** Long enough to be truncated whatever the limit is, as long as it stays sane. */
    private val longTitle = "0123456789".repeat(4)

    @Test
    fun keepsShortTitlesAfterWhitespaceNormalization() {
        assertEquals("第12话", formatAutoNextHintTitle("  第12话  ", fallbackTitle = "推荐视频"))
    }

    @Test
    fun collapsesInnerWhitespaceWithoutTruncating() {
        assertEquals("第12话 预告", formatAutoNextHintTitle("第12话\t\n预告", fallbackTitle = "推荐视频"))
    }

    @Test
    fun truncatesLongTitlesToTheConfiguredLimit() {
        val limit = PlayerActivity.AUTO_NEXT_TITLE_MAX_CHARS

        val formatted = formatAutoNextHintTitle(longTitle, fallbackTitle = "推荐视频")

        assertEquals(longTitle.take(limit) + "...", formatted)
        assertEquals(limit + 3, formatted.length)
    }

    @Test
    fun truncation_countsCodePointsSoSurrogatePairsAreNotSplit() {
        val limit = PlayerActivity.AUTO_NEXT_TITLE_MAX_CHARS
        val emojiTitle = "🎬".repeat(limit + 5)

        assertEquals("🎬".repeat(limit) + "...", formatAutoNextHintTitle(emojiTitle, fallbackTitle = "推荐视频"))
    }

    @Test
    fun fallsBackWhenTitleIsBlank() {
        assertEquals("推荐视频", formatAutoNextHintTitle("  \n\t  ", fallbackTitle = "推荐视频"))
    }
}
