package blbl.cat3399.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSpeedHintTest {
    @Test
    fun originalAndSlowSpeedsHaveNoHint() {
        assertNull(speedHoldHintTextFor(1.0f))
        assertNull(speedHoldHintTextFor(1.0f + 0.0005f))
        assertNull(speedHoldHintTextFor(0.5f))
        assertNull(speedHoldHintTextFor(0.25f))
    }

    @Test
    fun fasterSpeedsRenderHoldStyleHint() {
        // 与长按快进按住时的提示完全一致（「快进 x2」），不要「播放速度：X.XXx」这类文案。
        assertEquals("快进 x2", speedHoldHintTextFor(2.0f))
        assertEquals("快进 x1.25", speedHoldHintTextFor(1.25f))
        assertEquals("快进 x3", speedHoldHintTextFor(3.0f))
        assertEquals("快进 x4", speedHoldHintTextFor(4.0f))
    }

    @Test
    fun nonFiniteSpeedHasNoHint() {
        assertNull(speedHoldHintTextFor(Float.NaN))
        assertNull(speedHoldHintTextFor(Float.POSITIVE_INFINITY))
    }

    @Test
    fun holdSpeedLabelTrimsTrailingZeros() {
        assertEquals("2", holdSpeedLabel(2.0f))
        assertEquals("1.25", holdSpeedLabel(1.25f))
        assertEquals("1.5", holdSpeedLabel(1.5f))
        assertEquals("3", holdSpeedLabel(Float.NaN)) // 非有限值回落到 PLAYER_HOLD_SEEK_SPEED_DEFAULT（3.0f）
    }
}
