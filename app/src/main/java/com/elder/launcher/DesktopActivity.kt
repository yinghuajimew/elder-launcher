package com.elder.launcher

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import com.elder.launcher.base.BaseActivity
import com.elder.launcher.desktop.AnalogClockView
import com.elder.launcher.desktop.AppGridAdapter
import com.elder.launcher.desktop.ClockSettings
import com.elder.launcher.desktop.DesktopApps
import com.elder.launcher.desktop.DesktopSettings
import com.elder.launcher.desktop.DesktopTile
import com.elder.launcher.desktop.PageScrollView
import com.elder.launcher.desktop.TileType
import com.elder.launcher.keepalive.LockState
import com.elder.launcher.lunar.LunarCalendar
import com.elder.launcher.player.CoverStore
import com.elder.launcher.player.Playlist
import com.elder.launcher.player.VideoEntry
import com.elder.launcher.player.VideoPlayerActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 基础桌面（HOME）：固定时钟 + 磁贴网格（应用/视频，可设列数行数、超出一页横向翻页）+ 设置/退出入口。
 * 点击磁贴启动应用或播放视频；长按拖动排序 / 拖到删除区移除。
 * 「+」可选择添加应用或视频。
 */
class DesktopActivity : BaseActivity() {

    private lateinit var scrollView: PageScrollView
    private lateinit var pagesContainer: LinearLayout
    private lateinit var tiles: MutableList<DesktopTile>
    private lateinit var deleteZone: TextView

    private var columns = 3
    private var rows = 3
    private var pageSize = 9
    private var pages: List<List<DesktopTile>> = emptyList()
    private val pageGrids = mutableListOf<GridView>()

    private var dragIndex = -1
    private var dragTarget = -1
    private var dragPage = -1
    private var deleteZoneActive = false
    private var pendingCoverEntries: List<VideoEntry>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_desktop)

        updateDateTime()
        renderClock()

        scrollView = findViewById(R.id.scroll_pages)
        pagesContainer = findViewById(R.id.pages_container)
        deleteZone = findViewById(R.id.tv_delete_zone)

        deleteZone.setOnDragListener { _, event -> handleDeleteZoneDrag(event) }
        findViewById<View>(R.id.root).setOnDragListener { _, event -> handleRootDrag(event) }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.putExtra(MainActivity.EXTRA_AS_SETTINGS, true)
            startActivity(i)
        }

        findViewById<Button>(R.id.btn_exit_lock).setOnLongClickListener {
            val next = !LockState.lockEnabled(this)
            LockState.setLockEnabled(this, next)
            Toast.makeText(
                this,
                if (next) getString(R.string.toast_lock_on) else getString(R.string.toast_lock_off),
                Toast.LENGTH_SHORT
            ).show()
            refreshExitButton()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        updateDateTime()
        renderClock()
        reloadAdapter()
        refreshExitButton()
    }

    private fun updateDateTime() {
        findViewById<TextView>(R.id.tv_date).text =
            SimpleDateFormat("M月d日 EEEE", Locale.CHINESE).format(Date())
        findViewById<TextView>(R.id.tv_lunar).text = LunarCalendar.todayText()
    }

    /** 按设置渲染时钟区：数字 / 指针 / 双时钟。 */
    private fun renderClock() {
        val container = findViewById<LinearLayout>(R.id.clock_container)
        container.removeAllViews()
        val mode = ClockSettings.mode(this)
        val shape = ClockSettings.shape(this)
        val digitalLeft = ClockSettings.digitalLeft(this)

        when (mode) {
            ClockSettings.MODE_ANALOG -> container.addView(analogClock(shape, 240f))
            ClockSettings.MODE_BOTH -> {
                val digital = digitalClock(44f)
                val analog = analogClock(shape, 160f)
                if (digitalLeft) {
                    container.addView(digital)
                    container.addView(analog)
                } else {
                    container.addView(analog)
                    container.addView(digital)
                }
            }
            else -> container.addView(digitalClock(80f))
        }
    }

    private fun digitalClock(sizeSp: Float): TextClock = TextClock(this).apply {
        format12Hour = "a h:mm"
        format24Hour = "HH:mm"
        setTextColor(0xFF1A5F7A.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTypeface(Typeface.DEFAULT_BOLD)
        gravity = android.view.Gravity.CENTER
    }

    private fun analogClock(shape: String, sizeDp: Float): AnalogClockView {
        val size = (sizeDp * resources.displayMetrics.density).toInt()
        return AnalogClockView(this).apply {
            this.shape = if (shape == ClockSettings.SHAPE_ROUNDED)
                AnalogClockView.Shape.ROUNDED_SQUARE else AnalogClockView.Shape.CIRCLE
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 按网格密度计算图标大小（网格越少图标越大）。 */
    private fun iconSizeDp(): Float {
        val m = resources.displayMetrics
        val widthDp = m.widthPixels / m.density
        val heightDp = m.heightPixels / m.density
        val cellW = (widthDp - 40f) / columns
        val cellH = (heightDp * 0.45f) / rows
        val cell = minOf(cellW, cellH)
        return (cell * 0.6f).coerceIn(40f, 180f)
    }

    /** 应用名文字大小，随图标大小缩放。 */
    private fun labelSizeSp(): Float = (iconSizeDp() * 0.22f).coerceIn(11f, 30f)

    private fun reloadAdapter() {
        tiles = DesktopApps.list(this).toMutableList()
        columns = DesktopSettings.columns(this).coerceAtLeast(1)
        rows = DesktopSettings.rows(this).coerceAtLeast(1)
        pageSize = columns * rows
        renderGrid()
    }

    /** 按当前列数行数把磁贴拆页并渲染。 */
    private fun renderGrid() {
        val width = resources.displayMetrics.widthPixels
        val prevPage = if (width > 0) scrollView.scrollX / width else 0
        pagesContainer.removeAllViews()
        pageGrids.clear()
        pages = buildPages()
        val showAdd = DesktopSettings.showAddTile(this)
        pages.forEachIndexed { i, pageTiles ->
            val grid = createPageGrid(i, pageTiles, i == pages.lastIndex && showAdd, width)
            pageGrids.add(grid)
            pagesContainer.addView(grid)
        }
        scrollView.pageWidth = width
        val targetPage = prevPage.coerceIn(0, pages.size - 1)
        scrollView.scrollTo(targetPage * width, 0)
    }

    private fun buildPages(): List<List<DesktopTile>> {
        val list = tiles.chunked(pageSize).toMutableList()
        if (list.isEmpty()) list.add(emptyList())
        val showAdd = DesktopSettings.showAddTile(this)
        if (showAdd && list.last().size >= pageSize) list.add(emptyList())
        return list
    }

    private fun createPageGrid(pageIndex: Int, pageTiles: List<DesktopTile>, showAdd: Boolean, width: Int): GridView {
        val grid = GridView(this)
        grid.tag = pageIndex
        grid.numColumns = columns
        grid.layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        grid.setPadding(dp(16), dp(8), dp(16), dp(16))
        grid.clipToPadding = false
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.horizontalSpacing = dp(8)
        grid.verticalSpacing = dp(12)
        grid.adapter = AppGridAdapter(this, pageTiles.toMutableList(), showAdd, iconSizeDp(), labelSizeSp())

        grid.setOnItemClickListener { _, _, position, _ ->
            if (showAdd && position == pageTiles.size) {
                showAddDialog()
            } else {
                openTile(pageTiles[position])
            }
        }

        grid.setOnItemLongClickListener { _, view, position, _ ->
            if (position !in pageTiles.indices) return@setOnItemLongClickListener false
            startDrag(pageIndex * pageSize + position, view)
            true
        }

        grid.setOnDragListener { v, event -> handleGridDrag(v as GridView, event) }
        return grid
    }

    // ==================== 磁贴点击 ====================

    private fun openTile(tile: DesktopTile) {
        when (tile.type) {
            TileType.APP -> openApp(tile.payload)
            TileType.VIDEO -> startPlayer(listOf(VideoEntry(tile.payload, tile.label)), tile.payload)
            TileType.PLAYLIST -> startPlayer(Playlist.decode(tile.payload), tile.payload)
        }
    }

    private fun startPlayer(entries: List<VideoEntry>, key: String) {
        startActivity(
            Intent(this, VideoPlayerActivity::class.java)
                .putExtra(VideoPlayerActivity.EXTRA_KEY, key)
                .putExtra(VideoPlayerActivity.EXTRA_PLAYLIST, Playlist.encode(entries))
                .putExtra(VideoPlayerActivity.EXTRA_FROM_TILE, true)
        )
    }

    private fun openApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "无法打开该应用", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 添加 ====================

    private fun showAddDialog() {
        val options = arrayOf(
            getString(R.string.add_app),
            getString(R.string.add_video)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.add_dialog_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, AppPickerActivity::class.java))
                    1 -> pickVideo()
                }
            }
            .show()
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, REQ_PICK_VIDEO)
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_COVER) {
            val entries = pendingCoverEntries
            pendingCoverEntries = null
            if (resultCode == RESULT_OK && entries != null) {
                val uri = data?.data
                if (uri != null) {
                    Thread {
                        val cover = CoverStore.importImage(this, uri) ?: ""
                        runOnUiThread { addPlaylist(entries, cover) }
                    }.start()
                    return
                }
            }
            if (entries != null) addPlaylist(entries, "")
            return
        }
        if (requestCode != REQ_PICK_VIDEO) return
        if (resultCode != RESULT_OK) return

        val uris = mutableListOf<Uri>()
        val clip = data?.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data?.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return

        val entries = mutableListOf<VideoEntry>()
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            entries.add(VideoEntry(u.toString(), queryDisplayName(u)))
        }
        promptCover(entries)
    }

    private fun promptCover(entries: List<VideoEntry>) {
        val options = arrayOf(
            getString(R.string.cover_auto),
            getString(R.string.cover_pick),
            getString(R.string.cover_default)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cover_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val uri = Uri.parse(entries.first().uri)
                        Thread {
                            val cover = CoverStore.captureFromVideo(this, uri) ?: ""
                            runOnUiThread { addPlaylist(entries, cover) }
                        }.start()
                    }
                    1 -> pickCoverImage(entries)
                    else -> addPlaylist(entries, "")
                }
            }
            .show()
    }

    private fun pickCoverImage(entries: List<VideoEntry>) {
        pendingCoverEntries = entries
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(intent, REQ_PICK_COVER)
        } catch (_: Exception) {
            pendingCoverEntries = null
            addPlaylist(entries, "")
        }
    }

    private fun addPlaylist(entries: List<VideoEntry>, cover: String) {
        DesktopApps.addPlaylist(this, entries, buildPlaylistLabel(entries), cover)
        reloadAdapter()
    }

    private fun buildPlaylistLabel(entries: List<VideoEntry>): String {
        if (entries.isEmpty()) return ""
        val first = entries.first().name.ifEmpty { getString(R.string.playlist_unnamed, 1) }
        return if (entries.size == 1) first
        else getString(R.string.playlist_label_many, first, entries.size)
    }

    private fun queryDisplayName(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) ?: "" else ""
        } ?: ""
    } catch (_: Exception) {
        ""
    }

    // ==================== 拖动 ====================

    private fun startDrag(globalIndex: Int, view: View) {
        dragIndex = globalIndex
        dragTarget = globalIndex
        dragPage = globalIndex / pageSize
        deleteZone.alpha = 1f
        val clip = ClipData.newPlainText("", "")
        val shadow = View.DragShadowBuilder(view)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            view.startDragAndDrop(clip, shadow, null, 0)
        } else {
            @Suppress("DEPRECATION")
            view.startDrag(clip, shadow, null, 0)
        }
    }

    private fun handleGridDrag(grid: GridView, event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                val pageIdx = grid.tag as Int
                if (pageIdx == dragPage) {
                    val pos = grid.pointToPosition(event.x.toInt(), event.y.toInt())
                    if (pos != AdapterView.INVALID_POSITION && pos < pages[pageIdx].size) {
                        dragTarget = pageIdx * pageSize + pos
                    }
                }
            }
            DragEvent.ACTION_DROP -> finishDrag()
            DragEvent.ACTION_DRAG_ENDED -> cleanupDrag()
        }
        return true
    }

    private fun handleDeleteZoneDrag(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_ENTERED -> setDeleteZoneActive(true)
            DragEvent.ACTION_DRAG_EXITED -> setDeleteZoneActive(false)
            DragEvent.ACTION_DROP -> deleteDraggedTile()
            DragEvent.ACTION_DRAG_ENDED -> cleanupDrag()
        }
        return true
    }

    private fun handleRootDrag(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DROP, DragEvent.ACTION_DRAG_ENDED -> cleanupDrag()
        }
        return true
    }

    private fun finishDrag() {
        if (dragIndex in tiles.indices && dragTarget in tiles.indices && dragIndex != dragTarget) {
            val moved = tiles.removeAt(dragIndex)
            tiles.add(dragTarget, moved)
            DesktopApps.replace(this, tiles)
        }
        cleanupDrag()
        renderGrid()
    }

    private fun deleteDraggedTile() {
        if (dragIndex in tiles.indices) {
            tiles.removeAt(dragIndex)
            DesktopApps.replace(this, tiles)
            toast(getString(R.string.toast_removed))
        }
        cleanupDrag()
        renderGrid()
    }

    private fun cleanupDrag() {
        dragIndex = -1
        dragTarget = -1
        dragPage = -1
        deleteZone.alpha = 0f
        setDeleteZoneActive(false)
    }

    private fun setDeleteZoneActive(active: Boolean) {
        if (deleteZoneActive == active) return
        deleteZoneActive = active
        deleteZone.setBackgroundResource(
            if (active) R.drawable.bg_delete_zone_active else R.drawable.bg_delete_zone
        )
        deleteZone.text = getString(if (active) R.string.delete_zone_active else R.string.delete_zone)
    }

    private fun refreshExitButton() {
        val btn = findViewById<Button>(R.id.btn_exit_lock)
        btn.visibility = if (DesktopSettings.showExitButton(this)) View.VISIBLE else View.GONE
        btn.text = if (LockState.lockEnabled(this)) getString(R.string.btn_exit_lock) else getString(R.string.btn_lock)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onBackPressed() {
        // 桌面作为主页，不响应返回键
    }

    companion object {
        private const val REQ_PICK_VIDEO = 100
        private const val REQ_PICK_COVER = 101
    }
}
