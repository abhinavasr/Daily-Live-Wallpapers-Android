package xyz.abhinava.depthwallpaper.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.abhinava.depthwallpaper.R
import xyz.abhinava.depthwallpaper.data.SceneRepository
import xyz.abhinava.depthwallpaper.data.WallpaperCategory
import xyz.abhinava.depthwallpaper.data.WallpaperPack
import xyz.abhinava.depthwallpaper.wallpaper.DepthWallpaperService
import java.time.Instant

class MainActivity : Activity() {
    private enum class Section { BROWSE, FAVORITES }
    private enum class SortMode { MOST_LIKED, NEWEST, A_TO_Z, Z_TO_A }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var categoryRow: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var galleryList: RecyclerView
    private lateinit var galleryAdapter: GalleryAdapter
    private lateinit var emptyView: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var emptyAction: Button
    private lateinit var content: LinearLayout
    private lateinit var heroImage: ImageView
    private lateinit var heroSideFade: View
    private lateinit var heroBottomFade: View
    private lateinit var headerText: LinearLayout
    private lateinit var searchBar: View
    private lateinit var searchToggle: View
    private lateinit var railScroll: HorizontalScrollView
    private lateinit var railEdgeFade: View
    private lateinit var railMoreButton: View
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var overlayStatus: TextView
    private lateinit var overlayProgress: ProgressBar
    private lateinit var repository: SceneRepository

    private var allPacks: List<WallpaperPack> = emptyList()
    private var categories: List<WallpaperCategory> = WallpaperCategory.fallback
    private var favoriteIds: Set<String> = emptySet()
    private var browseOrder: List<String> = emptyList()
    private var currentSection = Section.BROWSE
    private var currentCategory: String? = null
    private var currentSort = SortMode.MOST_LIKED
    private var searchQuery = ""
    private var isRefreshing = false
    private var isDownloading = false
    private var pullStartY = 0f
    private val searchDebounce = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Daily Live Wallpapers"
        actionBar?.hide()
        repository = SceneRepository(this)
        favoriteIds = repository.loadFavoriteIds()

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(28))
            setBackgroundColor(PAGE_BACKGROUND)
        }
        status = TextView(this).apply {
            text = "Loading gallery…"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF756B7E.toInt())
            setPadding(dp(18), 0, dp(18), dp(8))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
                setMargins(dp(18), 0, dp(18), dp(12))
            }
        }
        searchInput = EditText(this).apply {
            hint = "Search by wallpaper code or keyword…"
            setSingleLine(true)
            textSize = 15f
            background = null
            setPadding(0, 0, 0, 0)
            setTextColor(0xFF201A2B.toInt())
            setHintTextColor(0xFF9A93A6.toInt())
            setOnEditorActionListener { _, _, _ -> applySearch(text?.toString().orEmpty()); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { applySearch(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(2), dp(10), dp(2))
        }
        galleryAdapter = GalleryAdapter()
        galleryList = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = galleryAdapter
            setHasFixedSize(true)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            // Cards are cheap to bind but their preview decode is not; keeping a few extra off-screen
            // holders around makes fling scrolling steady without holding all 66 previews at once.
            setItemViewCacheSize(8)
        }
        emptyView = createEmptyState()

        content.addView(createHeader())
        content.addView(createSearchBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(18), 0, dp(18), dp(14))
        })
        content.addView(status)
        content.addView(progress)
        content.addView(createCategoryRail(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        content.addView(FrameLayout(this).apply {
            addView(galleryList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        loadingOverlay = createLoadingOverlay()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(PAGE_BACKGROUND)
            addView(content)
            addView(loadingOverlay)
        })
        applyInsets()
        installPullToRefresh()

        val cached = repository.loadCachedGallery()
        if (cached.isNotEmpty()) {
            allPacks = cached
            categories = repository.loadCachedCategories().ifEmpty { deriveCategories(cached) }
            rebuildOrders()
            hideProgress(IDLE_STATUS)
            renderCurrentSection()
        }
        // Only reach for the network when there is nothing cached, or the cache has aged out.
        if (cached.isEmpty() || !repository.isGalleryCacheFresh()) {
            refreshGallery(showLoading = cached.isEmpty())
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_BROWSE, 1, "All wallpapers").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_FAVORITES, 2, "Favourites").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_SEARCH, 3, "Search").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_CODE, 4, "Enter wallpaper code").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_REFRESH, 5, "Refresh").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_BROWSE -> { switchSection(Section.BROWSE); true }
            MENU_FAVORITES -> { switchSection(Section.FAVORITES); true }
            MENU_SEARCH -> { toggleSearch(); true }
            MENU_CODE -> { showCodeDialog(); true }
            MENU_REFRESH -> { refreshGallery(showLoading = true); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        pendingSearch?.let { searchDebounce.removeCallbacks(it) }
        scope.cancel()
        super.onDestroy()
    }

    private fun applyInsets() {
        content.setOnApplyWindowInsetsListener { _, insets ->
            val system = insets.getInsets(WindowInsets.Type.systemBars())
            content.setPadding(0, 0, 0, system.bottom)
            heroImage.layoutParams.height = dp(HERO_HEIGHT_DP) + system.top
            heroSideFade.layoutParams.height = dp(HERO_HEIGHT_DP) + system.top
            (heroBottomFade.layoutParams as FrameLayout.LayoutParams).topMargin =
                dp(HERO_HEIGHT_DP) + system.top - dp(56)
            headerText.setPadding(dp(18), system.top + dp(10), dp(18), dp(16))
            (searchToggle.layoutParams as FrameLayout.LayoutParams).topMargin = system.top + dp(14)
            headerText.requestLayout()
            galleryList.setPadding(dp(12), 0, dp(12), dp(28))
            insets
        }
    }

    /**
     * Brand block on the left, hero art bleeding off the top right corner and fading into the page
     * on its inner edges so it reads as artwork rather than a pasted-in rectangle.
     */
    private fun createHeader(): View {
        val header = FrameLayout(this)
        val heroWidth = dp(250)
        val heroHeight = dp(HERO_HEIGHT_DP)
        val hero = ImageView(this).apply {
            setImageResource(R.drawable.header_hero)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val transparentPage = PAGE_BACKGROUND and 0x00FFFFFF
        val sideFade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(PAGE_BACKGROUND, transparentPage)
            )
        }
        val bottomFade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(transparentPage, PAGE_BACKGROUND)
            )
        }
        heroImage = hero
        heroSideFade = sideFade
        heroBottomFade = bottomFade
        header.addView(hero, FrameLayout.LayoutParams(heroWidth, heroHeight, Gravity.END or Gravity.TOP))
        header.addView(sideFade, FrameLayout.LayoutParams(dp(110), heroHeight, Gravity.END or Gravity.TOP).apply {
            rightMargin = heroWidth - dp(110)
        })
        header.addView(bottomFade, FrameLayout.LayoutParams(heroWidth, dp(56), Gravity.END or Gravity.TOP).apply {
            topMargin = heroHeight - dp(56)
        })

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val appMark = ImageView(this).apply {
            setImageResource(R.drawable.daily_live_wallpapers_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedRect(0xFF7C4DFF.toInt(), 0x00000000, dp(14), 0)
            clipToOutline = true
        }
        val titleView = TextView(this).apply {
            text = "Wallpaper"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF16121D.toInt())
        }
        titleRow.addView(appMark, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
        titleRow.addView(titleView)
        val subtitle = TextView(this).apply {
            text = "Beautiful wallpapers for every mood"
            textSize = 15f
            setTextColor(0xFF6B6478.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(16))
            addView(titleRow)
            addView(subtitle, LinearLayout.LayoutParams(dp(215), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        headerText = textColumn
        header.addView(textColumn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.BOTTOM
        ))
        val toggle = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            background = roundedRect(0xFFFFFFFF.toInt(), 0xFFE9E2F0.toInt(), dp(999), 1)
            elevation = dp(3).toFloat()
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = "Search"
            setOnClickListener { toggleSearch() }
        }
        searchToggle = toggle
        header.addView(toggle, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(18)
            topMargin = dp(14)
        })
        return header
    }

    /**
     * The chip rail scrolls past the right edge, which is easy to miss when the chips are white on
     * a near-white page - a plain gradient fade is invisible there. A chevron over the trailing edge
     * says so outright and scrolls the rail on tap; both it and the fade behind it hide once the
     * rail is scrolled to the end, so they only appear when there is more to reach.
     */
    private fun createCategoryRail(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(categoryRow)
        }
        val edgeFade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(PAGE_BACKGROUND and 0x00FFFFFF, PAGE_BACKGROUND)
            )
        }
        val moreButton = TextView(this).apply {
            text = "›"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF6B6478.toInt())
            background = roundedRect(0xFFFFFFFF.toInt(), 0xFFDFD7E9.toInt(), dp(999), 1)
            elevation = dp(4).toFloat()
            isClickable = true
            contentDescription = "More categories"
            setOnClickListener { scroll.smoothScrollBy((scroll.width * 0.6f).toInt(), 0) }
        }
        railScroll = scroll
        railEdgeFade = edgeFade
        railMoreButton = moreButton
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateRailHint() }
        // canScrollHorizontally() only answers once the rail has been measured, so re-ask on layout
        // as well as on scroll - asking too early reports "nothing to scroll" and hides the hint.
        // The overlay is sized from the rail here too: a MATCH_PARENT child stretches a
        // WRAP_CONTENT FrameLayout to the full height the parent offers, which would swallow the
        // grid below and strand the chevron in the middle of the screen.
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val railHeight = bottom - top
            if (railHeight > 0 && edgeFade.layoutParams.height != railHeight) {
                edgeFade.layoutParams = edgeFade.layoutParams.apply { height = railHeight }
            }
            updateRailHint()
        }
        return FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(edgeFade, FrameLayout.LayoutParams(
                dp(44),
                dp(48),
                Gravity.END
            ))
            addView(moreButton, FrameLayout.LayoutParams(
                dp(30),
                dp(30),
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { rightMargin = dp(8) })
        }
    }

    private fun updateRailHint() {
        if (!::railScroll.isInitialized) return
        val wanted = if (railScroll.canScrollHorizontally(1)) View.VISIBLE else View.INVISIBLE
        // Only touch visibility on an actual change; the layout listener below would otherwise
        // bounce between layout passes.
        if (railEdgeFade.visibility != wanted) railEdgeFade.visibility = wanted
        if (railMoreButton.visibility != wanted) railMoreButton.visibility = wanted
    }

    private fun createSearchBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
            background = roundedRect(0xFFFFFFFF.toInt(), 0xFFE9E2F0.toInt(), dp(28), 1)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(2), dp(8), dp(2))
        }
        val glass = ImageView(this).apply { setImageResource(R.drawable.ic_search) }
        val scan = ImageView(this).apply {
            setImageResource(R.drawable.ic_scan)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            isFocusable = true
            foreground = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let {
                val drawable = it.getDrawable(0); it.recycle(); drawable
            }
            setOnClickListener { showCodeDialog() }
        }
        bar.addView(glass, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) })
        bar.addView(searchInput, LinearLayout.LayoutParams(0, dp(54), 1f))
        bar.addView(scan, LinearLayout.LayoutParams(dp(42), dp(42)))
        searchBar = bar
        return bar
    }

    private fun installPullToRefresh() {
        galleryList.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!galleryList.canScrollVertically(-1)) pullStartY = event.rawY
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val pulled = event.rawY - pullStartY
                    if (!isRefreshing && !galleryList.canScrollVertically(-1) && pulled > 180f) {
                        refreshGallery(showLoading = true)
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun refreshGallery(showLoading: Boolean) {
        if (isRefreshing) return
        isRefreshing = true
        if (showLoading) showProgress("Refreshing gallery…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val packs = repository.fetchGallery()
                    val taxonomy = runCatching { repository.fetchPackCategories() }.getOrElse { deriveCategories(packs) }
                    packs to taxonomy
                }
            }
            isRefreshing = false
            result.onSuccess { (packs, taxonomy) ->
                allPacks = packs
                categories = taxonomy.ifEmpty { deriveCategories(packs) }
                favoriteIds = repository.loadFavoriteIds()
                rebuildOrders()
                hideProgress(IDLE_STATUS)
                renderCurrentSection()
            }.onFailure { error ->
                val cached = repository.loadCachedGallery()
                if (cached.isNotEmpty()) {
                    allPacks = cached
                    categories = deriveCategories(cached)
                    rebuildOrders()
                    renderCurrentSection()
                }
                hideProgress(friendlyFailure("Refresh failed. Showing saved gallery.", error))
            }
        }
    }

    private fun switchSection(section: Section, category: String? = null) {
        currentSection = section
        currentCategory = category
        if (section != Section.BROWSE) currentCategory = null
        rebuildOrders()
        renderCurrentSection()
    }

    private fun toggleSearch() {
        val opening = searchBar.visibility != View.VISIBLE
        searchBar.visibility = if (opening) View.VISIBLE else View.GONE
        val keyboard = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (opening) {
            searchInput.requestFocus()
            keyboard.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            keyboard.hideSoftInputFromWindow(searchInput.windowToken, 0)
            searchInput.clearFocus()
            searchInput.setText("")
            applySearch("")
        }
    }

    /**
     * Debounced: every keystroke used to re-filter and rebuild the whole grid synchronously, so
     * typing a five letter query rebuilt every card five times over.
     */
    private fun applySearch(query: String) {
        val next = query.trim()
        // Cancel first, then bail: typing "a" and deleting it lands back on the current query, and
        // returning early without cancelling would let the queued "a" fire 250ms later.
        pendingSearch?.let { searchDebounce.removeCallbacks(it) }
        pendingSearch = null
        if (next == searchQuery) return
        val task = Runnable {
            searchQuery = next
            renderCurrentSection()
        }
        pendingSearch = task
        searchDebounce.postDelayed(task, SEARCH_DEBOUNCE_MS)
    }

    private fun rebuildOrders() {
        browseOrder = sortedBrowsePacks(allPacks).map { it.id }
    }

    private fun sortedBrowsePacks(packs: List<WallpaperPack>): List<WallpaperPack> {
        return when (currentSort) {
            SortMode.MOST_LIKED -> packs.sortedWith(compareByDescending<WallpaperPack> { it.likeCount }.thenByDescending { it.viewCount }.thenByDescending { sortTime(it) })
            SortMode.NEWEST -> packs.sortedByDescending { sortTime(it) }
            SortMode.A_TO_Z -> packs.sortedBy { it.title.lowercase() }
            SortMode.Z_TO_A -> packs.sortedByDescending { it.title.lowercase() }
        }
    }

    private fun renderCurrentSection() {
        renderCategoryRail()
        val filtered = orderedPacksForCurrentSection().filter { matchesSearch(it) }
        galleryAdapter.submit(filtered)
        updateEmptyState(filtered.isEmpty())
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        galleryList.visibility = if (isEmpty) View.GONE else View.VISIBLE
        if (!isEmpty) return
        emptyText.text = when {
            searchQuery.isNotBlank() -> "No listed wallpaper matched \u201c$searchQuery\u201d."
            currentSection == Section.FAVORITES -> "No favourites yet. Tap \u2661 on wallpapers you love."
            else -> "No wallpapers here yet. Pull down to refresh."
        }
        if (searchQuery.isNotBlank()) {
            emptyAction.visibility = View.VISIBLE
            emptyAction.text = "Try code lookup for \u201c$searchQuery\u201d"
            emptyAction.setOnClickListener { downloadByCode(searchQuery) }
        } else {
            emptyAction.visibility = View.GONE
        }
    }

    private fun showSortDialog() {
        val labels = arrayOf("Most liked", "Newest", "A–Z", "Z–A")
        val values = arrayOf(SortMode.MOST_LIKED, SortMode.NEWEST, SortMode.A_TO_Z, SortMode.Z_TO_A)
        val checked = values.indexOf(currentSort).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Sort wallpapers")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                currentSort = values[which]
                rebuildOrders()
                renderCurrentSection()
                dialog.dismiss()
            }
            .show()
    }

    private fun renderCategoryRail() {
        categoryRow.removeAllViews()
        val allSelected = currentSection == Section.BROWSE && currentCategory == null
        categoryRow.addView(sectionChip("✨", "All", allSelected) { switchSection(Section.BROWSE) })
        categoryRow.addView(sectionChip("❤️", "Favourites", currentSection == Section.FAVORITES) { switchSection(Section.FAVORITES) })
        categories.forEach { category ->
            val selected = currentSection == Section.BROWSE && currentCategory == category.slug
            categoryRow.addView(sectionChip(categoryIcon(category.slug), category.title, selected) {
                switchSection(Section.BROWSE, category.slug)
            })
        }
        categoryRow.addView(sortChip())
        categoryRow.post { updateRailHint() }
    }

    private fun categoryIcon(slug: String): String = when (slug) {
        "anime" -> "🌸"
        "nature" -> "🍃"
        "devotional" -> "🙏"
        "vehicles" -> "🚗"
        "neon" -> "🌀"
        else -> "🖼"
    }

    private fun badgeColor(slug: String): Int = when (slug) {
        "anime" -> 0xFF7C4DFF.toInt()
        "nature" -> 0xFF2FA35B.toInt()
        "devotional" -> 0xFFF0921E.toInt()
        "vehicles" -> 0xFF2F6BFF.toInt()
        "neon" -> 0xFFEC4899.toInt()
        else -> 0xFF6B7280.toInt()
    }

    /**
     * Card titles arrive prefixed with their series or category ("Anime Bleach: Ichigo Kurosaki"),
     * which the badge right above already says. Strip that lead-in so the card reads as a name.
     */
    private fun displayTitle(pack: WallpaperPack): String {
        val afterColon = pack.title.substringAfter(':', pack.title).trim().ifBlank { pack.title }
        val categoryWord = WallpaperCategory.titleFor(pack.category).substringBefore(' ')
        val trimmed = if (afterColon.length > categoryWord.length + 1 &&
            afterColon.startsWith("$categoryWord ", ignoreCase = true)
        ) afterColon.substring(categoryWord.length + 1) else afterColon
        return trimmed.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }
    }

    private fun orderedPacksForCurrentSection(): List<WallpaperPack> {
        val byId = allPacks.associateBy { it.id }
        val orderedIds = when (currentSection) {
            Section.BROWSE -> browseOrder
            Section.FAVORITES -> browseOrder.filter { favoriteIds.contains(it) }
        }
        return orderedIds.mapNotNull { byId[it] }
            .filter { currentSection != Section.BROWSE || currentCategory == null || it.category == currentCategory }
    }

    private fun matchesSearch(pack: WallpaperPack): Boolean {
        if (searchQuery.isBlank()) return true
        val q = searchQuery.lowercase()
        return pack.title.lowercase().contains(q) || pack.code.lowercase().contains(q)
    }

    private inner class GalleryViewHolder(
        val card: LinearLayout,
        val image: ImageView,
        val badge: TextView,
        val titleOverlay: TextView,
        val likeButton: TextView,
        val meta: TextView,
        val hint: TextView
    ) : RecyclerView.ViewHolder(card)

    /**
     * Replaces the previous GridLayout, which inflated a card and started a full-resolution preview
     * download for every pack in the gallery at once and threw them all away on each re-render.
     */
    private inner class GalleryAdapter : RecyclerView.Adapter<GalleryViewHolder>() {
        private val items = mutableListOf<WallpaperPack>()
        private var favouriteSnapshot: Set<String> = emptySet()

        fun submit(next: List<WallpaperPack>) {
            val nextFavourites = favoriteIds
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = items.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) = items[oldPos].id == next[newPos].id
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val old = items[oldPos]
                    val fresh = next[newPos]
                    return old == fresh &&
                        favouriteSnapshot.contains(old.id) == nextFavourites.contains(fresh.id)
                }
            })
            items.clear()
            items.addAll(next)
            favouriteSnapshot = nextFavourites
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
            val cardWidth = gridCardWidth()
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                background = roundedRect(0xFFFFFFFF.toInt(), 0xFFEFE9F5.toInt(), dp(20), 1)
                elevation = dp(2).toFloat()
                setPadding(dp(6), dp(6), dp(6), dp(12))
                foreground = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
                    val drawable = it.getDrawable(0); it.recycle(); drawable
                }
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(6), dp(6), dp(6), dp(12)) }
            }
            val imageFrame = FrameLayout(this@MainActivity).apply {
                clipToOutline = true
                background = roundedRect(0xFFEFEAF7.toInt(), 0x00000000, dp(15), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (cardWidth * CARD_IMAGE_RATIO).toInt()
                )
            }
            val image = TopCropImageView(this@MainActivity).apply {
                adjustViewBounds = false
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val scrim = View(this@MainActivity).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x00000000, 0x59000000, 0xD9000000.toInt())
                )
            }
            val badge = TextView(this@MainActivity).apply {
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(9), dp(3), dp(9), dp(4))
            }
            val titleOverlay = TextView(this@MainActivity).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, dp(6), 0, 0)
            }
            val overlay = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(56), dp(10))
                addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(titleOverlay)
            }
            val likeButton = TextView(this@MainActivity).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = roundedRect(0x66000000, 0x00000000, dp(999), 0)
                setPadding(dp(9), dp(5), dp(9), dp(5))
            }
            imageFrame.addView(image)
            imageFrame.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104), Gravity.BOTTOM))
            imageFrame.addView(overlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ))
            imageFrame.addView(likeButton, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                bottomMargin = dp(10)
                rightMargin = dp(10)
            })
            val meta = TextView(this@MainActivity).apply {
                textSize = 12f
                setSingleLine(true)
                setTextColor(0xFF6B6478.toInt())
                setPadding(dp(6), dp(10), dp(6), 0)
                compoundDrawablePadding = dp(6)
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_eye, 0, 0, 0)
            }
            val hint = TextView(this@MainActivity).apply {
                textSize = 11f
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(0xFF9A93A6.toInt())
                setPadding(dp(6), dp(4), dp(6), 0)
            }
            card.addView(imageFrame)
            card.addView(meta)
            card.addView(hint)
            return GalleryViewHolder(card, image, badge, titleOverlay, likeButton, meta, hint)
        }

        override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
            val pack = items[position]
            val isFav = favoriteIds.contains(pack.id)
            holder.card.setOnClickListener { downloadPackAndOpenSetter(pack) }
            holder.badge.text = WallpaperCategory.titleFor(pack.category)
            holder.badge.background = roundedRect(badgeColor(pack.category), 0x00000000, dp(999), 0)
            holder.titleOverlay.text = displayTitle(pack)
            holder.likeButton.text = if (isFav) "\u2665 ${pack.likeCount}" else "\u2661 ${pack.likeCount}"
            holder.likeButton.setTextColor(if (isFav) 0xFFFF6B93.toInt() else 0xFFFFFFFF.toInt())
            holder.likeButton.setOnClickListener { toggleFavorite(pack, !favoriteIds.contains(pack.id)) }
            holder.meta.text = "${pack.viewCount} views"
            holder.hint.text = "Code: ${pack.code}"

            val url = repository.absoluteUrl(pack.previewUrl)
            if (holder.image.tag != url) {
                holder.image.tag = url
                holder.image.setImageDrawable(null)
                holder.image.load(url) {
                    crossfade(true)
                    allowHardware(false)
                    // Previews are full 1344x2992 wallpapers (several MB each). Decoding them at card
                    // size instead of full size is the difference between ~16 MB and ~2 MB per card.
                    size(previewTargetWidth(), previewTargetHeight())
                }
            }
        }

        override fun onViewRecycled(holder: GalleryViewHolder) {
            holder.image.setImageDrawable(null)
            holder.image.tag = null
        }
    }

    private fun previewTargetWidth(): Int = gridCardWidth()

    private fun previewTargetHeight(): Int = (gridCardWidth() * CARD_IMAGE_RATIO).toInt()

    private fun gridCardWidth(): Int {
        val screen = resources.displayMetrics.widthPixels
        val listPadding = dp(12) * 2
        val cardMargins = dp(6) * 4
        return ((screen - listPadding - cardMargins) / 2).coerceAtLeast(dp(148))
    }

    private fun toggleFavorite(pack: WallpaperPack, liked: Boolean) {
        favoriteIds = if (liked) favoriteIds + pack.id else favoriteIds - pack.id
        allPacks = allPacks.map {
            if (it.id == pack.id) it.copy(
                likeCount = (it.likeCount + if (liked) 1 else -1).coerceAtLeast(0),
                likeUserCount = (it.likeUserCount + if (liked) 1 else -1).coerceAtLeast(0)
            ) else it
        }
        renderCurrentSection()
        scope.launch(Dispatchers.IO) { repository.setFavorite(pack.id, liked) }
    }

    private fun createEmptyState(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        emptyText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(80), 0, dp(12))
        }
        emptyAction = Button(this).apply { visibility = View.GONE }
        container.addView(emptyText)
        container.addView(emptyAction)
        return container
    }

    private fun sectionChip(icon: String, label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = "$icon  $label"
            textSize = 14f
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF342A45.toInt())
            setPadding(dp(15), dp(11), dp(17), dp(11))
            background = roundedRect(
                if (selected) 0xFF7C4DFF.toInt() else 0xFFFFFFFF.toInt(),
                if (selected) 0xFF7C4DFF.toInt() else 0xFFE9E2F0.toInt(),
                dp(999),
                1
            )
            elevation = if (selected) dp(2).toFloat() else dp(1).toFloat()
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(8), 0) }
        }
    }

    /** The mock has no sort control; keeping it on the rail is the least intrusive home for it. */
    private fun sortChip(): TextView {
        return TextView(this).apply {
            text = "⇅  ${sortLabel(currentSort)}"
            textSize = 13f
            setTextColor(0xFF6B6478.toInt())
            setPadding(dp(14), dp(11), dp(16), dp(11))
            background = roundedRect(0x00000000, 0xFFDFD7E9.toInt(), dp(999), 1)
            setOnClickListener { showSortDialog() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(8), 0) }
        }
    }

    private fun sortLabel(mode: SortMode): String = when (mode) {
        SortMode.MOST_LIKED -> "Most liked"
        SortMode.NEWEST -> "Newest"
        SortMode.A_TO_Z -> "A–Z"
        SortMode.Z_TO_A -> "Z–A"
    }

    private fun showCodeDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(8))
        }
        val helper = TextView(this).apply {
            text = "Paste your wallpaper code below. Hidden/code-only wallpapers are supported."
            textSize = 14f
            setPadding(0, 0, 0, dp(14))
        }
        val input = EditText(this).apply {
            hint = "Wallpaper code"
            setSingleLine(true)
            textSize = 16f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(0xFFF6F1FA.toInt(), 0xFFCAC4D0.toInt(), dp(14), 1)
        }
        container.addView(helper)
        container.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Download by code")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.isBlank()) input.error = "Enter a code" else {
                    dialog.dismiss()
                    downloadByCode(code)
                }
            }
        }
        dialog.show()
    }

    private fun downloadByCode(code: String) {
        showProgress("Looking up wallpaper code…")
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.fetchPackByCode(code) } }
            result.onSuccess { pack ->
                if (allPacks.none { it.id == pack.id }) {
                    allPacks = listOf(pack) + allPacks
                    rebuildOrders()
                }
                downloadPackAndOpenSetter(pack)
            }.onFailure { error -> hideProgress(friendlyFailure("Code lookup failed.", error)) }
        }
    }

    private fun downloadPackAndOpenSetter(pack: WallpaperPack) {
        if (isDownloading) return
        isDownloading = true
        showProgress("Starting download for ${pack.title}…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repository.recordPackView(pack.id)
                    repository.fetchAndCacheScene(pack.sceneUrl) { completed, total ->
                        scope.launch(Dispatchers.Main) {
                            if (total > 0) showProgress("Downloading ${pack.title} · $completed/$total", completed, total)
                            else showProgress("Downloading ${pack.title}…")
                        }
                    }
                }
            }
            isDownloading = false
            result.onSuccess { scene ->
                allPacks = allPacks.map { if (it.id == pack.id) it.copy(viewCount = it.viewCount + 1) else it }
                repository.markScenePending(scene)
                hideProgress("Downloaded ${scene.title}")
                openWallpaperPicker()
            }.onFailure { error -> hideProgress(friendlyFailure("Download failed.", error)) }
        }
    }

    private fun deriveCategories(packs: List<WallpaperPack>): List<WallpaperCategory> {
        val counts = packs.groupingBy { it.category }.eachCount()
        return WallpaperCategory.fallback
            .mapNotNull { base -> counts[base.slug]?.takeIf { it > 0 }?.let { base.copy(count = it) } }
            .ifEmpty { counts.map { (slug, count) -> WallpaperCategory(slug, WallpaperCategory.titleFor(slug), count) } }
    }

    private fun sortTime(pack: WallpaperPack): Long {
        return pack.publishedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L) } ?: 0L
    }

    private fun createLoadingOverlay(): FrameLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = roundedRect(0xFFFFFFFF.toInt(), 0xFFD0C7E8.toInt(), dp(18), 1)
        }
        overlayStatus = TextView(this).apply {
            text = "Loading…"
            textSize = 16f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }
        overlayProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
        }
        panel.addView(overlayStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(overlayProgress)
        return FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            setBackgroundColor(0x99000000.toInt())
            addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                leftMargin = dp(28)
                rightMargin = dp(28)
            })
        }
    }

    private fun showProgress(message: String, value: Int? = null, max: Int? = null) {
        status.text = message
        status.visibility = View.VISIBLE
        overlayStatus.text = message
        progress.visibility = View.VISIBLE
        loadingOverlay.visibility = View.VISIBLE
        if (value != null && max != null && max > 0) {
            progress.isIndeterminate = false
            progress.max = max
            progress.progress = value.coerceIn(0, max)
            overlayProgress.isIndeterminate = false
            overlayProgress.max = max
            overlayProgress.progress = value.coerceIn(0, max)
        } else {
            progress.isIndeterminate = true
            overlayProgress.isIndeterminate = true
        }
    }

    private fun hideProgress(message: String) {
        status.text = message
        status.visibility = if (message == IDLE_STATUS) View.GONE else View.VISIBLE
        progress.visibility = View.GONE
        progress.isIndeterminate = false
        progress.progress = 0
        loadingOverlay.visibility = View.GONE
        overlayProgress.isIndeterminate = false
        overlayProgress.progress = 0
    }

    private fun friendlyFailure(prefix: String, error: Throwable): String {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: "Server may be offline or unreachable. Please try again."
        return "$prefix $detail"
    }

    private fun openWallpaperPicker() {
        val component = ComponentName(this, DepthWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        runCatching { startActivity(intent) }.onFailure { openLiveWallpaperChooser() }
    }

    private fun openLiveWallpaperChooser() {
        runCatching { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedRect(color: Int, strokeColor: Int, radius: Int, strokeDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val IDLE_STATUS = "Pull down to refresh"
        /**
         * Card image height as a fraction of card width. The mock crops cards to roughly 3:2
         * landscape, but every preview is a portrait wallpaper (1344x2992), so a wide card keeps a
         * thin band of it; taller cards show the subject. Paired with TopCropImageView.
         */
        private const val CARD_IMAGE_RATIO = 1.3f
        private const val HERO_HEIGHT_DP = 150
        private val PAGE_BACKGROUND = 0xFFF8F6FB.toInt()
        private const val MENU_BROWSE = 2
        private const val MENU_FAVORITES = 3
        private const val MENU_SEARCH = 4
        private const val MENU_CODE = 5
        private const val MENU_REFRESH = 6
    }
}
