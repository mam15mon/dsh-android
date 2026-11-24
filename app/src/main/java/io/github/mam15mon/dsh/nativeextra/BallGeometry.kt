package io.github.mam15mon.dsh.nativeextra

import android.content.res.Resources
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.roundToInt

/** Positions describe the ball's travel within usable bounds, independent of orientation and size. */
data class BallGeometry(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun x(value: Int, size: Int) = value.coerceIn(left, (right - size).coerceAtLeast(left))
    fun y(value: Int, size: Int) = value.coerceIn(top, (bottom - size).coerceAtLeast(top))
    fun fromX(ratio: Float, size: Int) = left + ((right - left - size).coerceAtLeast(0) * ratio.coerceIn(0f, 1f)).roundToInt()
    fun fromY(ratio: Float, size: Int) = top + ((bottom - top - size).coerceAtLeast(0) * ratio.coerceIn(0f, 1f)).roundToInt()
    fun ratioX(value: Int, size: Int) = ((x(value, size) - left).toFloat() / (right - left - size).coerceAtLeast(1)).coerceIn(0f, 1f)
    fun ratioY(value: Int, size: Int) = ((y(value, size) - top).toFloat() / (bottom - top - size).coerceAtLeast(1)).coerceIn(0f, 1f)

    companion object {
        fun screen(wm: WindowManager, resources: Resources): BallGeometry {
            val margin = (8 * resources.displayMetrics.density).roundToInt()
            if (Build.VERSION.SDK_INT >= 30) {
                val metrics = wm.currentWindowMetrics
                val inset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                return BallGeometry(inset.left + margin, inset.top + margin,
                    (metrics.bounds.width() - inset.right - margin).coerceAtLeast(inset.left + margin),
                    (metrics.bounds.height() - inset.bottom - margin).coerceAtLeast(inset.top + margin))
            }
            val display = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(display)
            val status = resources.getIdentifier("status_bar_height", "dimen", "android")
            val navigation = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            return BallGeometry(margin, margin + if (status == 0) 0 else resources.getDimensionPixelSize(status),
                display.widthPixels - margin, display.heightPixels - margin - if (navigation == 0) 0 else resources.getDimensionPixelSize(navigation))
        }
    }
}
