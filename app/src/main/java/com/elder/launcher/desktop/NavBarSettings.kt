package com.elder.launcher.desktop

import android.content.Context

/**
 * 内置导航栏设置：桌面底部显示返回键 / 主页键，替代系统三大金刚。
 */
object NavBarSettings {

    private const val PREFS = "elder_navbar_settings"
    private const val KEY_SHOW_BACK = "show_back"
    private const val KEY_SHOW_HOME = "show_home"

    fun showBackButton(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_BACK, false)

    fun setShowBackButton(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_BACK, value).apply()
    }

    fun showHomeButton(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_HOME, false)

    fun setShowHomeButton(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_HOME, value).apply()
    }

    /** 只要有一个按钮就显示导航栏。 */
    fun anyEnabled(context: Context): Boolean =
        showBackButton(context) || showHomeButton(context)
}
