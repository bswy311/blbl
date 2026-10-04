package blbl.cat3399.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeeklyApiParseTest {
    private class MapObj(
        private val values: Map<String, Any?>,
    ) : WeeklyApi.JsonObj {
        override fun optString(name: String, fallback: String): String {
            val v = values[name] ?: return fallback
            return v.toString()
        }

        override fun optInt(name: String, fallback: Int): Int =
            when (val v = values[name]) {
                is Number -> v.toInt()
                is String -> v.trim().toIntOrNull() ?: fallback
                else -> fallback
            }

        override fun optObject(name: String): WeeklyApi.JsonObj? = (values[name] as? Map<*, *>)?.let { MapObj(toStringMap(it)) }

        override fun optObjectArray(name: String): List<WeeklyApi.JsonObj> =
            (values[name] as? List<*>).orEmpty().mapNotNull { item ->
                (item as? Map<*, *>)?.let { MapObj(toStringMap(it)) }
            }

        private fun toStringMap(src: Map<*, *>): Map<String, Any?> =
            src.entries.associate { (k, v) -> k.toString() to v }
    }

    private fun issuesRoot(vararg issues: Map<String, Any?>): WeeklyApi.JsonObj = MapObj(mapOf("data" to mapOf("list" to issues.toList())))

    @Test
    fun parseIssues_should_map_number_subject_and_name() {
        val root =
            issuesRoot(
                mapOf("number" to 393, "subject" to "猫鼠队上大分", "name" to "2026第393期 09.25 - 10.01"),
                mapOf("number" to 392, "subject" to "宏大交响琵琶曲", "name" to "2026第392期 09.18 - 09.24"),
            )

        val issues = WeeklyApi.parseIssues(root)

        assertEquals(2, issues.size)
        assertEquals(393, issues[0].number)
        assertEquals("猫鼠队上大分", issues[0].subject)
        assertEquals("2026第393期 09.25 - 10.01", issues[0].name)
        assertEquals(392, issues[1].number)
    }

    @Test
    fun parseIssues_should_skip_entries_without_number() {
        val root =
            issuesRoot(
                mapOf("subject" to "no number"),
                mapOf("number" to "0", "subject" to "zero"),
                mapOf("number" to 391, "subject" to "ok"),
            )

        val issues = WeeklyApi.parseIssues(root)

        assertEquals(1, issues.size)
        assertEquals(391, issues[0].number)
    }

    @Test
    fun parseIssues_should_return_empty_when_payload_missing() {
        assertEquals(emptyList<WeeklyIssue>(), WeeklyApi.parseIssues(MapObj(emptyMap())))

        val dataOnly = MapObj(mapOf("data" to emptyMap<String, Any?>()))
        assertEquals(emptyList<WeeklyIssue>(), WeeklyApi.parseIssues(dataOnly))
    }

    @Test
    fun parseIssue_should_read_config_and_use_fallback_number() {
        val root = MapObj(mapOf("data" to mapOf("config" to mapOf("subject" to "主题", "name" to "2026第393期 09.25 - 10.01"))))

        val issue = WeeklyApi.parseIssue(root, fallbackNumber = 393)

        assertEquals(393, issue?.number)
        assertEquals("主题", issue?.subject)
    }

    @Test
    fun parseIssue_should_prefer_config_number_over_fallback() {
        val root = MapObj(mapOf("data" to mapOf("config" to mapOf("number" to 391, "subject" to "主题", "name" to "name"))))

        assertEquals(391, WeeklyApi.parseIssue(root, fallbackNumber = 393)?.number)
    }

    @Test
    fun parseIssue_should_return_null_without_config() {
        assertNull(WeeklyApi.parseIssue(MapObj(mapOf("data" to emptyMap<String, Any?>())), fallbackNumber = 393))
        assertNull(WeeklyApi.parseIssue(MapObj(emptyMap()), fallbackNumber = 0))
    }

    @Test
    fun displayText_should_drop_year_prefix_and_append_subject() {
        val issue = WeeklyIssue(number = 393, subject = "猫鼠队上大分", name = "2026第393期 09.25 - 10.01")

        assertEquals("第393期 09.25 - 10.01 · 猫鼠队上大分", issue.displayText())
    }

    @Test
    fun displayText_should_fall_back_when_name_and_subject_missing() {
        assertEquals("第7期", WeeklyIssue(number = 7, subject = "  ", name = "").displayText())
        assertEquals("第7期 · 主题", WeeklyIssue(number = 7, subject = "主题", name = "").displayText())
    }
}
