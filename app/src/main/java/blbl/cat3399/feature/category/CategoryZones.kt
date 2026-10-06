package blbl.cat3399.feature.category

import blbl.cat3399.core.model.Zone
import blbl.cat3399.core.prefs.AppPrefs

object CategoryZones {
    const val KEY_ALL = "all"
    private const val KEY_ZONE_PREFIX = "rank:"

    val defaultZones: List<Zone> =
        listOf(
            Zone("全站", null),
            Zone("动画", 1005),
            Zone("音乐", 1003),
            Zone("舞蹈", 1004),
            Zone("游戏", 1008),
            Zone("知识", 1010),
            Zone("科技", 1012),
            Zone("运动", 1018),
            Zone("汽车", 1013),
            Zone("美食", 1020),
            Zone("动物", 1024),
            Zone("鬼畜", 1007),
            Zone("时尚", 1014),
            Zone("娱乐", 1002),
            Zone("影视", 1001),
        )

    private val legacyTidToRankRid: Map<Int, Int> =
        mapOf(
            1 to 1005,
            3 to 1003,
            129 to 1004,
            4 to 1008,
            36 to 1010,
            188 to 1012,
            234 to 1018,
            223 to 1013,
            211 to 1020,
            217 to 1024,
            119 to 1007,
            155 to 1014,
            5 to 1002,
            181 to 1001,
        )

    fun findByRid(rid: Int): Zone? = defaultZones.firstOrNull { it.rid == rid }

    fun rankRidForLegacyTid(tid: Int): Int? = legacyTidToRankRid[tid]

    fun findAll(): Zone? = defaultZones.firstOrNull { it.rid == null }

    fun stableKeyFor(zone: Zone): String = zone.rid?.let { KEY_ZONE_PREFIX + it } ?: KEY_ALL

    fun visibleZones(prefs: AppPrefs): List<Zone> = orderedZones(prefs).filter { isZoneVisible(it, prefs) }

    /** All zones in the order the user arranged, hidden ones included. */
    fun orderedZones(prefs: AppPrefs): List<Zone> {
        val saved = prefs.mainCategoryTabOrder
        if (saved.isEmpty()) return defaultOrderWithVisibleTabsFirst(prefs)
        val byKey = defaultZones.associateBy { stableKeyFor(it) }
        val out = ArrayList<Zone>(saved.size)
        val seen = HashSet<String>(saved.size * 2)
        for (raw in saved) {
            val key = raw.trim()
            if (!seen.add(key)) continue
            byKey[key]?.let { out += it }
        }
        return out + defaultZones.filter { stableKeyFor(it) !in seen }
    }

    /** Visibility is stored as a key list; an empty list means "show every zone". */
    fun isZoneVisible(
        zone: Zone,
        prefs: AppPrefs,
    ): Boolean {
        val keys = prefs.mainCategoryVisibleTabs
        return keys.isEmpty() || stableKeyFor(zone) in keys
    }

    /**
     * Order used before the user ever reordered anything: the (possibly ordered) visible tabs come
     * first so existing selections keep their order, the rest follow in default order.
     */
    private fun defaultOrderWithVisibleTabsFirst(prefs: AppPrefs): List<Zone> {
        val visible = orderZonesByKeys(defaultZones, prefs.mainCategoryVisibleTabs)
        val shown = visible.mapTo(HashSet()) { stableKeyFor(it) }
        return visible + defaultZones.filter { stableKeyFor(it) !in shown }
    }

    /**
     * Applies a user-defined order: zones follow [keys] and keys that no longer match a zone are
     * dropped.
     */
    internal fun orderZonesByKeys(
        zones: List<Zone>,
        keys: List<String>,
    ): List<Zone> {
        if (keys.isEmpty()) return emptyList()
        val byKey = zones.associateBy { stableKeyFor(it) }
        val out = ArrayList<Zone>(keys.size)
        val seen = HashSet<String>(keys.size * 2)
        for (raw in keys) {
            val key = raw.trim()
            if (!seen.add(key)) continue
            byKey[key]?.let { out += it }
        }
        return out
    }
}
