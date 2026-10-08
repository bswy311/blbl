package blbl.cat3399.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlayerPlaybackModesTest {
    private val knownCodes =
        listOf(
            AppPrefs.PLAYER_PLAYBACK_MODE_NONE,
            AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE,
            AppPrefs.PLAYER_PLAYBACK_MODE_EXIT,
            AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST,
            AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST,
            AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST_THEN_RECOMMEND,
            AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND,
        )

    @Test
    fun normalize_keeps_known_codes_untouched() {
        for (code in knownCodes) {
            assertEquals(code, PlayerPlaybackModes.normalize(code))
            assertEquals(code, PlayerPlaybackModes.normalize(" $code "))
        }
    }

    @Test
    fun normalize_falls_back_to_none_for_unknown_codes() {
        assertEquals(AppPrefs.PLAYER_PLAYBACK_MODE_NONE, PlayerPlaybackModes.normalize(null))
        assertEquals(AppPrefs.PLAYER_PLAYBACK_MODE_NONE, PlayerPlaybackModes.normalize(""))
        assertEquals(AppPrefs.PLAYER_PLAYBACK_MODE_NONE, PlayerPlaybackModes.normalize("  "))
        assertEquals(AppPrefs.PLAYER_PLAYBACK_MODE_NONE, PlayerPlaybackModes.normalize("nonsense"))
    }

    @Test
    fun unknown_codes_do_not_round_trip() {
        // playbackModeOverrideFromIntent() 用 `normalize(code) == code` 判断 intent 传来的会话级
        // 覆盖值是否合法；未知值必须让这个判断为 false，否则会拿 NONE 顶掉用户的全局播放模式。
        assertNotEquals("nonsense", PlayerPlaybackModes.normalize("nonsense"))
        assertNotEquals(null, PlayerPlaybackModes.normalize(null))
        assertEquals(
            AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST,
            AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST.takeIf { PlayerPlaybackModes.normalize(it) == it },
        )
    }
}
