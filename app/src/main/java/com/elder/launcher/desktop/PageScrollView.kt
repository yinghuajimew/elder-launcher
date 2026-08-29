package com.elder.launcher.desktop

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.HorizontalScrollView

/**
 * 横向分页滚动容器：松手后吸附到最近的整页，禁用惯性，方便长辈一页页滑动。
 */
class PageScrollView(context: Context, attrs: AttributeSet? = null) : HorizontalScrollView(context, attrs) {

    /** 单页宽度（像素）。 */
    var pageWidth = 0
    var currentPage = 0
        private set
    var onPageChanged: ((Int) -> Unit)? = null

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            post { snapToNearest() }
        }
        return super.onTouchEvent(ev)
    }

    override fun fling(velocityX: Int) {
        // 禁用惯性，改为松手后吸附
    }

    private fun snapToNearest() {
        if (pageWidth <= 0) return
        val fraction = scrollX.toFloat() / pageWidth
        val target = when {
            fraction >= currentPage + 0.25f -> currentPage + 1
            fraction <= currentPage - 0.25f -> currentPage - 1
            else -> currentPage
        }.coerceAtLeast(0)
        smoothScrollTo(target * pageWidth, 0)
        if (target != currentPage) {
            currentPage = target
            onPageChanged?.invoke(target)
        }
    }
}
