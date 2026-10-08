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

    /** Reverses the list's own order; unrelated to duration. */
    const val REVERSE = "reverse"

    /**
     * 两个按时长排序的模式，命名按"实际行为"而不是 asc/desc：用户在界面上看到的措辞是
     * 「时长顺序播放」= 长→短、「时长倒序播放」= 短→长（用户 2026-10-08 指定）。
     * 别按名字里的"顺序/倒序"去纠正方向，否则会和用户要的相反。
     */
    const val DURATION_LONG_FIRST = "duration_long_first"
    const val DURATION_SHORT_FIRST = "duration_short_first"

    /** Picker order, i.e. the order the choices are listed in. */
    val ordered = listOf(SHUFFLE, SEQUENTIAL, REVERSE, DURATION_LONG_FIRST, DURATION_SHORT_FIRST)

    /** First-time value; keeps the plain list order so nothing surprises the user. */
    const val DEFAULT = SEQUENTIAL

    // 早期版本用过 direction 命名的持久化值（当时 asc=短→长），按行为等价迁移。
    private const val LEGACY_DURATION_ASC = "duration_asc"
    private const val LEGACY_DURATION_DESC = "duration_desc"

    fun normalize(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return when (trimmed) {
            SHUFFLE, REVERSE, DURATION_LONG_FIRST, DURATION_SHORT_FIRST -> trimmed

            LEGACY_DURATION_ASC -> DURATION_SHORT_FIRST

            LEGACY_DURATION_DESC -> DURATION_LONG_FIRST

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

            DURATION_LONG_FIRST -> byDuration(cards, shortFirst = false)

            DURATION_SHORT_FIRST -> byDuration(cards, shortFirst = true)

            else -> cards
        }
    }

    private fun byDuration(
        cards: List<VideoCard>,
        shortFirst: Boolean,
    ): List<VideoCard> {
        // Unknown duration (0) goes last in both directions so it can't masquerade as the
        // shortest or the longest video. sortedWith is stable, so ties keep the original order.
        val (known, unknown) = cards.partition { it.durationSec > 0 }
        val comparator = compareBy<VideoCard> { it.durationSec }
        val sorted = known.sortedWith(if (shortFirst) comparator else comparator.reversed())
        return sorted + unknown
    }
}
