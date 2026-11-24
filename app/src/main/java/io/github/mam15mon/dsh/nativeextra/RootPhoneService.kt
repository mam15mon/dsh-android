package io.github.mam15mon.dsh.nativeextra

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService
import io.github.mam15mon.dsh.core.platform.ShellUserService

class RootPhoneService : RootService() {
    private val service by lazy { ShellUserService() }
    override fun onBind(intent: Intent): IBinder = service
    override fun onDestroy() { service.close(); super.onDestroy() }
}
