package blbl.cat3399.core.prefs

import blbl.cat3399.core.model.VideoCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class ToViewPlayAllOrderTest {
    private fun card(
        bvid: String,
        durationSec: Int,
    ): VideoCard =
        VideoCard(
            bvid = bvid,
            cid = 1L,
            title = bvid,
            coverUrl = "",
            durationSec = durationSec,
            ownerName = "",
            ownerFace = null,
            view = null,
            danmaku = null,
            pubDate = null,
            pubDateText = null,
        )

    private fun ids(cards: List<VideoCard>): List<String> = cards.map { it.bvid }

    @Test
    fun normalize_should_fall_back_to_sequential() {
        assertEquals(ToViewPlayAllOrder.SEQUENTIAL, ToViewPlayAllOrder.normalize(null))
        assertEquals(ToViewPlayAllOrder.SEQUENTIAL, ToViewPlayAllOrder.normalize(""))
        assertEquals(ToViewPlayAllOrder.SEQUENTIAL, ToViewPlayAllOrder.normalize("  "))
        assertEquals(ToViewPlayAllOrder.SEQUENTIAL, ToViewPlayAllOrder.normalize("nonsense"))
    }

    @Test
    fun normalize_should_accept_known_orders_and_trim() {
        assertEquals(ToViewPlayAllOrder.REVERSE, ToViewPlayAllOrder.normalize(" reverse "))
        assertEquals(ToViewPlayAllOrder.SHUFFLE, ToViewPlayAllOrder.normalize("shuffle"))
        assertEquals(ToViewPlayAllOrder.DURATION_ASC, ToViewPlayAllOrder.normalize("duration_asc"))
    }

    @Test
    fun apply_sequential_should_keep_input_order() {
        val cards = listOf(card("a", 30), card("b", 10), card("c", 20))

        assertEquals(listOf("a", "b", "c"), ids(ToViewPlayAllOrder.apply(cards, ToViewPlayAllOrder.SEQUENTIAL)))
    }

    @Test
    fun apply_reverse_should_flip_order() {
        val cards = listOf(card("a", 30), card("b", 10), card("c", 20))

        assertEquals(listOf("c", "b", "a"), ids(ToViewPlayAllOrder.apply(cards, ToViewPlayAllOrder.REVERSE)))
    }

    @Test
    fun apply_duration_asc_should_sort_ascending() {
        val cards = listOf(card("a", 300), card("b", 10), card("c", 120))

        assertEquals(listOf("b", "c", "a"), ids(ToViewPlayAllOrder.apply(cards, ToViewPlayAllOrder.DURATION_ASC)))
    }

    @Test
    fun apply_duration_asc_should_push_unknown_durations_last_and_keep_them_stable() {
        val cards = listOf(card("unknown1", 0), card("long", 500), card("unknown2", 0), card("short", 20))

        assertEquals(
            listOf("short", "long", "unknown1", "unknown2"),
            ids(ToViewPlayAllOrder.apply(cards, ToViewPlayAllOrder.DURATION_ASC)),
        )
    }

    @Test
    fun apply_shuffle_should_keep_every_card() {
        val cards = listOf(card("a", 1), card("b", 2), card("c", 3), card("d", 4), card("e", 5))

        val shuffled = ToViewPlayAllOrder.apply(cards, ToViewPlayAllOrder.SHUFFLE, Random(42))

        assertEquals(cards.size, shuffled.size)
        assertEquals(ids(cards).toSet(), ids(shuffled).toSet())
        assertNotEquals(ids(cards), ids(shuffled))
    }

    @Test
    fun apply_shuffle_should_be_deterministic_for_a_seeded_random() {
        val cards = listOf(card("a", 1), card("b", 2), card("c", 3), card("d", 4), card("e", 5))

        val first = ids(ToViewPlayAllOrder.apply(cards, ToViewPlayAllOrder.SHUFFLE, Random(7)))
        val second = ids(ToViewPlayAllOrder.apply(cards, ToViewPlayAllOrder.SHUFFLE, Random(7)))

        assertEquals(first, second)
    }

    @Test
    fun apply_should_return_input_untouched_for_one_or_zero_cards() {
        val single = listOf(card("a", 10))

        assertEquals(single, ToViewPlayAllOrder.apply(single, ToViewPlayAllOrder.REVERSE))
        assertEquals(single, ToViewPlayAllOrder.apply(single, ToViewPlayAllOrder.SHUFFLE))
        assertEquals(emptyList<VideoCard>(), ToViewPlayAllOrder.apply(emptyList(), ToViewPlayAllOrder.SHUFFLE))
    }

    @Test
    fun ordered_should_start_with_the_default_sequential() {
        assertEquals(ToViewPlayAllOrder.SEQUENTIAL, ToViewPlayAllOrder.ordered.first())
        assertEquals(4, ToViewPlayAllOrder.ordered.size)
    }
}
