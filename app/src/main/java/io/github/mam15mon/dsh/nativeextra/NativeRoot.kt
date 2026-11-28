package io.github.mam15mon.dsh.nativeextra

import android.content.Context
import java.io.File
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RootResult(val code: Int, val output: String)

object NativeRoot {
    fun enabled(c: Context) = c.getSharedPreferences("native_root", 0).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) { c.getSharedPreferences("native_root", 0).edit().putBoolean("enabled", on).commit() }
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    suspend fun exec(c: Context, command: String, timeoutMs: Long = 30_000): RootResult {
        check(enabled(c)) { "Root 模式未开启，请到手机权限 → Root 与目录直连开启" }
        return withTimeout(timeoutMs + 1000) { withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val process = ProcessBuilder("su", "--mount-master", "-c", "exec /system/bin/toybox timeout -s KILL ${((timeoutMs + 999) / 1000).coerceIn(1, 120)} /system/bin/sh -c ${quote(command)}")
                    .redirectErrorStream(true).start()
                continuation.invokeOnCancellation { process.destroyForcibly() }
                Thread({
                    try {
                        val output = StringBuilder()
                        process.inputStream.bufferedReader().use { reader ->
                            val buffer = CharArray(4096)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                if (output.length < 64_000) output.append(buffer, 0, count.coerceAtMost(64_000 - output.length))
                            }
                        }
                        val result = RootResult(process.waitFor(), output.toString())
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    } finally { process.destroy() }
                }, "native-root-command").start()
            }
        } }
    }

    suspend fun capture(c: Context): Bitmap = io.github.mam15mon.dsh.service.FloatingBallService.withoutOverlays {
        val file = withContext(Dispatchers.IO) { File.createTempFile("native-root-shot-", ".png", c.cacheDir) }
        try {
            val result = exec(c, "screencap -p ${quote(file.path)}")
            check(result.code == 0) { "Root 截图失败：${result.output}" }
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path) ?: error("Root 截图不是有效图片") }
        } finally { file.delete() }
    }

    fun selected(c: Context): String = c.getSharedPreferences("native_root", 0).getString("workspace", "") ?: ""
    fun saveDirectory(c: Context, path: String) {
        require(path.startsWith("/") && !path.contains('\n') && !path.contains('\u0000')) { "请输入绝对目录路径" }
        c.getSharedPreferences("native_root", 0).edit().putString("workspace", path.trimEnd('/').ifEmpty { "/" }).commit()
    }
    fun clearDirectory(c: Context) { c.getSharedPreferences("native_root", 0).edit().remove("workspace").commit() }
}
