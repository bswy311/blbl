package blbl.cat3399.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkUpdaterDownloadPlanTest {
    @Test
    fun planSegments_coversWholeFileWithoutGapsOrOverlap() {
        val total = 5_267_453L

        val segments = ApkUpdater.planSegments(totalBytes = total, count = 4)

        assertEquals(4, segments.size)
        assertEquals(0L, segments.first().start)
        assertEquals(total - 1, segments.last().end)
        assertEquals(total, segments.sumOf { it.length })
        segments.zipWithNext { a, b -> assertEquals(a.end + 1, b.start) }
    }

    @Test
    fun planSegments_smallFileYieldsFewerSegmentsThanRequested() {
        val segments = ApkUpdater.planSegments(totalBytes = 3L, count = 4)

        assertEquals(listOf(0L, 1L, 2L), segments.map { it.start })
        assertEquals(3L, segments.sumOf { it.length })
    }

    @Test
    fun planSegments_nonPositiveSizeYieldsNothing() {
        assertTrue(ApkUpdater.planSegments(totalBytes = 0L, count = 4).isEmpty())
        assertTrue(ApkUpdater.planSegments(totalBytes = -1L, count = 4).isEmpty())
    }

    @Test
    fun mirrorCandidates_expandsGithubUrlToEveryMirror() {
        val url = "https://github.com/bswy311/blbl/releases/download/v0.2.2/blbl-android-0.2.2-release.apk"

        val candidates = ApkUpdater.mirrorCandidates(url)

        assertEquals(candidates.size, candidates.distinct().size)
        assertTrue(candidates.contains("https://gh-proxy.com/$url"))
        assertTrue(candidates.contains("https://ghfast.top/$url"))
        assertTrue(candidates.contains(url))
        // 直连 GitHub 排在最后，其余都是"镜像前缀 + 原地址"。
        assertEquals(url, candidates.last())
    }

    @Test
    fun mirrorCandidates_keepsNonGithubUrlAsIs() {
        val url = "https://example.com/update.apk"

        assertEquals(listOf(url), ApkUpdater.mirrorCandidates(url))
    }

    @Test
    fun mirrorCandidates_blankUrlYieldsNothing() {
        assertTrue(ApkUpdater.mirrorCandidates("   ").isEmpty())
    }
}
