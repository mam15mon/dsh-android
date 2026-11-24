package io.github.mam15mon.dsh.nativeextra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import io.github.mam15mon.dsh.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class BallVisualState {
    IDLE,
    WORKING,
    WAITING,
    DONE,
    ERROR,
}

/**
 * Custom Canvas-rendered floating ball / desktop pet view.
 *
 * - [BallSkin.DEFAULT]: draws upstream ic_ball / ic_ball_working / ic_ball_attention drawables.
 * - [BallSkin.PET]: draws transparent WebP pet stickers (`dsh_pet_*`) with contain scaling and
 *   subtle pose animations ported from main's BallView.
 */
class PetBallView(context: Context) : View(context) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()
    private val bitmaps = HashMap<String, Bitmap?>()

    private var skin: BallSkin = BallSkin.DEFAULT
    private var visualState: BallVisualState = BallVisualState.IDLE
    private var poseStartedAt: Long = SystemClock.uptimeMillis()

    fun setSkin(newSkin: BallSkin) {
        if (skin == newSkin) return
        skin = newSkin
        poseStartedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun setVisualState(newState: BallVisualState) {
        if (visualState == newState) return
        visualState = newState
        poseStartedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        if (skin == BallSkin.PET) {
            val pose = petPoseName(visualState)
            val bmp = loadPetBitmap(pose)
            if (bmp != null && !bmp.isRecycled) {
                val scale = min(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
                val dw = bmp.width * scale
                val dh = bmp.height * scale
                box.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
                drawPetSticker(canvas, bmp, pose, w, h)
                return
            }
        }

        val drawableRes = when (visualState) {
            BallVisualState.WAITING -> R.drawable.ic_ball_attention
            BallVisualState.WORKING -> R.drawable.ic_ball_working
            else -> R.drawable.ic_ball
        }
        ContextCompat.getDrawable(context, drawableRes)?.let { d ->
            d.setBounds(0, 0, w, h)
            d.draw(canvas)
        }
    }

    private fun drawPetSticker(canvas: Canvas, bmp: Bitmap, pose: String, w: Int, h: Int) {
        val age = SystemClock.uptimeMillis() - poseStartedAt
        var lift = 0f
        var tilt = 0f
        if (pose == "dsh_pet_done") {
            if (age < 650L) {
                val t = age / 650f
                val ease = 1f - (1f - t) * (1f - t)
                lift = -(sin(PI * ease).toFloat()) * h * 0.055f
                postInvalidateOnAnimation()
            }
        } else {
            val period = when (pose) {
                "dsh_pet_work" -> 420L
                "dsh_pet_wait" -> 1300L
                else -> 2400L
            }
            val phase = 2.0 * PI * (age % period) / period
            lift = (cos(phase) - 1.0).toFloat() * h * 0.012f
            tilt = sin(phase).toFloat() * if (pose == "dsh_pet_wait") 2f else 0.8f
            if (pose == "dsh_pet_work") postInvalidateOnAnimation()
            else postInvalidateDelayed(33L)
        }
        canvas.save()
        canvas.translate(0f, lift)
        canvas.rotate(tilt, w / 2f, h * 0.9f)
        canvas.drawBitmap(bmp, null, box, bitmapPaint)
        canvas.restore()
    }

    private fun petPoseName(state: BallVisualState): String = when (state) {
        BallVisualState.WORKING -> "dsh_pet_work"
        BallVisualState.WAITING -> "dsh_pet_wait"
        BallVisualState.DONE -> "dsh_pet_done"
        BallVisualState.ERROR -> "dsh_pet_error"
        BallVisualState.IDLE -> "dsh_pet_idle"
    }

    private fun loadPetBitmap(name: String): Bitmap? = bitmaps.getOrPut(name) {
        val resId = resources.getIdentifier(name, "drawable", context.packageName)
        if (resId == 0) return@getOrPut null
        runCatching {
            resources.openRawResource(resId).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
}
