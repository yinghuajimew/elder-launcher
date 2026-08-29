package com.elder.launcher.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import com.elder.launcher.DesktopActivity
import com.elder.launcher.MainActivity
import com.elder.launcher.desktop.DesktopApps
import com.elder.launcher.keepalive.LockState
import com.elder.launcher.setup.OnboardingState
import java.util.Calendar
import java.util.Locale

/**
 * 长辈桌面无障碍服务。
 *
 * 能力：
 *  1. 朗读通知 / 点读（系统 TTS）；
 *  2. 保活：锁定态下前台切到其它应用时，把本应用（主体应用）拉回前台；
 *  3. 按键拦截：锁定态下拦截返回/最近任务按键。
 *
 * 配合 MainActivity 声明为 HOME 桌面，实现"锁定主体应用"：
 * 不可划掉、按主页即回到本应用、重启后作为主屏幕自动恢复。
 */
class ElderAccessibilityService : AccessibilityService() {

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** 上一次"拉回前台"的时间，用于节流，避免高频重入造成循环。 */
    private var lastRelaunchMs = 0L

    private val chimeHandler = Handler(Looper.getMainLooper())
    private var lastChimeHour = -1
    private val chimeRunnable = object : Runnable {
        override fun run() {
            checkHourlyChime()
            chimeHandler.postDelayed(this, 30_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        initTts()
        enableKeyFiltering()
        chimeHandler.post(chimeRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                currentForegroundPackage = event.packageName?.toString()
                maybeKeepAlive(event.packageName?.toString())
            }

            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (AccessibilitySettings.readNotifications(this)) speakNotification(event)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (AccessibilitySettings.tapToRead(this)) speakNode(event.source)
            }
        }
    }

    /** 锁定态下拦截返回/最近任务按键，防止物理键逃出。 */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (LockState.lockEnabled(this)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_APP_SWITCH -> return true
            }
        }
        return super.onKeyEvent(event)
    }

    /**
     * 保活：锁定态下当前台切换到其它应用/桌面时，把主体应用拉回前台。
     * 跳过：
     *  - 系统关键窗口（通知栏/来电/拨号）
     *  - 已添加到桌面的应用（白名单，使用中不拉回）
     *  - 输入法键盘（避免搜索时键盘弹出被拉回）
     */
    private fun maybeKeepAlive(pkg: String?) {
        if (!LockState.lockEnabled(this)) return
        if (pkg.isNullOrEmpty()) return
        if (pkg == packageName) return
        if (pkg == "android" || SKIP_PACKAGES.any { pkg.startsWith(it) }) return
        if (DesktopApps.containsPkg(this, pkg)) return
        if (isInputMethod(pkg)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRelaunchMs < RELAUNCH_INTERVAL_MS) return
        lastRelaunchMs = now
        val target = if (OnboardingState.isDone(this)) DesktopActivity::class.java else MainActivity::class.java
        val intent = Intent(this, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // 前台启动可能被系统限制，忽略并等待下一次事件
        }
    }

    /** 判断是否为已启用的输入法（键盘）。 */
    private fun isInputMethod(pkg: String): Boolean {
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.any { it.packageName == pkg }
        } catch (_: Exception) {
            false
        }
    }

    private fun enableKeyFiltering() {
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        setServiceInfo(info)
    }

    /** 读出一条通知（标题 + 正文）。 */
    private fun speakNotification(event: AccessibilityEvent) {
        val extras = (event.parcelableData as? Notification)?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val content = listOf(title, text).filter { it.isNotEmpty() }
        if (content.isNotEmpty()) speak(content.joinToString("，"))
    }

    /** 读出一个控件的文本或无障碍描述。 */
    private fun speakNode(node: AccessibilityNodeInfo?) {
        val text = node?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: node?.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return
        speak(text)
    }

    private fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "elder_speak")
    }

    /** 整点报时：每分钟检查一次，到整点用 TTS 播报。 */
    private fun checkHourlyChime() {
        if (!AccessibilitySettings.hourlyChime(this)) return
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        if (minute == 0 && hour != lastChimeHour) {
            lastChimeHour = hour
            speak("现在${hour}点整")
        }
    }

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = configureLanguage()
            }
        }
    }

    /** 依次尝试中文各区域与系统默认语言，直到找到可用的引擎。 */
    private fun configureLanguage(): Boolean {
        val t = tts ?: return false
        val candidates = listOf(
            Locale.CHINA,
            Locale.SIMPLIFIED_CHINESE,
            Locale.CHINESE,
            Locale.getDefault()
        )
        for (locale in candidates) {
            when (t.setLanguage(locale)) {
                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> continue
                else -> return true
            }
        }
        return false
    }

    override fun onInterrupt() {
        tts?.stop()
    }

    override fun onDestroy() {
        isRunning = false
        currentForegroundPackage = null
        chimeHandler.removeCallbacks(chimeRunnable)
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    companion object {
        /** 服务是否正在运行。 */
        @Volatile
        var isRunning: Boolean = false

        /** 当前前台应用包名（守护模块可用）。 */
        @Volatile
        var currentForegroundPackage: String? = null

        /** 拉回前台的节流间隔。 */
        private const val RELAUNCH_INTERVAL_MS = 1500L

        /** 保活时跳过的系统关键窗口前缀。 */
        private val SKIP_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.incallui",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone"
        )
    }
}
