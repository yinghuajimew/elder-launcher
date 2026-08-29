package com.elder.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.elder.launcher.accessibility.AccessibilitySettings
import com.elder.launcher.base.BaseActivity
import com.elder.launcher.desktop.ClockSettings
import com.elder.launcher.desktop.DesktopSettings
import com.elder.launcher.keepalive.LockState
import com.elder.launcher.permission.PermissionDef
import com.elder.launcher.permission.PermissionHelper
import com.elder.launcher.player.PlayerSettings
import com.elder.launcher.player.VideoLibraryActivity
import com.elder.launcher.setup.OnboardingState

/**
 * 引导页 + 设置页（二合一），采用「手机设置」式两级列表：
 * 父列表（权限管理 / 桌面设置 / 锁定 / 关于）→ 点进去是子列表。
 * 数据结构驱动，后续加设置项只需往 parents() 里加一个 ParentDef。
 */
class MainActivity : BaseActivity() {

    private lateinit var listView: ListView
    private lateinit var adapter: SettingsAdapter

    private var asSettings = false
    private var currentParent: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        asSettings = intent.getBooleanExtra(EXTRA_AS_SETTINGS, false)

        // 非设置模式：按引导状态路由到桌面或引导向导
        if (!asSettings) {
            if (OnboardingState.isDone(this)) {
                startActivity(Intent(this, DesktopActivity::class.java))
            } else {
                startActivity(Intent(this, OnboardingActivity::class.java))
            }
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.lv_settings)
        adapter = SettingsAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.onClick?.invoke()
        }

        findViewById<Button>(R.id.btn_back).setOnClickListener { onBackAction() }
        findViewById<Button>(R.id.btn_start).setOnClickListener { finishOnboarding() }
        findViewById<Button>(R.id.btn_exit_lock).setOnLongClickListener {
            toggleLock()
            true
        }

        showTop()
    }

    override fun onResume() {
        super.onResume()
        if (::listView.isInitialized) refresh()
    }

    override fun onBackPressed() {
        if (currentParent != null) {
            showTop()
        } else if (asSettings) {
            super.onBackPressed()
        }
        // 引导模式顶层：停留本页，不可直接退出
    }

    // ==================== 导航 ====================

    private fun showTop() {
        currentParent = null
        render()
    }

    private fun showParent(id: String) {
        currentParent = id
        render()
    }

    private fun onBackAction() {
        if (currentParent != null) {
            showTop()
        } else if (asSettings) {
            finish()
        }
    }

    private fun render() {
        val parent = parents().firstOrNull { it.id == currentParent }
        if (parent == null) {
            adapter.submit(parentItems())
            setTopBar(
                if (asSettings) getString(R.string.title_settings) else getString(R.string.title_onboarding),
                showBack = asSettings
            )
        } else {
            adapter.submit(parent.items())
            setTopBar(parent.title, showBack = true)
        }
        findViewById<Button>(R.id.btn_start).visibility =
            if (asSettings) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.btn_exit_lock).text =
            if (LockState.lockEnabled(this)) getString(R.string.btn_exit_lock) else getString(R.string.btn_lock)
    }

    private fun setTopBar(title: String, showBack: Boolean) {
        findViewById<TextView>(R.id.tv_title).text = title
        findViewById<Button>(R.id.btn_back).visibility = if (showBack) View.VISIBLE else View.GONE
    }

    private fun refresh() = render()

    private fun finishOnboarding() {
        OnboardingState.setDone(this, true)
        startActivity(Intent(this, DesktopActivity::class.java))
        finish()
    }

    // ==================== 数据 ====================

    private data class SettingsItem(
        val title: String,
        val subtitle: String = "",
        val trailing: String = "",
        val onClick: (() -> Unit)? = null
    )

    private data class ParentDef(
        val id: String,
        val title: String,
        val subtitle: () -> String,
        val items: () -> List<SettingsItem>
    )

    private class SettingsAdapter : BaseAdapter() {
        private val items = mutableListOf<SettingsItem>()

        fun submit(list: List<SettingsItem>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): SettingsItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_setting, parent, false)
            val item = items[position]
            view.findViewById<TextView>(R.id.tv_item_title).text = item.title
            val sub = view.findViewById<TextView>(R.id.tv_item_subtitle)
            sub.text = item.subtitle
            sub.visibility = if (item.subtitle.isEmpty()) View.GONE else View.VISIBLE
            view.findViewById<TextView>(R.id.tv_item_trailing).text = item.trailing
            return view
        }
    }

    private fun parents(): List<ParentDef> = listOf(
        ParentDef("permissions", getString(R.string.parent_permissions), { permissionSummary() }, { permissionItems() }),
        ParentDef("desktop", getString(R.string.parent_desktop), { getString(R.string.parent_desktop_sub) }, { desktopItems() }),
        ParentDef("player", getString(R.string.parent_player), { getString(R.string.parent_player_sub) }, { playerItems() }),
        ParentDef("lock", getString(R.string.title_lock), { lockStatus() }, { lockItems() }),
        ParentDef("about", getString(R.string.parent_about), { appVersion() }, { aboutItems() })
    )

    private fun parentItems(): List<SettingsItem> = parents().map {
        SettingsItem(it.title, it.subtitle(), "›") { showParent(it.id) }
    }

    private fun permissionSummary(): String {
        val granted = runtimeGroups().count { PermissionHelper.hasAll(this, it) }
        return getString(R.string.status_granted) + " $granted/5"
    }

    private fun runtimeGroups(): List<Array<String>> = listOf(
        PermissionDef.STORAGE, PermissionDef.LOCATION, PermissionDef.PHONE,
        PermissionDef.SMS, PermissionDef.CONTACTS
    )

    private fun lockStatus(): String =
        if (LockState.lockEnabled(this)) getString(R.string.status_on) else getString(R.string.status_off)

    private fun permissionItems(): List<SettingsItem> {
        val on = getString(R.string.status_on)
        val off = getString(R.string.status_off)
        return listOf(
            SettingsItem(getString(R.string.btn_all_runtime)) { requestAll() },
            permItem(getString(R.string.btn_storage), PermissionDef.STORAGE),
            permItem(getString(R.string.btn_location), PermissionDef.LOCATION),
            permItem(getString(R.string.btn_phone), PermissionDef.PHONE),
            permItem(getString(R.string.btn_sms), PermissionDef.SMS),
            permItem(getString(R.string.btn_contacts), PermissionDef.CONTACTS),
            specialItem(getString(R.string.item_overlay), PermissionHelper.canDrawOverlays(this)) {
                PermissionHelper.openOverlaySettings(this)
            },
            specialItem(getString(R.string.item_usage), PermissionHelper.hasUsageStatsPermission(this)) {
                PermissionHelper.openUsageStatsSettings(this)
            },
            specialItem(getString(R.string.item_battery), PermissionHelper.isIgnoringBatteryOptimizations(this)) {
                PermissionHelper.openBatteryOptimizationSettings(this)
            },
            SettingsItem(getString(R.string.item_autostart), getString(R.string.status_manual)) {
                PermissionHelper.openAutoStartSettings(this)
            },
            SettingsItem(getString(R.string.item_accessibility), if (PermissionHelper.isAccessibilityServiceEnabled(this)) on else off) {
                if (PermissionHelper.isAccessibilityServiceEnabled(this)) toast(getString(R.string.status_on))
                else PermissionHelper.openAccessibilitySettings(this)
            },
            SettingsItem(getString(R.string.item_read_notifications), if (AccessibilitySettings.readNotifications(this)) on else off) {
                AccessibilitySettings.setReadNotifications(this, !AccessibilitySettings.readNotifications(this))
                refresh()
            },
            SettingsItem(getString(R.string.item_tap_read), if (AccessibilitySettings.tapToRead(this)) on else off) {
                AccessibilitySettings.setTapToRead(this, !AccessibilitySettings.tapToRead(this))
                refresh()
            },
            SettingsItem(getString(R.string.item_hourly_chime), if (AccessibilitySettings.hourlyChime(this)) on else off) {
                AccessibilitySettings.setHourlyChime(this, !AccessibilitySettings.hourlyChime(this))
                refresh()
            }
        )
    }

    private fun permItem(label: String, perms: Array<String>): SettingsItem =
        SettingsItem(
            label,
            "",
            if (PermissionHelper.hasAll(this, perms)) getString(R.string.status_granted) else getString(R.string.status_not_granted)
        ) {
            requirePermissions(perms) { _, _ -> refresh() }
        }

    private fun specialItem(label: String, granted: Boolean, open: () -> Unit): SettingsItem =
        SettingsItem(
            label,
            "",
            if (granted) getString(R.string.status_on) else getString(R.string.status_off)
        ) {
            if (!granted) open()
        }

    private fun desktopItems(): List<SettingsItem> = listOf(
        SettingsItem(
            getString(R.string.setting_show_add_tile),
            getString(R.string.setting_show_add_tile_desc),
            if (DesktopSettings.showAddTile(this)) getString(R.string.status_on) else getString(R.string.status_off)
        ) {
            DesktopSettings.setShowAddTile(this, !DesktopSettings.showAddTile(this))
            refresh()
        },
        SettingsItem(getString(R.string.clock_mode), "", clockModeLabel()) { showClockModeDialog() },
        SettingsItem(getString(R.string.clock_shape), "", clockShapeLabel()) { showClockShapeDialog() },
        SettingsItem(getString(R.string.clock_order), "", clockOrderLabel()) { showClockOrderDialog() },
        SettingsItem(getString(R.string.grid_size), "", gridLabel()) { showGridDialog() },
        SettingsItem(
            getString(R.string.setting_add_app),
            getString(R.string.setting_add_app_desc)
        ) {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
    )

    private fun clockModeLabel(): String = when (ClockSettings.mode(this)) {
        ClockSettings.MODE_ANALOG -> getString(R.string.clock_mode_analog)
        ClockSettings.MODE_BOTH -> getString(R.string.clock_mode_both)
        else -> getString(R.string.clock_mode_digital)
    }

    private fun clockShapeLabel(): String =
        if (ClockSettings.shape(this) == ClockSettings.SHAPE_ROUNDED)
            getString(R.string.clock_shape_rounded) else getString(R.string.clock_shape_circle)

    private fun clockOrderLabel(): String =
        if (ClockSettings.digitalLeft(this)) getString(R.string.clock_order_digital_left)
        else getString(R.string.clock_order_digital_right)

    private fun showClockModeDialog() {
        val options = arrayOf(
            getString(R.string.clock_mode_digital),
            getString(R.string.clock_mode_analog),
            getString(R.string.clock_mode_both)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clock_mode))
            .setItems(options) { _, which ->
                ClockSettings.setMode(
                    this,
                    when (which) {
                        0 -> ClockSettings.MODE_DIGITAL
                        1 -> ClockSettings.MODE_ANALOG
                        else -> ClockSettings.MODE_BOTH
                    }
                )
                refresh()
            }
            .show()
    }

    private fun showClockShapeDialog() {
        val options = arrayOf(
            getString(R.string.clock_shape_circle),
            getString(R.string.clock_shape_rounded)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clock_shape))
            .setItems(options) { _, which ->
                ClockSettings.setShape(
                    this,
                    if (which == 0) ClockSettings.SHAPE_CIRCLE else ClockSettings.SHAPE_ROUNDED
                )
                refresh()
            }
            .show()
    }

    private fun showClockOrderDialog() {
        val options = arrayOf(
            getString(R.string.clock_order_digital_left),
            getString(R.string.clock_order_digital_right)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clock_order))
            .setItems(options) { _, which ->
                ClockSettings.setDigitalLeft(this, which == 0)
                refresh()
            }
            .show()
    }

    private fun gridLabel(): String =
        "${DesktopSettings.columns(this)}×${DesktopSettings.rows(this)}"

    private fun showGridDialog() {
        val presets = listOf(2 to 2, 2 to 3, 2 to 4, 3 to 3, 3 to 4, 3 to 5, 4 to 4, 4 to 5)
        val options = presets.map { "${it.first}×${it.second}" } + getString(R.string.grid_custom)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.grid_size))
            .setItems(options.toTypedArray()) { _, which ->
                if (which < presets.size) {
                    val (c, r) = presets[which]
                    DesktopSettings.setColumns(this, c)
                    DesktopSettings.setRows(this, r)
                    refresh()
                } else {
                    showCustomGridDialog()
                }
            }
            .show()
    }

    private fun showCustomGridDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_grid_custom, null)
        val inputCols = view.findViewById<EditText>(R.id.input_cols)
        val inputRows = view.findViewById<EditText>(R.id.input_rows)
        inputCols.setText(DesktopSettings.columns(this).toString())
        inputRows.setText(DesktopSettings.rows(this).toString())
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.grid_custom))
            .setView(view)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val c = (inputCols.text.toString().toIntOrNull() ?: 1).coerceIn(1, 8)
                val r = (inputRows.text.toString().toIntOrNull() ?: 1).coerceIn(1, 8)
                DesktopSettings.setColumns(this, c)
                DesktopSettings.setRows(this, r)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun playerItems(): List<SettingsItem> = listOf(
        SettingsItem(
            getString(R.string.player_resume),
            getString(R.string.player_resume_desc),
            if (PlayerSettings.resumeEnabled(this)) getString(R.string.status_on) else getString(R.string.status_off)
        ) {
            PlayerSettings.setResumeEnabled(this, !PlayerSettings.resumeEnabled(this))
            refresh()
        },
        SettingsItem(
            getString(R.string.player_orientation),
            "",
            orientationLabel()
        ) { showOrientationDialog() },
        SettingsItem(
            getString(R.string.open_video_library),
            getString(R.string.open_video_library_desc)
        ) {
            startActivity(Intent(this, VideoLibraryActivity::class.java))
        }
    )

    private fun orientationLabel(): String = when (PlayerSettings.orientation(this)) {
        PlayerSettings.ORIENT_LANDSCAPE -> getString(R.string.player_orientation_landscape)
        PlayerSettings.ORIENT_PORTRAIT -> getString(R.string.player_orientation_portrait)
        else -> getString(R.string.player_orientation_auto)
    }

    private fun showOrientationDialog() {
        val options = arrayOf(
            getString(R.string.player_orientation_auto),
            getString(R.string.player_orientation_landscape),
            getString(R.string.player_orientation_portrait)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.player_orientation))
            .setItems(options) { _, which ->
                val value = when (which) {
                    0 -> PlayerSettings.ORIENT_AUTO
                    1 -> PlayerSettings.ORIENT_LANDSCAPE
                    else -> PlayerSettings.ORIENT_PORTRAIT
                }
                PlayerSettings.setOrientation(this, value)
                refresh()
            }
            .show()
    }

    private fun lockItems(): List<SettingsItem> = listOf(
        SettingsItem(
            getString(R.string.lock_mode_title),
            getString(R.string.lock_mode_desc),
            lockStatus()
        ) { toggleLock() },
        SettingsItem(
            getString(R.string.setting_show_exit_button),
            getString(R.string.setting_show_exit_button_desc),
            if (DesktopSettings.showExitButton(this)) getString(R.string.status_on) else getString(R.string.status_off)
        ) {
            DesktopSettings.setShowExitButton(this, !DesktopSettings.showExitButton(this))
            refresh()
        }
    )

    private fun aboutItems(): List<SettingsItem> = listOf(
        SettingsItem(getString(R.string.about_app), "", getString(R.string.app_name)),
        SettingsItem(getString(R.string.about_version), "", appVersion()),
        SettingsItem(getString(R.string.about_github), getString(R.string.about_github_url)) {
            openUrl(getString(R.string.about_github_url))
        },
        SettingsItem(getString(R.string.about_license), "", "AGPL-3.0") { showLicense() }
    )

    // ==================== 动作 ====================

    private fun toggleLock() {
        val next = !LockState.lockEnabled(this)
        LockState.setLockEnabled(this, next)
        toast(if (next) getString(R.string.toast_lock_on) else getString(R.string.toast_lock_off))
        refresh()
    }

    private fun requestAll() {
        requirePermissions(PermissionDef.ALL_RUNTIME) { granted, _ ->
            toast(if (granted) getString(R.string.toast_all_granted) else getString(R.string.toast_all_failed))
            refresh()
        }
    }

    private fun appVersion(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("无法打开链接")
        }
    }

    private fun showLicense() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.license_title))
            .setMessage(getString(R.string.license_summary))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** 以设置页模式打开（跳过引导跳转）。 */
        const val EXTRA_AS_SETTINGS = "as_settings"
    }
}
