package blbl.cat3399.core.prefs

/**
 * `bvid → 时间戳` 小表的通用维护逻辑（本机功能记录用，不做服务端同步）。
 */
internal object BvidMarks {
    /** 记一条并裁掉过期/超量的项；[maxEntries] 超出时保留最新的。 */
    fun mark(
        existing: Map<String, Long>,
        bvid: String,
        nowSec: Long,
        maxAgeSec: Long,
        maxEntries: Int,
    ): Map<String, Long> {
        val key = bvid.trim()
        if (key.isBlank()) return existing
        val merged = LinkedHashMap<String, Long>(existing.size + 1)
        merged.putAll(existing)
        merged[key] = nowSec
        val cutoff = nowSec - maxAgeSec
        val fresh = merged.filterValues { it > cutoff }
        if (fresh.size <= maxEntries) return fresh
        return fresh.entries
            .sortedByDescending { it.value }
            .take(maxEntries)
            .associate { it.key to it.value }
    }

    fun remove(
        existing: Map<String, Long>,
        bvid: String,
    ): Map<String, Long> {
        val key = bvid.trim()
        if (key.isBlank() || !existing.containsKey(key)) return existing
        return existing.filterKeys { it != key }
    }
}
