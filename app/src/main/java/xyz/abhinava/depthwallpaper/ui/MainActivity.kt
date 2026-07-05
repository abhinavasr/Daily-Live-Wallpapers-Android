package xyz.abhinava.depthwallpaper.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.graphics.drawable.GradientDrawable
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.abhinava.depthwallpaper.data.SceneRepository
import xyz.abhinava.depthwallpaper.data.WallpaperPack
import xyz.abhinava.depthwallpaper.wallpaper.DepthWallpaperService

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var galleryContainer: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var overlayStatus: TextView
    private lateinit var overlayProgress: ProgressBar
    private lateinit var repository: SceneRepository
    private var isRefreshing = false
    private var isDownloading = false
    private var pullStartY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Daily Live Wallpapers"
        actionBar?.subtitle = "Free wallpapers today"
        repository = SceneRepository(this)

        scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_ALWAYS
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(88), dp(18), dp(28))
        }
        status = TextView(this).apply {
            text = "Loading gallery…"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 10)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
                setMargins(0, 0, 0, dp(16))
            }
        }
        galleryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        content.addView(status)
        content.addView(progress)
        content.addView(galleryContainer)
        scroll.addView(content)
        loadingOverlay = createLoadingOverlay()
        val root = FrameLayout(this).apply {
            addView(scroll)
            addView(loadingOverlay)
        }
        setContentView(root)
        applyInsets()
        installPullToRefresh()

        val cached = repository.loadCachedGallery()
        if (cached.isNotEmpty()) {
            hideProgress("Pull down to refresh · ${cached.size} wallpapers")
            renderGallery(cached)
        }
        refreshGallery(showLoading = cached.isEmpty())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CODE, 0, "Enter wallpaper code").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_REFRESH, 1, "Refresh").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
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
            // Extra top padding keeps the first gallery card clear of the native action/status bars.
            content.setPadding(dp(18), dp(88), dp(18), dp(28) + system.bottom)
            insets
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
            val result = withContext(Dispatchers.IO) { runCatching { repository.fetchGallery() } }
            isRefreshing = false
            result.onSuccess { packs ->
                hideProgress("Pull down to refresh · ${packs.size} wallpapers")
                renderGallery(packs)
            }.onFailure { error ->
                val cached = repository.loadCachedGallery()
                if (cached.isNotEmpty()) renderGallery(cached)
                hideProgress(friendlyFailure("Refresh failed. Showing saved gallery.", error))
            }
        }
    }

    private fun renderGallery(packs: List<WallpaperPack>) {
        galleryContainer.removeAllViews()
        if (packs.isEmpty()) {
            galleryContainer.addView(TextView(this).apply {
                text = "No wallpapers yet. Pull down to refresh."
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 80, 0, 0)
            })
            return
        }
        for (pack in packs) galleryContainer.addView(galleryItem(pack))
    }

    private fun galleryItem(pack: WallpaperPack): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 34)
            isClickable = true
            isFocusable = true
            foreground = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
                val drawable = it.getDrawable(0); it.recycle(); drawable
            }
            setOnClickListener { downloadPackAndOpenSetter(pack) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val image = ImageView(this).apply {
            adjustViewBounds = false
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 680)
            setBackgroundColor(0xFFEFEAF7.toInt())
        }
        val title = TextView(this).apply {
            text = pack.title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 12, 0, 0)
        }
        val meta = TextView(this).apply {
            text = "Free · Code: ${pack.code}"
            textSize = 12f
            setPadding(0, 3, 0, 8)
        }
        val hint = TextView(this).apply {
            text = "Tap to preview and set"
            textSize = 13f
            setPadding(0, 0, 0, 2)
        }
        card.addView(image)
        card.addView(title)
        card.addView(meta)
        card.addView(hint)

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { repository.fetchBitmap(pack.previewUrl) }
            if (bitmap != null) image.setImageBitmap(bitmap)
        }
        return card
    }

    private fun showCodeDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(8))
        }
        val helper = TextView(this).apply {
            text = "Paste your wallpaper code below. All wallpapers are free today."
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
                if (code.isBlank()) {
                    input.error = "Enter a code"
                } else {
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
            result.onSuccess { pack -> downloadPackAndOpenSetter(pack) }
                .onFailure { error -> hideProgress(friendlyFailure("Code lookup failed.", error)) }
        }
    }

    private fun downloadPackAndOpenSetter(pack: WallpaperPack) {
        if (isDownloading) return
        isDownloading = true
        showProgress("Starting download for ${pack.title}…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repository.fetchAndCacheScene(pack.sceneUrl) { completed, total ->
                        scope.launch(Dispatchers.Main) {
                            if (total > 0) {
                                showProgress("Downloading ${pack.title} · $completed/$total", completed, total)
                            } else {
                                showProgress("Downloading ${pack.title}…")
                            }
                        }
                    }
                }
            }
            isDownloading = false
            result.onSuccess { scene ->
                hideProgress("Downloaded ${scene.title}")
                openWallpaperPicker()
            }.onFailure { error ->
                hideProgress(friendlyFailure("Download failed.", error))
            }
        }
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
            setStroke(dp(strokeDp), strokeColor)
        }
    }

    companion object {
        private const val MENU_CODE = 1
        private const val MENU_REFRESH = 2
    }
}
