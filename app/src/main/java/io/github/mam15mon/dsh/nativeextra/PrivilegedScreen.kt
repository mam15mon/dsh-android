package io.github.mam15mon.dsh.nativeextra

import android.graphics.BitmapFactory
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Base64
import android.os.ParcelFileDescriptor
import io.github.mam15mon.dsh.core.capability.*
import io.github.mam15mon.dsh.core.capability.tools.Images
import io.github.mam15mon.dsh.service.FloatingBallService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrivilegedScreen @Inject constructor(@ApplicationContext private val context: Context, private val shell: PhoneShell, private val ocr: ScreenOcr) {
    suspend fun capture(display: Int) = FloatingBallService.withoutOverlays {
        val bytes = withContext(Dispatchers.IO) {
            val svc = shell.service()
            if (display == 0) ParcelFileDescriptor.AutoCloseInputStream(svc.captureMain()).use { it.readBytes() }
            else svc.captureDisplay(display, 1280)
        } ?: error("No screenshot of display $display")
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid screenshot")
    }

    suspend fun invoke(name: String, args: JsonObject, call: CallContext, display: Int): ToolResult {
        if (name == "screen_capture") {
            val bitmap = capture(display)
            return try { val image = Images.encode(bitmap)
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                context.getSystemService(DisplayManager::class.java).getDisplay(display).getRealMetrics(metrics)
                ToolResult.image(Images.MIME, Base64.encodeToString(image.bytes, Base64.NO_WRAP),
                    "${shell.label} · image ${image.width}x${image.height}; screen ${metrics.widthPixels}x${metrics.heightPixels}. Multiply image coordinates by ${metrics.widthPixels.toFloat() / image.width} for screen pixels.")
            } finally { bitmap.recycle() }
        }
        val result = JSONObject(withContext(Dispatchers.IO) { shell.service().screen(name, args.toString(), call.sessionId, display) })
        if (!result.getBoolean("ok")) return ToolResult.error("device_screen_failed", result.getString("text"))
        var text = "${shell.label} · ${result.getString("text")}"
        if (name == "screen_read" && args.bool("ocr") == true) {
            val bitmap = capture(display)
            try { text += "\nRecognized text (use screen-pixel coordinates to tap):\n" + ocr.read(bitmap).joinToString("\n") { "${it.text} @${it.x},${it.y}" } }
            finally { bitmap.recycle() }
        }
        return ToolResult.text(text).withOutcome(
            if (name == "screen_read") ActionOutcome.ReadChanged else if (name == "screen_wait") null
            else if (result.optBoolean("unchanged")) ActionOutcome.NoEffect else ActionOutcome.Effective,
            result.optInt("fingerprint"))
    }
}
