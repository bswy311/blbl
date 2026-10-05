package blbl.cat3399.feature.my

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.ToViewPlayAllOrder
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.requestFocusFirstItemOrSelfAfterRefresh
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.feature.following.openUpDetailFromVideoCard
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.video.VideoCardActionController
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.feature.video.VideoCardDismissBehavior
import blbl.cat3399.feature.video.VideoCardPlaybackSource
import blbl.cat3399.feature.video.VideoCardVisibilityFilter
import blbl.cat3399.feature.video.buildVideoCardPlaylistToken
import blbl.cat3399.feature.video.openPlayerFromPlaybackSource
import blbl.cat3399.feature.video.openVideoDetailFromCards
import blbl.cat3399.feature.video.removeVideoCardAndRestoreFocus
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MyToViewFragment : Fragment(), MyTabSwitchFocusTarget, RefreshKeyHandler {
    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoCardAdapter
    private var initialLoadTriggered: Boolean = false
    private var requestToken: Int = 0
    private var pendingFocusFirstItemFromTabSwitch: Boolean = false
    private var pendingFocusFirstItemAfterRefresh: Boolean = false
    private var dpadGridController: DpadGridController? = null
    private var lastFocusedHeaderView: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            val actionController =
                VideoCardActionController(
                    context = requireContext(),
                    scope = viewLifecycleOwner.lifecycleScope,
                    dismissBehavior = VideoCardDismissBehavior.DeleteToView,
                    onOpenDetail = { _, pos -> openDetail(pos) },
                    onOpenUp = { card -> openUpDetailFromVideoCard(card) },
                    onCardRemoved = { stableKey ->
                        _binding?.recycler?.removeVideoCardAndRestoreFocus(
                            adapter = adapter,
                            stableKey = stableKey,
                            isAlive = { _binding != null && isResumed },
                        )
                    },
                )
            adapter =
                VideoCardAdapter(
                    onClick = { card, pos ->
                        val cards = adapter.snapshot()
                        if (BiliClient.prefs.playerOpenDetailBeforePlay) {
                            requireContext().openVideoDetailFromCards(
                                cards = cards,
                                position = pos,
                                source = "MyToView",
                            )
                        } else {
                            val token =
                                cards.buildVideoCardPlaylistToken(
                                    index = pos,
                                    source = "MyToView",
                                ) ?: return@VideoCardAdapter
                            startActivity(
                                Intent(requireContext(), PlayerActivity::class.java)
                                    .putExtra(PlayerActivity.EXTRA_BVID, card.bvid)
                                    .putExtra(PlayerActivity.EXTRA_CID, card.cid ?: -1L)
                                    .apply {
                                        card.progressSec?.takeIf { it >= 5L }?.let { sec ->
                                            putExtra(PlayerActivity.EXTRA_START_POSITION_MS, sec * 1000L)
                                        }
                                    }
                                    .putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, token)
                                    .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, pos),
                            )
                        }
                    },
                    onLongClick = { card, _ ->
                        openUpDetailFromVideoCard(card)
                        true
                    },
                    actionDelegate = actionController,
                )
        }
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth(resources))
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            if (focusPlayAllHeader()) return true
                            focusSelectedMyTabIfAvailable()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            return switchToPrevMyTabFromContentEdge()
                        }

                        override fun onRightEdge() {
                            switchToNextMyTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean = false

                        override fun loadMore() = Unit
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }
        binding.swipeRefresh.setOnRefreshListener {
            pendingFocusFirstItemAfterRefresh = true
            dpadGridController?.parkFocusForDataSetReset()
            reload()
        }

        setupPlayAllHeader()
    }

    override fun onResume() {
        super.onResume()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth(resources)
        maybeTriggerInitialLoad()
        silentRefreshForProgressDataSource()
        maybeConsumePendingFocusFirstItemFromTabSwitch()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        pendingFocusFirstItemAfterRefresh = true
        dpadGridController?.parkFocusForDataSetReset()
        reload()
        return true
    }

    override fun requestFocusFirstItemFromTabSwitch(): Boolean {
        pendingFocusFirstItemFromTabSwitch = true
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstItemFromTabSwitch()
    }

    private fun setupPlayAllHeader() {
        val b = _binding ?: return
        b.llPlayAllHeader.isVisible = true
        b.btnPlayAll.setOnClickListener { startPlayAll() }
        b.btnPlayOrder.setOnClickListener { showPlayOrderPicker() }
        b.btnPlayAll.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) lastFocusedHeaderView = b.btnPlayAll }
        b.btnPlayOrder.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) lastFocusedHeaderView = b.btnPlayOrder }
        updatePlayOrderLabel()
    }

    private fun updatePlayOrderLabel() {
        val b = _binding ?: return
        val current = ToViewPlayAllOrder.normalize(BiliClient.prefs.toViewPlayAllOrder)
        b.btnPlayOrder.text = b.root.context.getString(R.string.my_toview_play_order_button, b.root.context.getString(playOrderLabelRes(current)))
    }

    private fun playOrderLabelRes(order: String): Int =
        when (order) {
            ToViewPlayAllOrder.DURATION_ASC -> R.string.my_toview_order_duration_asc
            ToViewPlayAllOrder.REVERSE -> R.string.my_toview_order_reverse
            ToViewPlayAllOrder.SHUFFLE -> R.string.my_toview_order_shuffle
            else -> R.string.my_toview_order_sequential
        }

    private fun focusPlayAllHeader(): Boolean {
        val b = _binding ?: return false
        val header = b.llPlayAllHeader
        if (!header.isVisible || !header.isShown) return false
        val target = lastFocusedHeaderView?.takeIf { it.parent === header } ?: b.btnPlayAll
        return target.requestFocus()
    }

    /**
     * Starts the whole 稍后再看 list as one playlist. The player walks the queue in storage
     * order, so the chosen ordering is applied here and the playlist mode is switched on
     * accordingly — otherwise playback would stop after the first video.
     */
    private fun startPlayAll() {
        val ctx = context ?: return
        val cards = if (::adapter.isInitialized) adapter.snapshot() else emptyList()
        if (cards.isEmpty()) {
            AppToast.show(ctx, ctx.getString(R.string.my_toview_play_all_empty))
            return
        }
        val order = ToViewPlayAllOrder.normalize(BiliClient.prefs.toViewPlayAllOrder)
        val ordered = ToViewPlayAllOrder.apply(cards, order)
        BiliClient.prefs.playerPlaybackMode = AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST
        AppLog.i("MyToView", "playAll size=${ordered.size} order=$order")
        ctx.openPlayerFromPlaybackSource(
            playbackSource = VideoCardPlaybackSource(cards = ordered, source = "MyToView"),
            position = 0,
        ) { card ->
            // Resume the first item from its saved progress, matching the single-card click path.
            card.progressSec?.takeIf { it >= 5L }?.let { sec ->
                putExtra(PlayerActivity.EXTRA_START_POSITION_MS, sec * 1000L)
            }
        }
    }

    private fun showPlayOrderPicker() {
        val ctx = context ?: return
        val b = _binding ?: return
        val orders = ToViewPlayAllOrder.ordered
        val current = ToViewPlayAllOrder.normalize(BiliClient.prefs.toViewPlayAllOrder)
        AppPopup.singleChoice(
            context = ctx,
            title = ctx.getString(R.string.my_toview_play_order_title),
            items = orders.map { ctx.getString(playOrderLabelRes(it)) },
            checkedIndex = orders.indexOf(current).coerceAtLeast(0),
            onRestoreFocus = { b.btnPlayOrder.requestFocus() },
        ) { index, _ ->
            val picked = orders.getOrNull(index) ?: return@singleChoice
            BiliClient.prefs.toViewPlayAllOrder = picked
            updatePlayOrderLabel()
        }
    }

    private fun maybeConsumePendingFocusFirstItemFromTabSwitch(): Boolean {
        if (!pendingFocusFirstItemFromTabSwitch) return false
        if (!isAdded || _binding == null) return false
        if (!isResumed) return false
        if (!this::adapter.isInitialized) return false

        // The "播放全部" bar sits above the grid, so entering the page lands there first.
        if (focusPlayAllHeader()) {
            pendingFocusFirstItemFromTabSwitch = false
            return true
        }

        val focused = activity?.currentFocus
        if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
            pendingFocusFirstItemFromTabSwitch = false
            return false
        }

        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return true
        }

        val recycler = binding.recycler
        recycler.postIfAlive(isAlive = { _binding != null }) {
            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                pendingFocusFirstItemFromTabSwitch = false
                return@postIfAlive
            }
            recycler.scrollToPosition(0)
            recycler.postIfAlive(isAlive = { _binding != null }) {
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
                pendingFocusFirstItemFromTabSwitch = false
            }
        }
        return true
    }

    private fun maybeTriggerInitialLoad() {
        if (initialLoadTriggered) return
        if (!this::adapter.isInitialized) return
        if (adapter.itemCount != 0) {
            initialLoadTriggered = true
            return
        }
        if (binding.swipeRefresh.isRefreshing) return
        binding.swipeRefresh.isRefreshing = true
        reload()
        initialLoadTriggered = true
    }

    private fun silentRefreshForProgressDataSource() {
        val b = _binding ?: return
        if (!initialLoadTriggered) return
        if (adapter.itemCount <= 0) return
        if (b.swipeRefresh.isRefreshing) return
        reload()
    }

    private fun reload() {
        val token = ++requestToken
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = BiliApi.toViewList()
                if (token != requestToken) return@launch
                if (pendingFocusFirstItemAfterRefresh) {
                    dpadGridController?.parkFocusForDataSetReset()
                }
                adapter.submit(VideoCardVisibilityFilter.filterVisible(list))
                _binding?.recycler?.postIfAlive(isAlive = { _binding != null }) {
                    if (pendingFocusFirstItemAfterRefresh) {
                        pendingFocusFirstItemAfterRefresh = false
                        val recycler = binding.recycler
                        val isUiAlive = { _binding != null && isResumed }
                        recycler.requestFocusFirstItemOrSelfAfterRefresh(
                            itemCount = adapter.itemCount,
                            smoothScroll = false,
                            isAlive = isUiAlive,
                        )
                        return@postIfAlive
                    }
                    maybeConsumePendingFocusFirstItemFromTabSwitch()
                    dpadGridController?.consumePendingFocusAfterLoadMore()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("MyToView", "load failed", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    override fun onDestroyView() {
        initialLoadTriggered = false
        dpadGridController?.release()
        dpadGridController = null
        _binding = null
        super.onDestroyView()
    }

    private fun openDetail(position: Int) {
        requireContext().openVideoDetailFromCards(
            cards = adapter.snapshot(),
            position = position,
            source = "MyToView",
        )
    }
}
