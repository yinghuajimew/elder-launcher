package com.elder.launcher.desktop

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.elder.launcher.R
import com.elder.launcher.player.CoverStore

/**
 * 桌面磁贴网格适配器：展示已添加的应用 + 视频磁贴，末尾一个「添加」磁贴。
 * 图标 / 文字大小按网格密度缩放（网格越少越大的适配长辈视力）。
 */
class AppGridAdapter(
    private val context: Context,
    private val tiles: MutableList<DesktopTile>,
    private val showAddTile: Boolean = true,
    private val iconSizeDp: Float = 60f,
    private val labelSizeSp: Float = 14f
) : BaseAdapter() {

    private val TYPE_APP = 0
    private val TYPE_VIDEO = 1
    private val TYPE_PLAYLIST = 2
    private val TYPE_ADD = 3

    private val iconSizePx = (iconSizeDp * context.resources.displayMetrics.density).toInt()

    override fun getCount(): Int = tiles.size + if (showAddTile) 1 else 0

    override fun getItem(position: Int): Any? =
        if (position < tiles.size) tiles[position] else null

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getItemViewType(position: Int): Int = when {
        showAddTile && position == tiles.size -> TYPE_ADD
        tiles[position].type == TileType.VIDEO -> TYPE_VIDEO
        tiles[position].type == TileType.PLAYLIST -> TYPE_PLAYLIST
        else -> TYPE_APP
    }

    override fun getViewTypeCount(): Int = 4

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        if (showAddTile && position == tiles.size) return addTile(convertView, parent)
        val tile = tiles[position]
        return when (tile.type) {
            TileType.VIDEO -> videoTile(tile, convertView, parent)
            TileType.PLAYLIST -> playlistTile(tile, convertView, parent)
            else -> appTile(tile, convertView, parent)
        }
    }

    private fun bindLabel(icon: ImageView, label: TextView) {
        icon.layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSizeSp)
    }

    private fun appTile(tile: DesktopTile, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_desktop_app, parent, false)
        val icon = view.findViewById<ImageView>(R.id.img_icon)
        val label = view.findViewById<TextView>(R.id.tv_label)
        bindLabel(icon, label)

        val pkg = tile.payload
        try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            icon.setImageDrawable(ai.loadIcon(context.packageManager))
            label.text = ai.loadLabel(context.packageManager)
        } catch (_: Exception) {
            icon.setImageResource(R.drawable.ic_video)
            label.text = pkg
        }
        return view
    }

    private fun videoTile(tile: DesktopTile, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_desktop_app, parent, false)
        val icon = view.findViewById<ImageView>(R.id.img_icon)
        val label = view.findViewById<TextView>(R.id.tv_label)
        bindLabel(icon, label)
        setTileIcon(icon, R.drawable.ic_video, tile.cover)
        label.text = tile.label.ifEmpty { tile.payload }
        return view
    }

    private fun playlistTile(tile: DesktopTile, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_desktop_app, parent, false)
        val icon = view.findViewById<ImageView>(R.id.img_icon)
        val label = view.findViewById<TextView>(R.id.tv_label)
        bindLabel(icon, label)
        setTileIcon(icon, R.drawable.ic_playlist, tile.cover)
        label.text = tile.label
        return view
    }

    private val coverCache = LruCache<String, Bitmap>(16)

    private fun setTileIcon(icon: ImageView, defaultRes: Int, coverPath: String) {
        if (coverPath.isEmpty()) {
            icon.tag = null
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            icon.setImageResource(defaultRes)
            return
        }
        val cached = coverCache.get(coverPath)
        if (cached != null) {
            icon.scaleType = ImageView.ScaleType.CENTER_CROP
            icon.setImageBitmap(cached)
            return
        }
        icon.tag = coverPath
        icon.setImageResource(defaultRes)
        Thread {
            val bmp = CoverStore.load(coverPath)
            if (bmp != null) coverCache.put(coverPath, bmp)
            icon.post {
                if (icon.tag == coverPath) {
                    if (bmp != null) {
                        icon.scaleType = ImageView.ScaleType.CENTER_CROP
                        icon.setImageBitmap(bmp)
                    }
                }
            }
        }.start()
    }

    private fun addTile(convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_desktop_add, parent, false)
        val circle = view.findViewById<TextView>(R.id.tv_add_circle)
        val label = view.findViewById<TextView>(R.id.tv_add_label)
        circle.layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
        circle.setTextSize(TypedValue.COMPLEX_UNIT_SP, iconSizeDp * 0.55f)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSizeSp)
        return view
    }
}
