package blbl.cat3399.feature.my

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayAllOrder
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BackButtonSizingHelper
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.GridViewportFillMonitor
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.installGridViewportFillMonitor
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.requestFocusFirstItemOrSelfAfterRefresh
import blbl.cat3399.core.ui.setTextSizePxIfChanged
import blbl.cat3399.core.ui.uiScaler
import blbl.cat3399.databinding.FragmentMyFavFolderDetailBinding
import blbl.cat3399.feature.following.openUpDetailFromVideoCard
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.VideoCardPlaylistPage
import blbl.cat3399.feature.video.VideoCardActionController
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.feature.video.VideoCardDismissBehavior
import blbl.cat3399.feature.video.VideoCardPlaybackSource
import blbl.cat3399.feature.video.VideoCardVisibilityFilter
import blbl.cat3399.feature.video.buildPagedVideoCardPlaybackHandle
import blbl.cat3399.feature.video.defaultVideoCardPlaylistItem
import blbl.cat3399.feature.video.openPlayerFromPlaybackSource
import blbl.cat3399.feature.video.openVideoDetailFromPlaybackHandle
import blbl.cat3399.feature.video.openVideoFromPlaybackHandle
import blbl.cat3399.feature.video.removeVideoCardAndRestoreFocus
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MyFavFolderDetailFragment : Fragment(), RefreshKeyHandler {
    private var _binding: FragmentMyFavFolderDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoCardAdapter
    private var lastAppliedHeaderSizingScale: Float? = null
    private var baseTitleTextSizePx: Float? = null

    private val mediaId: Long by lazy { requireArguments().getLong(ARG_MEDIA_ID) }
    private val title: String by lazy { requireArguments().getString(ARG_TITLE).orEmpty() }

    private val loadedStableKeys = HashSet<String>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var page: Int = 1
    private var requestToken: Int = 0
    private var pendingFocusFirstItem: Boolean = false
    private var dpadGridController: DpadGridController? = null
    private var viewportFillMonitor: GridViewportFillMonitor? = null
    private var lastFocusedHeaderView: View? = null
    private var playAllJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyFavFolderDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStackImmediate() }
        binding.tvTitle.text = title.ifBlank { getString(R.string.my_fav_default_title) }
        applyHeaderSizing(uiScale = UiScale.factor(requireContext()))
        setupPlayAllHeader()

        if (!::adapter.isInitialized) {
            val actionController =
                VideoCardActionController(
                    context = requireContext(),
                    scope = viewLifecycleOwner.lifecycleScope,
                    dismissBehavior = VideoCardDismissBehavior.DeleteFavFolderItem(mediaId = mediaId),
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
                    onClick = { _, pos ->
                        requireContext().openVideoFromPlaybackHandle(
                            playbackHandle = playbackHandle(),
                            position = pos,
                            openDetailBeforePlay = BiliClient.prefs.playerOpenDetailBeforePlay,
                        )
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
        binding.recycler.clearOnScrollListeners()
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isLoadingMore || endReached) return
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return
                    if (total - lastVisible - 1 <= 8) loadNextPage()
                }
            },
        )
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            if (focusPlayAllHeader()) return true
                            binding.btnBack.requestFocus()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            binding.btnBack.requestFocus()
                            return true
                        }

                        override fun onRightEdge() = Unit

                        override fun canLoadMore(): Boolean = !endReached

                        override fun loadMore() {
                            loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }
        viewportFillMonitor?.release()
        viewportFillMonitor =
            binding.recycler.installGridViewportFillMonitor(
                isEnabled = { _binding != null && isResumed },
                canLoadMore = { !isLoadingMore && !endReached },
                loadMore = { loadNextPage() },
            )
        binding.swipeRefresh.setOnRefreshListener {
            pendingFocusFirstItem = true
            dpadGridController?.parkFocusForDataSetReset()
            resetAndLoad()
        }

        if (savedInstanceState == null) {
            pendingFocusFirstItem = true
            binding.recycler.requestFocus()
            binding.swipeRefresh.isRefreshing = true
            resetAndLoad()
        }

    }

    override fun onResume() {
        super.onResume()
        applyBackButtonSizing()
        // The order picker may have been changed on another folder entry of the same folder.
        updatePlayOrderLabel()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth(resources)
        viewportFillMonitor?.scheduleCheck()
    }

    private fun applyHeaderSizing(uiScale: Float) {
        val b = _binding ?: return
        val scale = uiScale.takeIf { it.isFinite() && it > 0f } ?: 1.0f
        if (lastAppliedHeaderSizingScale == scale) return

        val scaler = requireContext().uiScaler(scale)
        val baseTs = baseTitleTextSizePx ?: b.tvTitle.textSize.also { baseTitleTextSizePx = it }
        b.tvTitle.setTextSizePxIfChanged(scaler.scaledPxF(baseTs, minPx = 1f))

        lastAppliedHeaderSizingScale = scale
    }

    private fun applyBackButtonSizing() {
        val sidebarScale = UiScale.factor(requireContext())
        BackButtonSizingHelper.applySidebarSizing(
            view = binding.btnBack,
            resources = resources,
            sidebarScale = sidebarScale,
        )
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        pendingFocusFirstItem = true
        dpadGridController?.parkFocusForDataSetReset()
        resetAndLoad()
        return true
    }

    private fun setupPlayAllHeader() {
        val b = _binding ?: return
        b.btnPlayAll.setOnClickListener { startPlayAll() }
        b.btnPlayOrder.setOnClickListener { showPlayOrderPicker() }
        b.btnPlayAll.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) lastFocusedHeaderView = b.btnPlayAll }
        b.btnPlayOrder.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) lastFocusedHeaderView = b.btnPlayOrder }
        updatePlayOrderLabel()
    }

    private fun updatePlayOrderLabel() {
        val b = _binding ?: return
        b.btnPlayOrder.text =
            PlayAllOrderUi.orderButtonText(
                context = b.root.context,
                order = BiliClient.prefs.favFolderPlayAllOrder(mediaId),
            )
    }

    private fun focusPlayAllHeader(): Boolean {
        val b = _binding ?: return false
        val header = b.llPlayAllHeader
        if (!header.isVisible || !header.isShown) return false
        val target = lastFocusedHeaderView?.takeIf { it.parent === header } ?: b.btnPlayAll
        return target.requestFocus()
    }

    private fun showPlayOrderPicker() {
        val b = _binding ?: return
        PlayAllOrderUi.showOrderPicker(
            context = requireContext(),
            currentOrder = BiliClient.prefs.favFolderPlayAllOrder(mediaId),
            restoreFocusTarget = b.btnPlayOrder,
        ) { picked ->
            BiliClient.prefs.setFavFolderPlayAllOrder(mediaId, picked)
            updatePlayOrderLabel()
        }
    }

    /**
     * 播放全部：收藏夹是分页接口，先把还没加载的页全部拉下来再交给播放器，这样随机/时长排序
     * 对收藏夹里的每个视频都成立；拉取期间给一个可取消的进度弹窗。
     */
    private fun startPlayAll() {
        if (playAllJob?.isActive == true) return
        if (!::adapter.isInitialized) return
        val ctx = context ?: return
        val order = PlayAllOrder.normalize(BiliClient.prefs.favFolderPlayAllOrder(mediaId))
        var finished = false
        val progress =
            AppPopup.progress(
                context = ctx,
                title = ctx.getString(R.string.play_all),
                status = ctx.getString(R.string.play_all_loading_fav),
                cancelable = true,
                onNegative = { playAllJob?.cancel() },
                onDismiss = { if (!finished) playAllJob?.cancel() },
            )
        playAllJob =
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val loaded = adapter.snapshot()
                    val seen = loaded.mapTo(HashSet()) { it.stableKey() }
                    val all = ArrayList(loaded)
                    var nextPage = page
                    var hasMore = !endReached
                    while (hasMore) {
                        val res = BiliApi.favFolderResources(mediaId = mediaId, pn = nextPage, ps = 20)
                        val fresh = VideoCardVisibilityFilter.filterVisibleFresh(res.items, seen)
                        fresh.forEach { seen.add(it.stableKey()) }
                        all.addAll(fresh)
                        nextPage++
                        hasMore = res.hasMore
                        progress?.updateStatus(ctx.getString(R.string.play_all_loading_fav_count, all.size))
                    }
                    if (all.isEmpty()) {
                        AppToast.show(ctx, ctx.getString(R.string.play_all_empty_fav))
                        return@launch
                    }
                    val ordered = PlayAllOrder.apply(all, order)
                    AppLog.i("MyFavDetail", "playAll mediaId=$mediaId size=${ordered.size} order=$order")
                    // 播放列表模式只对这一会话生效（intent 覆盖），不写全局播放模式设置，
                    // 这样用户点单张卡片时保持他自己的播放模式。
                    ctx.openPlayerFromPlaybackSource(
                        playbackSource =
                            VideoCardPlaybackSource(
                                cards = ordered,
                                source = "MyFavFolderPlayAll:$mediaId",
                            ),
                        position = 0,
                    ) {
                        putExtra(PlayerActivity.EXTRA_PLAYBACK_MODE_OVERRIDE, AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    AppLog.e("MyFavDetail", "playAll failed mediaId=$mediaId", t)
                    AppToast.show(ctx, "加载失败，可查看 Logcat(标签 BLBL)")
                } finally {
                    finished = true
                    progress?.dismiss()
                }
            }
    }

    private fun resetAndLoad() {
        pendingFocusFirstItem = true
        dpadGridController?.parkFocusForDataSetReset()
        loadedStableKeys.clear()
        isLoadingMore = false
        endReached = false
        page = 1
        requestToken++
        dpadGridController?.clearPendingFocusAfterLoadMore()
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        val token = requestToken
        isLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var targetPage = page
                var loadedPage: Pair<List<blbl.cat3399.core.model.VideoCard>, Boolean>? = null
                while (loadedPage == null) {
                    val res = BiliApi.favFolderResources(mediaId = mediaId, pn = targetPage, ps = 20)
                    if (token != requestToken) return@launch
                    val hasMore = res.hasMore
                    val visibleItems = VideoCardVisibilityFilter.filterVisibleFresh(res.items, loadedStableKeys)
                    targetPage++
                    if (visibleItems.isNotEmpty() || !hasMore) {
                        loadedPage = visibleItems to hasMore
                    }
                }
                val (visibleItems, hasMore) = checkNotNull(loadedPage)
                if (token != requestToken) return@launch
                visibleItems.forEach { loadedStableKeys.add(it.stableKey()) }
                if (isRefresh) adapter.submit(visibleItems) else adapter.append(visibleItems)
                maybeFocusFirstItem()
                _binding?.recycler?.postIfAlive(isAlive = { _binding != null }) { dpadGridController?.consumePendingFocusAfterLoadMore() }
                endReached = !hasMore
                page = targetPage
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("MyFavDetail", "load failed mediaId=$mediaId", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
                isLoadingMore = false
                viewportFillMonitor?.scheduleCheck()
            }
        }
    }

    private fun maybeFocusFirstItem() {
        if (!pendingFocusFirstItem) return
        if (_binding == null) return
        val recycler = binding.recycler
        val isUiAlive = { _binding != null && isResumed }
        recycler.requestFocusFirstItemOrSelfAfterRefresh(
            itemCount = adapter.itemCount,
            smoothScroll = false,
            isAlive = isUiAlive,
            onDone = { pendingFocusFirstItem = false },
        )
    }

    override fun onDestroyView() {
        playAllJob?.cancel()
        playAllJob = null
        dpadGridController?.release()
        dpadGridController = null
        viewportFillMonitor?.release()
        viewportFillMonitor = null
        _binding = null
        super.onDestroyView()
    }

    private fun openDetail(position: Int) {
        requireContext().openVideoDetailFromPlaybackHandle(playbackHandle(), position)
    }

    private fun playbackHandle() =
        buildPagedVideoCardPlaybackHandle(
            source = "MyFavFolderDetail:$mediaId",
            cardsProvider = adapter::snapshot,
            nextCursorProvider = { page },
            hasMoreProvider = { !endReached },
        ) { targetPage ->
            val pageNum = targetPage.coerceAtLeast(1)
            val res = BiliApi.favFolderResources(mediaId = mediaId, pn = pageNum, ps = 20)
            VideoCardPlaylistPage(
                cards = res.items,
                nextCursor = pageNum + 1,
                hasMore = res.hasMore,
                canAdvance = res.hasMore && res.items.isNotEmpty(),
            )
        }

    companion object {
        private const val ARG_MEDIA_ID = "media_id"
        private const val ARG_TITLE = "title"

        fun newInstance(mediaId: Long, title: String): MyFavFolderDetailFragment =
            MyFavFolderDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_MEDIA_ID, mediaId)
                    putString(ARG_TITLE, title)
                }
            }
    }
}
