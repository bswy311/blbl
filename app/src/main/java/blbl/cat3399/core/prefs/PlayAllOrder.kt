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

    /**
     * 按"最近播放"排序：**有观看进度（没看完）的优先**，其次按最近观看时间从近到远，
     * 没有观看记录的排最后。时间来自服务端观看历史，需要联网取，所以只在稍后再看里提供
     * （见 [orderedWithRecentPlay]）。
     */
    const val RECENT_PLAY = "recent_play"

    /** Picker order, i.e. the order the choices are listed in. */
    val ordered = listOf(SHUFFLE, SEQUENTIAL, REVERSE, DURATION_LONG_FIRST, DURATION_SHORT_FIRST)

    /**
     * 稍后再看用：比 [ordered] 多一个「最近播放」，并且**放在最前面**（用户 2026-10-10 指定）。
     * 收藏夹不提供它（要多拉历史请求）。
     */
    val orderedWithRecentPlay = listOf(RECENT_PLAY) + ordered

    /** First-time value; keeps the plain list order so nothing surprises the user. */
    const val DEFAULT = SEQUENTIAL

    // 早期版本用过 direction 命名的持久化值（当时 asc=短→长），按行为等价迁移。
    private const val LEGACY_DURATION_ASC = "duration_asc"
    private const val LEGACY_DURATION_DESC = "duration_desc"

    fun normalize(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return when (trimmed) {
            SHUFFLE, REVERSE, DURATION_LONG_FIRST, DURATION_SHORT_FIRST, RECENT_PLAY -> trimmed

            LEGACY_DURATION_ASC -> DURATION_SHORT_FIRST

            LEGACY_DURATION_DESC -> DURATION_LONG_FIRST

            else -> DEFAULT
        }
    }

    /**
     * @param recentViewAtByBvid 最近一次观看时间（秒），来自服务端观看历史；只在 [RECENT_PLAY]
     *   下使用。里没有的视频视为"没播放记录"，排到最后并按原顺序保持稳定。
     */
    fun apply(
        cards: List<VideoCard>,
        order: String,
        random: Random = Random.Default,
        recentViewAtByBvid: Map<String, Long> = emptyMap(),
    ): List<VideoCard> {
        if (cards.size <= 1) return cards
        return when (normalize(order)) {
            REVERSE -> cards.reversed()

            SHUFFLE -> cards.shuffled(random)

            DURATION_LONG_FIRST -> byDuration(cards, shortFirst = false)

            DURATION_SHORT_FIRST -> byDuration(cards, shortFirst = true)

            RECENT_PLAY -> byRecentPlay(cards, recentViewAtByBvid)

            else -> cards
        }
    }

    private fun byRecentPlay(
        cards: List<VideoCard>,
        viewAtByBvid: Map<String, Long>,
    ): List<VideoCard> {
        if (viewAtByBvid.isEmpty()) return cards
        // 有进度的（看了一半没看完）排最前，接着是看过但没进度的（已看完/进度已清），
        // 没有播放记录的排最后；每组内按最近观看时间倒序，稳定排序保证并列时保持原顺序。
        val (withProgress, rest) = cards.partition { it.hasUnfinishedProgress() }
        val (played, unplayed) = rest.partition { (viewAtByBvid[it.bvid] ?: 0L) > 0L }
        return withProgress.sortedByDescending { viewAtByBvid[it.bvid] ?: 0L } +
            played.sortedByDescending { viewAtByBvid.getValue(it.bvid) } +
            unplayed
    }

    /** 看了一半、还没看完（"已看完"不算，因为有进度条才值得优先续播）。 */
    private fun VideoCard.hasUnfinishedProgress(): Boolean {
        val progress = progressSec ?: return false
        if (progress <= 0L || progressFinished) return false
        return durationSec <= 0 || progress < durationSec
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
