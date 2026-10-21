package blbl.cat3399.core.prefs

object PlayerHoldSpeedModes {
    const val RESTORE_ON_RELEASE = "restore_on_release"
    const val KEEP_UNTIL_LEFT = "keep_until_left"

    fun normalize(value: String?): String =
        when (value?.trim()) {
            KEEP_UNTIL_LEFT -> KEEP_UNTIL_LEFT
            else -> RESTORE_ON_RELEASE
        }

    fun shouldKeepAfterRelease(
        mode: String,
        isSpeedHold: Boolean,
    ): Boolean = normalize(mode) == KEEP_UNTIL_LEFT && isSpeedHold
}
