package blbl.cat3399.core.prefs

import blbl.cat3399.core.model.VideoCard
import kotlin.random.Random

/**
 * Ordering strategies for "播放全部" on the 稍后再看 page.
 *
 * The player walks the playlist strictly in the order it was stored (see
 * `PlayerActivityAutoNext.resolvePageAutoNextTarget`), so reordering is done here, before the
 * list is handed to the player.
 */
internal object ToViewPlayAllOrder {
    const val SEQUENTIAL = "sequential"
    const val DURATION_ASC = "duration_asc"
    const val REVERSE = "reverse"
    const val SHUFFLE = "shuffle"

    /** Picker order. First entry is also the default. */
    val ordered = listOf(SEQUENTIAL, DURATION_ASC, REVERSE, SHUFFLE)

    fun normalize(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return when (trimmed) {
            DURATION_ASC, REVERSE, SHUFFLE -> trimmed
            else -> SEQUENTIAL
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

            DURATION_ASC -> {
                // Unknown duration (0) sorts to the end so it can't masquerade as the shortest.
                val (known, unknown) = cards.partition { it.durationSec > 0 }
                known.sortedBy { it.durationSec } + unknown
            }

            else -> cards
        }
    }
}
