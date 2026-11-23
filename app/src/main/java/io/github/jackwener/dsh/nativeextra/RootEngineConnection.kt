package io.github.jackwener.dsh.nativeextra

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService
import io.github.jackwener.dsh.core.engine.IEngineService
import kotlinx.coroutines.*

/** Separate daemon process: stopping Node must not kill the phone capability service. */
class RootEngineConnection(private val context: Context, private val onDeath: () -> Unit) {
    private val intent = Intent(context, RootEngineService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE)
    private var connection: ServiceConnection? = null
    private var generation = 0
    private var death: CompletableDeferred<Unit>? = null
    private var remote: IEngineService? = null
    suspend fun connect(): IEngineService {
        val service = bindOnce()
        if (!service.isRunning) return service
        // A killed Android wrapper can leave its daemon alive. Node must start in a fresh process.
        generation++
        withContext(Dispatchers.Main) { RootService.stop(intent) }
        withTimeout(10000) { death!!.await() }
        withContext(Dispatchers.Main) { connection?.let(RootService::unbind); connection = null }
        return bindOnce()
    }
    private suspend fun bindOnce(): IEngineService {
        val current = ++generation
        val died = CompletableDeferred<Unit>().also { death = it }
        val ready = CompletableDeferred<IEngineService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                binder.linkToDeath({ died.complete(Unit); if (current == generation) onDeath() }, 0)
                ready.complete(IEngineService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) { died.complete(Unit); if (current == generation) onDeath() }
        }
        withContext(Dispatchers.Main) { connection = conn; RootService.bind(intent, conn) }
        try { return withTimeout(30000) { ready.await() }.also { remote = it } }
        catch (e: Exception) { close(); throw e }
    }
    fun stop() {
        try { remote?.stop() } catch (_: android.os.DeadObjectException) { }
    }
    fun close() {
        generation++
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            RootService.stop(intent)
            connection?.let(RootService::unbind); connection = null; remote = null
        }
    }
}
