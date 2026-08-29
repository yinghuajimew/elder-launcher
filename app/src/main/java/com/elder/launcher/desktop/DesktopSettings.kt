package com.elder.launcher.desktop

import android.content.Context

/**
 * 桌面显示相关设置，持久化在 SharedPreferences。
 */
object DesktopSettings {

    private const val PREFS = "elder_desktop_settings"
    private const val KEY_SHOW_ADD_TILE = "show_add_tile"
    private const val KEY_COLUMNS = "columns"
    private const val KEY_ROWS = "rows"
    private const val KEY_SHOW_EXIT_BUTTON = "show_exit_button"

    /** 是否在桌面显示「+」添加磁贴（默认显示）。 */
    fun showAddTile(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_ADD_TILE, true)

    fun setShowAddTile(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_ADD_TILE, value).apply()
    }

    /** 桌面网格列数（默认 3）。 */
    fun columns(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_COLUMNS, 3)

    fun setColumns(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COLUMNS, value).apply()
    }

    /** 桌面网格行数（默认 3）。 */
    fun rows(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ROWS, 3)

    fun setRows(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ROWS, value).apply()
    }

    /** 是否在桌面显示「退出」按钮（默认显示，可关掉防止误触退出）。 */
    fun showExitButton(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_EXIT_BUTTON, true)

    fun setShowExitButton(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_EXIT_BUTTON, value).apply()
    }
}
