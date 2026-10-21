package blbl.cat3399.core.prefs

import blbl.cat3399.core.model.VideoCard

/**
 * 本机「清除观看进度」的标记（bvid → 清除时刻秒）。
 *
 * B站没有"只清进度、保留历史记录"的接口，所以这个清除只在本 App 的稍后再看列表里生效：
 * 被标记的卡片不再显示进度条／「已看完」，点开播放从 0 开始；点开时顺手删掉标记，
 * 之后重新按服务端进度显示。服务端的观看记录不受影响。
 */
internal object ProgressClearedMarks {
    private const val MAX_AGE_SEC = 90L * 24 * 60 * 60

    private const val MAX_ENTRIES = 300

    fun mark(
        existing: Map<String, Long>,
        bvid: String,
        nowSec: Long,
    ): Map<String, Long> = BvidMarks.mark(existing, bvid, nowSec, MAX_AGE_SEC, MAX_ENTRIES)

    fun remove(
        existing: Map<String, Long>,
        bvid: String,
    ): Map<String, Long> = BvidMarks.remove(existing, bvid)

    /** 把标记过的卡片当成"没看过"（不显示进度条/已看完）。 */
    fun hideProgress(
        cards: List<VideoCard>,
        clearedAtByBvid: Map<String, Long>,
    ): List<VideoCard> {
        if (cards.isEmpty() || clearedAtByBvid.isEmpty()) return cards
        return cards.map { card ->
            if (clearedAtByBvid.containsKey(card.bvid)) {
                card.copy(progressSec = null, progressFinished = false)
            } else {
                card
            }
        }
    }
}
