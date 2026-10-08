package blbl.cat3399.core.prefs

import blbl.cat3399.core.model.VideoCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class PlayAllOrderTest {
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
        assertEquals(PlayAllOrder.SEQUENTIAL, PlayAllOrder.normalize(null))
        assertEquals(PlayAllOrder.SEQUENTIAL, PlayAllOrder.normalize(""))
        assertEquals(PlayAllOrder.SEQUENTIAL, PlayAllOrder.normalize("  "))
        assertEquals(PlayAllOrder.SEQUENTIAL, PlayAllOrder.normalize("nonsense"))
    }

    @Test
    fun normalize_should_accept_known_orders_and_trim() {
        assertEquals(PlayAllOrder.REVERSE, PlayAllOrder.normalize(" reverse "))
        assertEquals(PlayAllOrder.SHUFFLE, PlayAllOrder.normalize("shuffle"))
        assertEquals(PlayAllOrder.DURATION_LONG_FIRST, PlayAllOrder.normalize("duration_long_first"))
        assertEquals(PlayAllOrder.DURATION_SHORT_FIRST, PlayAllOrder.normalize("duration_short_first"))
    }

    @Test
    fun normalize_should_migrate_legacy_duration_values_by_behaviour() {
        // 旧值当时 asc=短→长、desc=长→短；按"行为等价"迁移，别让用户已选的值变成默认值。
        assertEquals(PlayAllOrder.DURATION_SHORT_FIRST, PlayAllOrder.normalize("duration_asc"))
        assertEquals(PlayAllOrder.DURATION_LONG_FIRST, PlayAllOrder.normalize("duration_desc"))
    }

    @Test
    fun apply_sequential_should_keep_input_order() {
        val cards = listOf(card("a", 30), card("b", 10), card("c", 20))

        assertEquals(listOf("a", "b", "c"), ids(PlayAllOrder.apply(cards, PlayAllOrder.SEQUENTIAL)))
    }

    @Test
    fun apply_reverse_should_flip_order() {
        val cards = listOf(card("a", 30), card("b", 10), card("c", 20))

        assertEquals(listOf("c", "b", "a"), ids(PlayAllOrder.apply(cards, PlayAllOrder.REVERSE)))
    }

    @Test
    fun apply_duration_short_first_should_sort_ascending() {
        val cards = listOf(card("a", 300), card("b", 10), card("c", 120))

        assertEquals(listOf("b", "c", "a"), ids(PlayAllOrder.apply(cards, PlayAllOrder.DURATION_SHORT_FIRST)))
    }

    @Test
    fun apply_duration_long_first_should_sort_descending() {
        val cards = listOf(card("a", 300), card("b", 10), card("c", 120))

        assertEquals(listOf("a", "c", "b"), ids(PlayAllOrder.apply(cards, PlayAllOrder.DURATION_LONG_FIRST)))
    }

    @Test
    fun apply_duration_short_first_should_push_unknown_durations_last_and_keep_them_stable() {
        val cards = listOf(card("unknown1", 0), card("long", 500), card("unknown2", 0), card("short", 20))

        assertEquals(
            listOf("short", "long", "unknown1", "unknown2"),
            ids(PlayAllOrder.apply(cards, PlayAllOrder.DURATION_SHORT_FIRST)),
        )
    }

    @Test
    fun apply_duration_long_first_should_push_unknown_durations_last_and_keep_them_stable() {
        val cards = listOf(card("unknown1", 0), card("long", 500), card("unknown2", 0), card("short", 20))

        assertEquals(
            listOf("long", "short", "unknown1", "unknown2"),
            ids(PlayAllOrder.apply(cards, PlayAllOrder.DURATION_LONG_FIRST)),
        )
    }

    @Test
    fun apply_shuffle_should_keep_every_card() {
        val cards = listOf(card("a", 1), card("b", 2), card("c", 3), card("d", 4), card("e", 5))

        val shuffled = PlayAllOrder.apply(cards, PlayAllOrder.SHUFFLE, Random(42))

        assertEquals(cards.size, shuffled.size)
        assertEquals(ids(cards).toSet(), ids(shuffled).toSet())
        assertNotEquals(ids(cards), ids(shuffled))
    }

    @Test
    fun apply_shuffle_should_be_deterministic_for_a_seeded_random() {
        val cards = listOf(card("a", 1), card("b", 2), card("c", 3), card("d", 4), card("e", 5))

        val first = ids(PlayAllOrder.apply(cards, PlayAllOrder.SHUFFLE, Random(7)))
        val second = ids(PlayAllOrder.apply(cards, PlayAllOrder.SHUFFLE, Random(7)))

        assertEquals(first, second)
    }

    @Test
    fun apply_should_return_input_untouched_for_one_or_zero_cards() {
        val single = listOf(card("a", 10))

        assertEquals(single, PlayAllOrder.apply(single, PlayAllOrder.REVERSE))
        assertEquals(single, PlayAllOrder.apply(single, PlayAllOrder.SHUFFLE))
        assertEquals(single, PlayAllOrder.apply(single, PlayAllOrder.DURATION_LONG_FIRST))
        assertEquals(emptyList<VideoCard>(), PlayAllOrder.apply(emptyList(), PlayAllOrder.SHUFFLE))
    }

    @Test
    fun ordered_should_list_every_choice_and_include_the_default() {
        assertEquals(
            listOf(
                PlayAllOrder.SHUFFLE,
                PlayAllOrder.SEQUENTIAL,
                PlayAllOrder.REVERSE,
                PlayAllOrder.DURATION_LONG_FIRST,
                PlayAllOrder.DURATION_SHORT_FIRST,
            ),
            PlayAllOrder.ordered,
        )
        assertEquals(PlayAllOrder.SEQUENTIAL, PlayAllOrder.DEFAULT)
    }
}
