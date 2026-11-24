package io.github.mam15mon.dsh.nativeextra

import android.content.Context
import android.os.SystemClock
import java.io.File
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.TimeUnit
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
    fun target(c: Context) = File(c.filesDir.canonicalFile, "phone-workspace")
    private var helper: Process? = null
    private var mountedSource: String? = null

    /** Owned by the engine process; closing its pipe also releases the FUSE mount. */
    @Synchronized fun restore(c: Context) {
        val source = selected(c)
        if (source.isEmpty()) return
        check(enabled(c)) { "所选直连目录需要 Root；请开启 Root 或解除目录直连" }
        if (helper?.isAlive == true && mountedSource == source) return
        disconnect()
        val dir = target(c).apply { mkdirs() }
        check(dir.canonicalFile == dir.absoluteFile) { "挂载点不能是链接" }
        check(dir.list()?.isEmpty() == true) { "挂载点不是空目录" }
        val pid = android.os.Process.myPid()
        val uid = android.os.Process.myUid()
        // Android/data's emulated view is replaced by the underlying path for Root/FUSE access.
        val host = if (source.startsWith("/sdcard/Android/data/")) "/data/media/${uid / 100000}/Android/data/" + source.removePrefix("/sdcard/Android/data/") else source
        val executable = File(c.applicationInfo.nativeLibraryDir, "libdshworkspace.so")
        val process = ProcessBuilder("su", "--mount-master", "-c", "exec ${quote(executable.path)} $pid $uid ${quote(host)} ${quote(dir.path)}")
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader()
        val deadline = SystemClock.elapsedRealtime() + 10_000
        val messages = StringBuilder()
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                if (output.ready() || !process.isAlive) {
                    val line = output.readLine() ?: break
                    if (line == "READY") { helper = process; mountedSource = source; return }
                    messages.appendLine(line)
                } else Thread.sleep(25)
            }
            error("目录直连失败：${messages.toString().take(1000)}")
        } finally {
            if (helper !== process) { process.outputStream.close(); process.destroy() }
        }
    }

    @Synchronized fun disconnect() {
        helper?.let { process ->
            process.outputStream.close()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        helper = null
        mountedSource = null
    }
}
