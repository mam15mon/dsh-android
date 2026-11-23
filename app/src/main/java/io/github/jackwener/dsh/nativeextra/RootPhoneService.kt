package io.github.jackwener.dsh.nativeextra

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService
import io.github.jackwener.dsh.core.platform.ShellUserService

class RootPhoneService : RootService() {
    private val service by lazy { ShellUserService() }
    override fun onBind(intent: Intent): IBinder = service
    override fun onDestroy() { service.close(); super.onDestroy() }
}
