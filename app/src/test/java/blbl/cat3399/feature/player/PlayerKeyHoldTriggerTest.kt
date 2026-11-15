package blbl.cat3399.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerKeyHoldTriggerTest {
    @Test
    fun triggerStaysAtSevenHundredMs() {
        assertEquals(700L, PlayerActivity.KEY_HOLD_TRIGGER_MS)
    }

    @Test
    fun beforeThresholdIsNotHold() {
        assertFalse(isKeyHoldPastTrigger(eventTimeMs = 0L, downTimeMs = 0L))
        assertFalse(isKeyHoldPastTrigger(eventTimeMs = 699L, downTimeMs = 0L))
    }

    @Test
    fun atOrAfterThresholdIsHold() {
        assertTrue(isKeyHoldPastTrigger(eventTimeMs = 700L, downTimeMs = 0L))
        assertTrue(isKeyHoldPastTrigger(eventTimeMs = 1_500L, downTimeMs = 0L))
    }

    @Test
    fun usesHeldDurationNotAbsoluteEventTime() {
        assertFalse(isKeyHoldPastTrigger(eventTimeMs = 1_000L, downTimeMs = 650L))
        assertTrue(isKeyHoldPastTrigger(eventTimeMs = 1_050L, downTimeMs = 350L))
    }
}
