package blbl.cat3399.feature.my

import android.content.Context
import android.view.View
import blbl.cat3399.R
import blbl.cat3399.core.prefs.PlayAllOrder
import blbl.cat3399.core.ui.popup.AppPopup

/**
 * Shared UI pieces of the "播放全部" bar: 稍后再看 and 收藏夹 show the same choices but remember them
 * separately, so callers pass their own stored order in and out.
 */
internal object PlayAllOrderUi {
    fun orderLabelRes(order: String): Int =
        when (PlayAllOrder.normalize(order)) {
            PlayAllOrder.SHUFFLE -> R.string.play_all_order_shuffle
            PlayAllOrder.REVERSE -> R.string.play_all_order_reverse
            PlayAllOrder.DURATION_LONG_FIRST -> R.string.play_all_order_duration_long_first
            PlayAllOrder.DURATION_SHORT_FIRST -> R.string.play_all_order_duration_short_first
            PlayAllOrder.RECENT_PLAY -> R.string.play_all_order_recent_play
            else -> R.string.play_all_order_sequential
        }

    fun orderButtonText(
        context: Context,
        order: String,
    ): CharSequence = context.getString(R.string.play_all_order_button, context.getString(orderLabelRes(order)))

    fun showOrderPicker(
        context: Context,
        currentOrder: String,
        restoreFocusTarget: View,
        orders: List<String> = PlayAllOrder.ordered,
        onPicked: (String) -> Unit,
    ) {
        val current = PlayAllOrder.normalize(currentOrder)
        AppPopup.singleChoice(
            context = context,
            title = context.getString(R.string.play_all_order_title),
            items = orders.map { context.getString(orderLabelRes(it)) },
            checkedIndex = orders.indexOf(current).coerceAtLeast(0),
            onRestoreFocus = { restoreFocusTarget.requestFocus() },
        ) { index, _ ->
            orders.getOrNull(index)?.let(onPicked)
        }
    }
}
