package io.github.jackwener.dsh.nativeextra

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.Shell
import io.github.jackwener.dsh.core.platform.IShellService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object RootConnection {
    init { Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER)) }
    private val mutex = Mutex()
    @Volatile private var binder: IShellService? = null
    private var connection: ServiceConnection? = null

    suspend fun service(context: Context): IShellService = mutex.withLock {
        check(NativeRoot.enabled(context)) { "Root 操作未开启" }
        binder?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        val ready = CompletableDeferred<IShellService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                if (b != null && b.pingBinder()) ready.complete(IShellService.Stub.asInterface(b))
                else ready.completeExceptionally(IllegalStateException("Root 服务未返回有效 Binder"))
            }
            override fun onServiceDisconnected(name: ComponentName?) { binder = null }
            override fun onBindingDied(name: ComponentName?) { binder = null }
        }
        try {
            withContext(Dispatchers.Main) {
                connection?.let(RootService::unbind)
                connection = conn
                RootService.bind(Intent(context, RootPhoneService::class.java), conn)
            }
            withTimeout(30_000) { ready.await() }.also { binder = it }
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.Main) { RootService.unbind(conn); connection = null }
            throw e
        }
    }

    suspend fun disconnect() = mutex.withLock {
        withContext(Dispatchers.Main) { connection?.let(RootService::unbind); connection = null; binder = null }
    }
}
