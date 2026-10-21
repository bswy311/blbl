package blbl.cat3399.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerHoldSpeedModesTest {
    @Test
    fun normalizeDefaultsToRestoreOnRelease() {
        assertEquals(PlayerHoldSpeedModes.RESTORE_ON_RELEASE, PlayerHoldSpeedModes.normalize(null))
        assertEquals(PlayerHoldSpeedModes.RESTORE_ON_RELEASE, PlayerHoldSpeedModes.normalize("unknown"))
    }

    @Test
    fun normalizeKeepsSupportedMode() {
        assertEquals(PlayerHoldSpeedModes.KEEP_UNTIL_LEFT, PlayerHoldSpeedModes.normalize(" keep_until_left "))
    }

    @Test
    fun onlySpeedHoldCanPersistAfterRelease() {
        assertTrue(PlayerHoldSpeedModes.shouldKeepAfterRelease(PlayerHoldSpeedModes.KEEP_UNTIL_LEFT, isSpeedHold = true))
        assertFalse(PlayerHoldSpeedModes.shouldKeepAfterRelease(PlayerHoldSpeedModes.RESTORE_ON_RELEASE, isSpeedHold = true))
        assertFalse(PlayerHoldSpeedModes.shouldKeepAfterRelease(PlayerHoldSpeedModes.KEEP_UNTIL_LEFT, isSpeedHold = false))
    }
}
