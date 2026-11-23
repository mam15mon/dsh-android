package io.github.jackwener.dsh.nativeextra

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.platform.IShellService
import io.github.jackwener.dsh.core.platform.ShizukuShell
import io.github.jackwener.dsh.core.platform.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One device-service contract; the user chooses which identity starts it. */
@Singleton
class PhoneShell @Inject constructor(@ApplicationContext private val context: Context, private val shizuku: ShizukuShell) {
    val access get() = shizuku.access
    val rootMode get() = NativeRoot.enabled(context)
    val available get() = rootMode || shizuku.available
    val label get() = if (rootMode) "Root" else "Shizuku"
    suspend fun service(): IShellService = if (rootMode) RootConnection.service(context) else shizuku.service()
    suspend fun exec(cmd: String, timeoutMs: Long = 30_000): ShellResult = withContext(Dispatchers.IO) {
        val raw = service().exec(cmd, timeoutMs)
        ShellResult(raw.substringBefore('\n').toIntOrNull() ?: -1, raw.substringAfter('\n'))
    }
}
