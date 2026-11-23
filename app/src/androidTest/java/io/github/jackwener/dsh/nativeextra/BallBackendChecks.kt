package io.github.jackwener.dsh.nativeextra

import android.content.Context
import android.app.Instrumentation
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import io.github.jackwener.dsh.service.FloatingBallService
import io.github.jackwener.dsh.service.CapsuleStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.abs

object BallBackendChecks {
    suspend fun run(context: Context, instrument: Instrumentation): Bundle {
        val result = Bundle()
        fun active() = FloatingBallService::class.java.getDeclaredField("active").apply { isAccessible = true }.get(null) as? FloatingBallService
        fun ball() = FloatingBallService::class.java.getDeclaredField("view").apply { isAccessible = true }.get(active()) as View
        suspend fun until(test: () -> Boolean) = withTimeout(5000) { while (!withContext(Dispatchers.Main) { test() }) delay(30) }
        val original = BallPrefs.read(context)
        val wasActive = active() != null
        val wasInFront = active()?.let { FloatingBallService::class.java.getDeclaredField("inFront").apply { isAccessible = true }.getBoolean(it) }
        try {
            val expected = original.copy(skin = BallSkin.PET, sizeDp = 64, keepInApp = true, posXRatio = .45f, posYRatio = .42f)
            val received = CoroutineScope(currentCoroutineContext()).async { BallPrefs.flow(context).first { it == expected } }
            withContext(Dispatchers.Main) {
                BallPrefs.setSkin(context, expected.skin); BallPrefs.setSizeDp(context, expected.sizeDp)
                BallPrefs.setKeepInApp(context, expected.keepInApp); BallPrefs.setPosition(context, expected.posXRatio, expected.posYRatio)
            }
            withTimeout(3000) { received.await() }
            check(BallPrefs.read(context) == expected)
            result.putString("config_flow", "PASS: supplied BallPrefs emitted and persisted all fields")
            withContext(Dispatchers.Main) { FloatingBallService.setEnabled(context, true) }
            until { active() != null && ball().visibility == View.VISIBLE }
            val service = active()!!
            withContext(Dispatchers.Main) {
                val lp = ball().layoutParams as WindowManager.LayoutParams
                val area = BallGeometry.screen(service.getSystemService(WindowManager::class.java), service.resources)
                check(lp.width == (64 * service.resources.displayMetrics.density).toInt())
                check(abs(area.ratioX(lp.x, lp.width) - .45f) < .01f)
                check(abs(area.ratioY(lp.y, lp.height) - .42f) < .01f)
                check(lp.x > area.left && lp.x + lp.width < area.right) { "Ball snapped to an edge" }
            }
            result.putString("free_position", "PASS: non-edge normalized position within safe bounds")
            withContext(Dispatchers.Main) {
                val view = ball()
                val lp = view.layoutParams as WindowManager.LayoutParams
                val x = lp.x + lp.width / 2f; val y = lp.y + lp.height / 2f
                val time = android.os.SystemClock.uptimeMillis()
                for ((action, dt, dx) in listOf(Triple(android.view.MotionEvent.ACTION_DOWN, 0L, 0f), Triple(android.view.MotionEvent.ACTION_MOVE, 100L, 80f), Triple(android.view.MotionEvent.ACTION_UP, 160L, 80f))) {
                    val event = android.view.MotionEvent.obtain(time, time + dt, action, x + dx, y + dx, 0)
                    try { check(view.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                val moved = view.layoutParams as WindowManager.LayoutParams
                val area = BallGeometry.screen(service.getSystemService(WindowManager::class.java), service.resources)
                val config = BallPrefs.read(context)
                check(abs(config.posXRatio - area.ratioX(moved.x, moved.width)) < .01f)
                check(abs(config.posYRatio - area.ratioY(moved.y, moved.height)) < .01f)
                check(moved.x > area.left && moved.x + moved.width < area.right)
            }
            result.putString("drag_and_save", "PASS: real touch handler retains a free position and saves ratios")
            withContext(Dispatchers.Main) { BallPrefs.setSizeDp(context, 96) }
            until { (ball().layoutParams as WindowManager.LayoutParams).width == (96 * service.resources.displayMetrics.density).toInt() }
            check(active() === service)
            withContext(Dispatchers.Main) { BallPrefs.setSkin(context, BallSkin.DEFAULT) }
            until { PetBallView::class.java.getDeclaredField("skin").apply { isAccessible = true }.get(ball()) == BallSkin.DEFAULT }
            result.putString("live_appearance", "PASS: size and skin changed without service restart")
            withContext(Dispatchers.Main) {
                FloatingBallService::class.java.getDeclaredField("inFront").apply { isAccessible = true }.setBoolean(service, true)
                BallPrefs.setKeepInApp(context, false)
            }
            until { ball().visibility == View.GONE }
            withContext(Dispatchers.Main) { BallPrefs.setKeepInApp(context, true) }
            until { ball().visibility == View.VISIBLE }
            val capsuleField = FloatingBallService::class.java.getDeclaredField("capsule").apply { isAccessible = true }
            val setCapsule = FloatingBallService::class.java.getDeclaredMethod("showCapsule", String::class.java, CapsuleStatus::class.java, String::class.java).apply { isAccessible = true }
            for (ratio in listOf(.2f, .8f)) {
                withContext(Dispatchers.Main) { BallPrefs.setPosition(context, ratio, .5f) }
                until { abs(BallGeometry.screen(service.getSystemService(WindowManager::class.java), service.resources).ratioX((ball().layoutParams as WindowManager.LayoutParams).x, ball().width) - ratio) < .02f }
                withContext(Dispatchers.Main) {
                    setCapsule.invoke(service, "ball-test", CapsuleStatus.Working(null, false), null)
                    val c = capsuleField.get(service) as View
                    val lp = c.layoutParams as WindowManager.LayoutParams
                    val area = BallGeometry.screen(service.getSystemService(WindowManager::class.java), service.resources)
                    check(lp.x >= area.left && lp.x + c.measuredWidth <= area.right)
                    check(lp.y >= area.top && lp.y + lp.height <= area.bottom)
                    val b = ball().layoutParams as WindowManager.LayoutParams
                    check(if (ratio < .5f) lp.x > b.x else lp.x < b.x)
                }
            }
            result.putString("capsule_placement", "PASS: capsule switches sides and stays within safe bounds")
            FloatingBallService.withoutOverlays {
                withContext(Dispatchers.Main) { check(ball().visibility != View.VISIBLE) }
                withContext(Dispatchers.Main) { check((capsuleField.get(service) as View).visibility != View.VISIBLE) }
                if (NativeRoot.enabled(context)) {
                    val screenshot = NativeRoot.capture(context)
                    try { check(screenshot.width > 0 && screenshot.height > 0) } finally { screenshot.recycle() }
                }
                FloatingBallService.withoutOverlays { withContext(Dispatchers.Main) { check(ball().visibility != View.VISIBLE) } }
                withContext(Dispatchers.Main) { check(ball().visibility != View.VISIBLE) }
            }
            until { ball().visibility == View.VISIBLE }
            result.putString("visibility_and_capture", "PASS: in-app setting and nested screenshot hide/restore")
            if (NativeRoot.enabled(context)) {
                val field = io.github.jackwener.dsh.service.DshAccessibilityService::class.java.getDeclaredField("instance").apply { isAccessible = true }
                val previous = field.get(null)
                try {
                    withContext(Dispatchers.Main) { field.set(null, null) }
                    val capture = FloatingBallService::class.java.getDeclaredField("quickCapture").apply { isAccessible = true }.get(service) as BallScreenCapture
                    capture.prepare()
                    val shot = io.github.jackwener.dsh.feature.quick.ScreenContext.takeShot()
                    check(shot != null && shot.width > 0 && shot.height > 0) { "Root double-tap capture did not attach an image" }
                    shot.recycle()
                    result.putString("double_tap_root_without_accessibility", "PASS: actual double-tap capture backend attached screenshot with accessibility instance absent")
                } finally {
                    withContext(Dispatchers.Main) { field.set(null, previous) }
                    io.github.jackwener.dsh.feature.quick.ScreenContext.clear()
                }
            }
            withContext(Dispatchers.Main) {
                val show = FloatingBallService::class.java.getDeclaredMethod("show", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                val capsule = FloatingBallService::class.java.getDeclaredMethod("showCapsule", String::class.java, CapsuleStatus::class.java, String::class.java).apply { isAccessible = true }
                val state = PetBallView::class.java.getDeclaredField("visualState").apply { isAccessible = true }
                show.invoke(service, null, true); check(state.get(ball()) == BallVisualState.WORKING)
                show.invoke(service, "ball-test", false); check(state.get(ball()) == BallVisualState.WAITING)
                show.invoke(service, null, false)
                capsule.invoke(service, "ball-test", CapsuleStatus.Ended(CapsuleStatus.End.Done), null); check(state.get(ball()) == BallVisualState.DONE)
                capsule.invoke(service, "ball-test", CapsuleStatus.Ended(CapsuleStatus.End.Failed), null); check(state.get(ball()) == BallVisualState.ERROR)
                capsule.invoke(service, null, null, null); check(state.get(ball()) == BallVisualState.IDLE)
            }
            withContext(Dispatchers.Main) { BallPrefs.setSkin(context, BallSkin.PET) }
            until { PetBallView::class.java.getDeclaredField("skin").apply { isAccessible = true }.get(ball()) == BallSkin.PET }
            withContext(Dispatchers.Main) {
                BallVisualState.entries.forEach { pose ->
                    (ball() as PetBallView).setVisualState(pose)
                    val bitmap = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888)
                    try { ball().draw(android.graphics.Canvas(bitmap)) } finally { bitmap.recycle() }
                }
            }
            result.putString("status_mapping", "PASS: all five service statuses reach supplied PetBallView")
            if (NativeRoot.enabled(context)) {
                val auto = android.provider.Settings.System.getInt(context.contentResolver, "accelerometer_rotation", 1)
                val rotation = android.provider.Settings.System.getInt(context.contentResolver, "user_rotation", 0)
                val page = instrument.startActivitySync(android.content.Intent(context, NativeRootActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                try {
                    check(NativeRoot.exec(context, "wm user-rotation lock 1").code == 0)
                    until {
                        val area = BallGeometry.screen(service.getSystemService(WindowManager::class.java), service.resources)
                        area.right - area.left > area.bottom - area.top
                    }
                    withContext(Dispatchers.Main) {
                        val lp = ball().layoutParams as WindowManager.LayoutParams
                        val area = BallGeometry.screen(service.getSystemService(WindowManager::class.java), service.resources)
                        check(lp.x >= area.left && lp.y >= area.top && lp.x + lp.width <= area.right && lp.y + lp.height <= area.bottom)
                    }
                    result.putString("rotation", "PASS: landscape retains ball inside screen and status-bar safe bounds")
                } finally {
                    NativeRoot.exec(context, if (auto == 1) "wm user-rotation free" else "wm user-rotation lock $rotation")
                    instrument.runOnMainSync { page.finish() }
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                BallPrefs.setSkin(context, original.skin); BallPrefs.setSizeDp(context, original.sizeDp)
                BallPrefs.setKeepInApp(context, original.keepInApp); BallPrefs.setPosition(context, original.posXRatio, original.posYRatio)
                if (wasInFront != null) active()?.let { service ->
                    FloatingBallService::class.java.getDeclaredField("inFront").apply { isAccessible = true }.setBoolean(service, wasInFront)
                    FloatingBallService::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(service)
                }
                if (!wasActive) FloatingBallService.setEnabled(context, false)
            }
        }
        return result
    }
}
