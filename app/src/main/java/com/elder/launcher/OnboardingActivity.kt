package com.elder.launcher

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.elder.launcher.base.BaseActivity
import com.elder.launcher.permission.PermissionDef
import com.elder.launcher.permission.PermissionHelper
import com.elder.launcher.setup.OnboardingState

/**
 * 首次引导：权限一步一步授权。
 * 从日常危险权限开始，再到悬浮窗 / 使用统计 / 电池 / 自启动 / 无障碍等需手动开启的特殊权限。
 */
class OnboardingActivity : BaseActivity() {

    private var step = 0
    private lateinit var tvStep: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvDesc: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnAction: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button

    private data class Step(
        val title: String,
        val desc: String,
        val status: () -> String,
        val action: () -> Unit,
        val actionLabel: String
    )

    private lateinit var steps: List<Step>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        tvStep = findViewById(R.id.tv_step)
        tvTitle = findViewById(R.id.tv_title)
        tvDesc = findViewById(R.id.tv_desc)
        tvStatus = findViewById(R.id.tv_status)
        btnAction = findViewById(R.id.btn_action)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)

        buildSteps()

        btnAction.setOnClickListener {
            steps[step].action()
            render()
        }
        btnPrev.setOnClickListener {
            if (step > 0) {
                step--
                render()
            }
        }
        btnNext.setOnClickListener {
            if (step < steps.size - 1) {
                step++
                render()
            } else {
                finishOnboarding()
            }
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        if (::steps.isInitialized) render()
    }

    private fun buildSteps() {
        steps = listOf(
            Step(
                getString(R.string.onboarding_runtime_title),
                getString(R.string.onboarding_runtime_desc),
                { runtimeStatus() },
                { requestAll() },
                getString(R.string.btn_all_runtime)
            ),
            Step(
                getString(R.string.onboarding_overlay_title),
                getString(R.string.onboarding_overlay_desc),
                { if (PermissionHelper.canDrawOverlays(this)) getString(R.string.status_on) else getString(R.string.status_off) },
                { PermissionHelper.openOverlaySettings(this) },
                getString(R.string.onboarding_go_open)
            ),
            Step(
                getString(R.string.onboarding_usage_title),
                getString(R.string.onboarding_usage_desc),
                { if (PermissionHelper.hasUsageStatsPermission(this)) getString(R.string.status_on) else getString(R.string.status_off) },
                { PermissionHelper.openUsageStatsSettings(this) },
                getString(R.string.onboarding_go_open)
            ),
            Step(
                getString(R.string.onboarding_battery_title),
                getString(R.string.onboarding_battery_desc),
                { if (PermissionHelper.isIgnoringBatteryOptimizations(this)) getString(R.string.status_on) else getString(R.string.status_off) },
                { PermissionHelper.openBatteryOptimizationSettings(this) },
                getString(R.string.onboarding_go_open)
            ),
            Step(
                getString(R.string.onboarding_autostart_title),
                getString(R.string.onboarding_autostart_desc),
                { getString(R.string.status_manual) },
                { PermissionHelper.openAutoStartSettings(this) },
                getString(R.string.onboarding_go_open)
            ),
            Step(
                getString(R.string.onboarding_accessibility_title),
                getString(R.string.onboarding_accessibility_desc),
                { if (PermissionHelper.isAccessibilityServiceEnabled(this)) getString(R.string.status_on) else getString(R.string.status_off) },
                {
                    if (!PermissionHelper.isAccessibilityServiceEnabled(this)) {
                        PermissionHelper.openAccessibilitySettings(this)
                    }
                },
                getString(R.string.onboarding_go_open)
            )
        )
    }

    private fun runtimeStatus(): String {
        val granted = PermissionHelper.hasAll(this, PermissionDef.ALL_RUNTIME)
        return if (granted) getString(R.string.status_on)
        else "${getString(R.string.status_granted)} ${runtimeGrantedCount()}/${PermissionDef.ALL_RUNTIME.size}"
    }

    private fun runtimeGrantedCount(): Int =
        PermissionDef.ALL_RUNTIME.count { PermissionHelper.hasAll(this, arrayOf(it)) }

    private fun requestAll() {
        requirePermissions(PermissionDef.ALL_RUNTIME) { _, _ -> render() }
    }

    private fun render() {
        val s = steps[step]
        tvStep.text = getString(R.string.onboarding_step, step + 1, steps.size)
        tvTitle.text = s.title
        tvDesc.text = s.desc
        tvStatus.text = s.status()
        btnAction.text = s.actionLabel
        btnPrev.visibility = if (step == 0) android.view.View.GONE else android.view.View.VISIBLE
        btnNext.text = if (step == steps.size - 1) getString(R.string.btn_start)
        else getString(R.string.onboarding_next)
    }

    private fun finishOnboarding() {
        OnboardingState.setDone(this, true)
        startActivity(Intent(this, DesktopActivity::class.java))
        finish()
    }
}
