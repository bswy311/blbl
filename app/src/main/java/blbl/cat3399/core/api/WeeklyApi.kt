package blbl.cat3399.core.api

import blbl.cat3399.core.api.video.web.WebVideoMapper
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One issue (a week) of Bilibili's "每周必看" curated selection. */
data class WeeklyIssue(
    val number: Int,
    val subject: String,
    val name: String,
) {
    /** "第393期 09.25 - 10.01 · 猫鼠队上大分", falling back to the raw API name. */
    fun displayText(): String {
        val shortName = name.replaceFirst(YEAR_PREFIX, "").trim().ifBlank { "第${number}期" }
        val subjectText = subject.trim()
        return if (subjectText.isBlank()) shortName else "$shortName · $subjectText"
    }

    private companion object {
        val YEAR_PREFIX = Regex("^\\d{4}")
    }
}

/** A resolved "每周必看" issue together with its curated videos. */
data class WeeklyIssueContent(
    val issue: WeeklyIssue,
    val items: List<VideoCard>,
)

/**
 * Web-only "每周必看" endpoints. `series/one` is behind WBI signing and, unlike the
 * other series endpoints, returns the *first* issue when `number` is omitted, so the
 * issue number always has to come from [issues].
 */
internal object WeeklyApi {
    private const val BASE = "https://api.bilibili.com"
    private const val LIST_PATH = "/x/web-interface/popular/series/list"
    private const val ONE_PATH = "/x/web-interface/popular/series/one"
    private const val WEB_LOCATION = "333.934"

    /**
     * Minimal JSON view so issue parsing can be unit tested: local JVM tests cannot use
     * `org.json` (android.jar ships stubs).
     */
    internal interface JsonObj {
        fun optString(name: String, fallback: String): String

        fun optInt(name: String, fallback: Int): Int

        fun optObject(name: String): JsonObj?

        fun optObjectArray(name: String): List<JsonObj>
    }

    private class OrgJsonObj(
        private val obj: JSONObject,
    ) : JsonObj {
        override fun optString(name: String, fallback: String): String = obj.optString(name, fallback)

        override fun optInt(name: String, fallback: Int): Int = obj.optInt(name, fallback)

        override fun optObject(name: String): JsonObj? = obj.optJSONObject(name)?.let { OrgJsonObj(it) }

        override fun optObjectArray(name: String): List<JsonObj> {
            val arr = obj.optJSONArray(name) ?: return emptyList()
            val out = ArrayList<JsonObj>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out += OrgJsonObj(it) }
            }
            return out
        }
    }

    internal fun parseIssues(root: JsonObj): List<WeeklyIssue> =
        root
            .optObject("data")
            ?.optObjectArray("list")
            .orEmpty()
            .mapNotNull { parseIssueObject(it, fallbackNumber = 0) }

    internal fun parseIssue(
        root: JsonObj,
        fallbackNumber: Int = 0,
    ): WeeklyIssue? = root.optObject("data")?.optObject("config")?.let { parseIssueObject(it, fallbackNumber) }

    private fun parseIssueObject(
        obj: JsonObj,
        fallbackNumber: Int,
    ): WeeklyIssue? {
        val number = obj.optInt("number", 0).takeIf { it > 0 } ?: fallbackNumber.takeIf { it > 0 } ?: return null
        return WeeklyIssue(
            number = number,
            subject = obj.optString("subject", "").trim(),
            name = obj.optString("name", "").trim(),
        )
    }

    suspend fun issues(): List<WeeklyIssue> {
        val json = BiliClient.getJson("$BASE$LIST_PATH")
        requireSuccess(json)
        return withContext(Dispatchers.Default) { parseIssues(OrgJsonObj(json)) }
    }

    suspend fun issue(number: Int): WeeklyIssueContent {
        val safeNumber =
            number.takeIf { it > 0 }
                ?: throw BiliApiException(apiCode = -400, apiMessage = "missing_weekly_issue_number")

        val keys = BiliClient.ensureWbiKeys()
        val url =
            BiliClient.signedWbiUrl(
                path = ONE_PATH,
                params = mapOf("number" to safeNumber.toString(), "web_location" to WEB_LOCATION),
                keys = keys,
            )
        val json = BiliClient.getJson(url)
        requireSuccess(json)

        return withContext(Dispatchers.Default) {
            val issue =
                parseIssue(OrgJsonObj(json), fallbackNumber = safeNumber)
                    ?: throw BiliApiException(apiCode = -404, apiMessage = "weekly_issue_not_found")
            val list = json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
            WeeklyIssueContent(
                issue = issue,
                items = WebVideoMapper(BiliApiSource.WEB).parseWeeklySelectedCards(list),
            )
        }
    }

    private fun requireSuccess(json: JSONObject) {
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }
}
