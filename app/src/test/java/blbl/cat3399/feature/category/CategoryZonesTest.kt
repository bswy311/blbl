package blbl.cat3399.feature.category

import blbl.cat3399.core.model.Zone
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryZonesTest {
    private val zones =
        listOf(
            Zone("全站", null),
            Zone("动画", 1005),
            Zone("音乐", 1003),
        )

    @Test
    fun orderZonesByKeys_should_follow_the_saved_key_order() {
        val ordered = CategoryZones.orderZonesByKeys(zones, listOf("rank:1003", "all", "rank:1005"))

        assertEquals(listOf("音乐", "全站", "动画"), ordered.map { it.title })
    }

    @Test
    fun orderZonesByKeys_should_drop_unknown_keys_and_keep_the_first_duplicate() {
        val ordered = CategoryZones.orderZonesByKeys(zones, listOf("rank:1005", "rank:1005", "rank:999"))

        assertEquals(listOf("动画"), ordered.map { it.title })
    }

    @Test
    fun orderZonesByKeys_should_return_empty_for_an_empty_key_list() {
        assertEquals(emptyList<Zone>(), CategoryZones.orderZonesByKeys(zones, emptyList()))
    }
}
