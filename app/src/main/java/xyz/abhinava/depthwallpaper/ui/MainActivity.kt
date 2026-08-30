package xyz.abhinava.depthwallpaper.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.abhinava.depthwallpaper.data.SceneRepository
import xyz.abhinava.depthwallpaper.data.WallpaperCategory
import xyz.abhinava.depthwallpaper.data.WallpaperPack
import xyz.abhinava.depthwallpaper.wallpaper.DepthWallpaperService
import java.time.Instant

class MainActivity : Activity() {
    private enum class Section { FEATURED, BROWSE, FAVORITES }
    private enum class SortMode { MOST_LIKED, NEWEST, A_TO_Z, Z_TO_A }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var categoryRow: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var galleryContainer: GridLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var overlayStatus: TextView
    private lateinit var overlayProgress: ProgressBar
    private lateinit var repository: SceneRepository

    private var allPacks: List<WallpaperPack> = emptyList()
    private var categories: List<WallpaperCategory> = WallpaperCategory.fallback
    private var favoriteIds: Set<String> = emptySet()
    private var browseOrder: List<String> = emptyList()
    private var highlightedIds: List<String> = emptyList()
    private var currentSection = Section.FEATURED
    private var currentCategory: String? = null
    private var currentSort = SortMode.MOST_LIKED
    private var isSearchVisible = false
    private var searchQuery = ""
    private var isRefreshing = false
    private var isDownloading = false
    private var pullStartY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Daily Live Wallpapers"
        actionBar?.hide()
        repository = SceneRepository(this)
        favoriteIds = repository.loadFavoriteIds()

        scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_ALWAYS }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
            setBackgroundColor(0xFFF8F6FB.toInt())
        }
        status = TextView(this).apply {
            text = "Loading gallery…"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF756B7E.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
                setMargins(0, 0, 0, dp(12))
            }
        }
        searchInput = EditText(this).apply {
            hint = "Search name or wallpaper code"
            setSingleLine(true)
            visibility = View.GONE
            textSize = 16f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedRect(0xFFFFFFFF.toInt(), 0xFFE4DCE9.toInt(), dp(18), 1)
            setOnEditorActionListener { _, _, _ -> applySearch(text?.toString().orEmpty()); true }
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) applySearch(text?.toString().orEmpty()) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { applySearch(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(12), dp(12))
        }
        galleryContainer = GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        content.addView(createHeader())
        content.addView(status)
        content.addView(progress)
        content.addView(searchInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12))
        })
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(categoryRow)
        })
        content.addView(galleryContainer)
        scroll.addView(content)
        loadingOverlay = createLoadingOverlay()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFFF8F6FB.toInt())
            addView(scroll)
            addView(loadingOverlay)
        })
        applyInsets()
        installPullToRefresh()

        val cached = repository.loadCachedGallery()
        if (cached.isNotEmpty()) {
            allPacks = cached
            rebuildOrders()
            hideProgress("Pull down to refresh")
            renderCurrentSection()
        }
        refreshGallery(showLoading = cached.isEmpty())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_FEATURED, 0, "Featured").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_BROWSE, 1, "Browse").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_FAVORITES, 2, "Favourites").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_SEARCH, 3, "Search").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_CODE, 4, "Enter wallpaper code").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_REFRESH, 5, "Refresh").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_FEATURED -> { switchSection(Section.FEATURED); true }
            MENU_BROWSE -> { switchSection(Section.BROWSE); true }
            MENU_FAVORITES -> { switchSection(Section.FAVORITES); true }
            MENU_SEARCH -> { toggleSearch(); true }
            MENU_CODE -> { showCodeDialog(); true }
            MENU_REFRESH -> { refreshGallery(showLoading = true); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun applyInsets() {
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val system = insets.getInsets(WindowInsets.Type.systemBars())
            content.setPadding(dp(18), dp(14) + system.top, dp(18), dp(28) + system.bottom)
            insets
        }
    }

    private fun createHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        val spacer = FrameLayout(this)
        val titleView = TextView(this).apply {
            text = "Live Wallpapers"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF16121D.toInt())
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedRect(0xFFFFFFFF.toInt(), 0xFFE7DFEE.toInt(), dp(999), 1)
            elevation = dp(3).toFloat()
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        actions.addView(headerIcon("🔍") { toggleSearch() })
        actions.addView(headerIcon("↕") { showSortDialog() })
        header.addView(spacer, LinearLayout.LayoutParams(0, dp(46), 1f))
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        header.addView(actions, LinearLayout.LayoutParams(0, dp(46), 1f).apply { gravity = Gravity.END })
        return header
    }

    private fun headerIcon(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(0xFF201A2B.toInt())
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(38), ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun installPullToRefresh() {
        scroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (scroll.scrollY == 0) pullStartY = event.rawY
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val pulled = event.rawY - pullStartY
                    if (!isRefreshing && scroll.scrollY == 0 && pulled > 180f) {
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
                hideProgress("Pull down to refresh")
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
        isSearchVisible = !isSearchVisible
        searchInput.visibility = if (isSearchVisible) View.VISIBLE else View.GONE
        if (isSearchVisible) {
            searchInput.requestFocus()
        } else {
            searchInput.setText("")
            applySearch("")
        }
    }

    private fun applySearch(query: String) {
        searchQuery = query.trim()
        renderCurrentSection()
    }

    private fun rebuildOrders() {
        browseOrder = sortedBrowsePacks(allPacks).map { it.id }
        highlightedIds = allPacks
            .sortedWith(compareByDescending<WallpaperPack> { it.likeCount }
                .thenByDescending { it.viewCount }
                .thenByDescending { sortTime(it) })
            .take(HIGHLIGHT_COUNT)
            .map { it.id }
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
        val ordered = orderedPacksForCurrentSection()
        val filtered = ordered.filter { matchesSearch(it) }
        galleryContainer.removeAllViews()
        if (filtered.isEmpty()) {
            galleryContainer.addView(emptyState())
            if (searchQuery.isNotBlank()) galleryContainer.addView(codeLookupButton(searchQuery))
            return
        }
        for (pack in filtered) galleryContainer.addView(galleryItem(pack))
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
        categoryRow.addView(sectionChip("✦ Featured", currentSection == Section.FEATURED) { switchSection(Section.FEATURED) })
        categoryRow.addView(sectionChip("▦ Browse", currentSection == Section.BROWSE && currentCategory == null) { switchSection(Section.BROWSE) })
        categoryRow.addView(sectionChip("♥ Favourites", currentSection == Section.FAVORITES) { switchSection(Section.FAVORITES) })
        if (currentSection == Section.BROWSE) {
            categories.forEach { category ->
                categoryRow.addView(sectionChip("${categoryIcon(category.slug)} ${category.title}", currentCategory == category.slug) { switchSection(Section.BROWSE, category.slug) })
            }
        }
    }

    private fun categoryIcon(slug: String): String = when (slug) {
        "anime" -> "⚡"
        "nature" -> "🌿"
        "devotional" -> "🛕"
        "vehicles" -> "🏎"
        "neon" -> "✦"
        else -> "•"
    }

    private fun orderedPacksForCurrentSection(): List<WallpaperPack> {
        val byId = allPacks.associateBy { it.id }
        val orderedIds = when (currentSection) {
            Section.FEATURED -> highlightedIds
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

    private fun galleryItem(pack: WallpaperPack): LinearLayout {
        val isFav = favoriteIds.contains(pack.id)
        val cardWidth = gridCardWidth()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
            isClickable = true
            isFocusable = true
            foreground = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
                val drawable = it.getDrawable(0); it.recycle(); drawable
            }
            setOnClickListener { downloadPackAndOpenSetter(pack) }
            layoutParams = GridLayout.LayoutParams().apply {
                width = cardWidth
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(dp(4), dp(4), dp(4), dp(16))
            }
        }
        val imageFrame = FrameLayout(this).apply {
            clipToOutline = true
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (cardWidth * 1.58f).toInt())
            background = roundedRect(0xFFEFEAF7.toInt(), 0x00000000, dp(24), 0)
        }
        val image = ImageView(this).apply {
            adjustViewBounds = false
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val titleOverlay = TextView(this).apply {
            text = pack.title
            maxLines = 2
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(30), dp(52), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x66000000, 0xE0000000.toInt()))
        }
        val likeButton = Button(this).apply {
            text = if (isFav) "♥ ${pack.likeCount}" else "♡ ${pack.likeCount}"
            textSize = 12f
            setTextColor(if (isFav) 0xFFE91E63.toInt() else 0xFF2D2533.toInt())
            background = roundedRect(0xEFFFFFFF.toInt(), 0x99FFFFFF.toInt(), dp(999), 1)
            minHeight = 0
            minWidth = 0
            setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener {
                val liked = !favoriteIds.contains(pack.id)
                toggleFavorite(pack, liked)
            }
        }
        imageFrame.addView(image)
        imageFrame.addView(titleOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96), Gravity.BOTTOM))
        imageFrame.addView(likeButton, FrameLayout.LayoutParams(dp(72), dp(42), Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(10)
            rightMargin = dp(8)
        })

        val meta = TextView(this).apply {
            text = "${WallpaperCategory.titleFor(pack.category)} · ${pack.viewCount} views"
            textSize = 11f
            setTextColor(0xFF62596A.toInt())
            setPadding(dp(2), dp(7), dp(2), dp(1))
        }
        val hint = TextView(this).apply {
            text = "Code: ${pack.code}"
            textSize = 10f
            maxLines = 1
            setTextColor(0xFF8A8190.toInt())
            setPadding(dp(2), 0, dp(2), dp(2))
        }
        card.addView(imageFrame)
        card.addView(meta)
        card.addView(hint)

        image.load(repository.absoluteUrl(pack.previewUrl)) {
            crossfade(true)
            allowHardware(false)
        }
        return card
    }

    private fun gridCardWidth(): Int {
        val screen = resources.displayMetrics.widthPixels
        val horizontalPadding = dp(18) * 2
        val gap = dp(16)
        return ((screen - horizontalPadding - gap) / 2).coerceAtLeast(dp(148))
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

    private fun emptyState(): TextView {
        return TextView(this).apply {
            text = when {
                searchQuery.isNotBlank() -> "No listed wallpaper matched “$searchQuery”."
                currentSection == Section.FAVORITES -> "No favourites yet. Tap ♡ on wallpapers you love."
                else -> "No wallpapers here yet. Pull down to refresh."
            }
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(80), 0, dp(12))
        }
    }

    private fun codeLookupButton(code: String): Button {
        return Button(this).apply {
            text = "Try code lookup for “$code”"
            setOnClickListener { downloadByCode(code) }
        }
    }

    private fun sectionChip(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF342A45.toInt())
            setPadding(dp(15), dp(10), dp(15), dp(10))
            background = roundedRect(if (selected) 0xFF7C4DFF.toInt() else 0xFFFFFFFF.toInt(), if (selected) 0xFF7C4DFF.toInt() else 0xFFE6DEEA.toInt(), dp(999), 1)
            elevation = if (selected) dp(2).toFloat() else 0f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
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
        private const val HIGHLIGHT_COUNT = 5
        private const val MENU_FEATURED = 1
        private const val MENU_BROWSE = 2
        private const val MENU_FAVORITES = 3
        private const val MENU_SEARCH = 4
        private const val MENU_CODE = 5
        private const val MENU_REFRESH = 6
    }
}
