package blbl.cat3399.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkUpdaterChangelogTest {
    @Test
    fun parseChangelog_should_use_first_version_section() {
        val update =
            ApkUpdater.parseChangelog(
                """
                # CHANGELOG

                ## 0.2.0 - 2026-05-01

                ### 修复

                - 修复启动检查。

                ## 0.1.9

                - 旧版本内容。
                """.trimIndent(),
            )

        assertEquals("0.2.0", update.versionName)
        assertTrue(update.changelog.contains("修复启动检查"))
        assertFalse(update.changelog.contains("旧版本内容"))
    }

    @Test
    fun parseChangelog_should_accept_bracketed_v_prefix() {
        val update =
            ApkUpdater.parseChangelog(
                """
                # CHANGELOG

                ## [v0.3.1]

                - 新版本。
                """.trimIndent(),
            )

        assertEquals("0.3.1", update.versionName)
        assertTrue(update.changelog.contains("新版本"))
    }

    @Test
    fun isRemoteNewer_debug_build_should_always_be_older() {
        // debug 包 versionName 为 "debug"，无法解析为版本号，应视为 0.0.0
        assertTrue(ApkUpdater.isRemoteNewer("0.1.37", "debug"))
        assertTrue(ApkUpdater.isRemoteNewer("0.0.1", "debug"))
        assertTrue(ApkUpdater.isRemoteNewer("1.0.0", "debug"))
    }

    @Test
    fun isRemoteNewer_same_version_should_return_false() {
        assertFalse(ApkUpdater.isRemoteNewer("0.1.36", "0.1.36"))
        assertFalse(ApkUpdater.isRemoteNewer("0.1.36", "0.1.37"))
    }
}
