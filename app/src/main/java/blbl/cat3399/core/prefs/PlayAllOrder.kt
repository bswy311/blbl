package blbl.cat3399.core.prefs

import blbl.cat3399.core.model.VideoCard
import kotlin.random.Random

/**
 * Ordering strategies for the "播放全部" entry. It is shared by 稍后再看 and by each 收藏夹, which
 * remember their own choice separately (see [AppPrefs.favFolderPlayAllOrder]).
 *
 * The player walks the playlist strictly in the order it was stored (see
 * `PlayerActivityAutoNext.resolvePageAutoNextTarget`), so reordering is done here, before the
 * list is handed to the player.
 */
internal object PlayAllOrder {
    const val SHUFFLE = "shuffle"
    const val SEQUENTIAL = "sequential"
    const val REVERSE = "reverse"
    const val DURATION_ASC = "duration_asc"
    const val DURATION_DESC = "duration_desc"

    /** Picker order, i.e. the order the choices are listed in. */
    val ordered = listOf(SHUFFLE, SEQUENTIAL, REVERSE, DURATION_ASC, DURATION_DESC)

    /** First-time value; keeps the plain list order so nothing surprises the user. */
    const val DEFAULT = SEQUENTIAL

    fun normalize(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return when (trimmed) {
            SHUFFLE, REVERSE, DURATION_ASC, DURATION_DESC -> trimmed
            else -> DEFAULT
        }
    }

    fun apply(
        cards: List<VideoCard>,
        order: String,
        random: Random = Random.Default,
    ): List<VideoCard> {
        if (cards.size <= 1) return cards
        return when (normalize(order)) {
            REVERSE -> cards.reversed()

            SHUFFLE -> cards.shuffled(random)

            DURATION_ASC -> byDuration(cards, ascending = true)

            DURATION_DESC -> byDuration(cards, ascending = false)

            else -> cards
        }
    }

    private fun byDuration(
        cards: List<VideoCard>,
        ascending: Boolean,
    ): List<VideoCard> {
        // Unknown duration (0) goes last in both directions so it can't masquerade as the
        // shortest or the longest video. sortedWith is stable, so ties keep the original order.
        val (known, unknown) = cards.partition { it.durationSec > 0 }
        val comparator = compareBy<VideoCard> { it.durationSec }
        val sorted = known.sortedWith(if (ascending) comparator else comparator.reversed())
        return sorted + unknown
    }
}
