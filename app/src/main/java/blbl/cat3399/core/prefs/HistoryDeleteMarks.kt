package blbl.cat3399.core.prefs

/**
 * 本机「删除观看记录」的标记（bvid → 删除时刻，秒）。
 *
 * 「最近播放」排序读的是服务端观看历史，但服务端删除不保证立刻从 `history/cursor` 里消失
 * （也可能被紧接着的一次上报重新写回），于是删完记录再点「播放全部」，那个视频还会排在最前。
 * 这里再记一份本机标记：历史里的 view_at 不晚于删除时刻时，就当它"没有播放记录"。
 * 用户之后确实又看了（view_at 变新）就自然恢复，不影响后续排序。
 */
internal object HistoryDeleteMarks {
    /** 容忍本机与服务端的秒级时钟误差，也顺带压掉删除后紧跟着的上报。 */
    private const val GRACE_SEC = 60L

    private const val MAX_AGE_SEC = 30L * 24 * 60 * 60

    private const val MAX_ENTRIES = 300

    fun mark(
        existing: Map<String, Long>,
        bvid: String,
        nowSec: Long,
    ): Map<String, Long> {
        val key = bvid.trim()
        if (key.isBlank()) return existing
        val merged = LinkedHashMap<String, Long>(existing.size + 1)
        merged.putAll(existing)
        merged[key] = nowSec
        val cutoff = nowSec - MAX_AGE_SEC
        val fresh = merged.filterValues { it > cutoff }
        if (fresh.size <= MAX_ENTRIES) return fresh
        return fresh.entries
            .sortedByDescending { it.value }
            .take(MAX_ENTRIES)
            .associate { it.key to it.value }
    }

    /** 丢掉"已删除但服务端历史还没跟上"的记录；没被删过、或删除后重新看过的保持原样。 */
    fun withoutDeleted(
        viewAtByBvid: Map<String, Long>,
        deletedAtByBvid: Map<String, Long>,
    ): Map<String, Long> {
        if (viewAtByBvid.isEmpty() || deletedAtByBvid.isEmpty()) return viewAtByBvid
        return viewAtByBvid.filterKeys { bvid ->
            val deletedAt = deletedAtByBvid[bvid] ?: return@filterKeys true
            viewAtByBvid.getValue(bvid) > deletedAt + GRACE_SEC
        }
    }
}
