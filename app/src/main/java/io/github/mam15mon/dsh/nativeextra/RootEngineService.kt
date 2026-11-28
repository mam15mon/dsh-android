package io.github.mam15mon.dsh.nativeextra

import android.content.Intent
import android.os.*
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.topjohnwu.superuser.ipc.RootService
import io.github.mam15mon.dsh.core.engine.IEngineService
import io.github.mam15mon.dsh.engine.NodeRuntime
import java.io.File
import java.io.FileDescriptor

class RootEngineService : RootService() {
    private var started = false
    private var appUid = -1
    private var managed: List<String> = emptyList()
    private var ownership: EngineStateOwnership? = null
    private var owner: IBinder? = null
    private fun releaseOwnership() {
        if (appUid < 10000) return
        ownership?.close()
        // Only app-owned engine state, never the selected project or symlink targets.
        managed.forEach { path ->
            val file = File(path)
            if (file.exists()) ProcessBuilder("/system/bin/find", path, "-xdev", "-exec", "chown", "-h", "$appUid:$appUid", "{}", "+").start().waitFor()
        }
    }
    override fun onDestroy() { releaseOwnership(); super.onDestroy(); Process.killProcess(Process.myPid()) }
    override fun onBind(intent: Intent): IBinder = object : IEngineService.Stub() {
        override fun isRunning() = started
        override fun stop() {
            try { releaseOwnership() } finally { Process.killProcess(Process.myPid()) }
        }
        override fun start(env: Bundle): Bundle = synchronized(this@RootEngineService) {
            check(!started && Os.getuid() == 0) { "Root engine requires uid 0 and a fresh process" }
            appUid = env.getInt("engine_app_uid")
            check(appUid >= 10000)
            owner = env.getBinder("engine_owner")!!
            owner!!.linkToDeath({
                try { releaseOwnership() } finally { Process.killProcess(Process.myPid()) }
            }, 0)
            managed = env.getStringArrayList("engine_managed")!!.toList()
            val privateTemp = File(env.getString("TMPDIR")!!, "root-engine").apply { mkdirs() }
            // The App and Root engines must not share uid-validated spill directories.
            check(ProcessBuilder("/system/bin/find", privateTemp.path, "-xdev", "-exec", "chown", "-h", "0:$appUid", "{}", "+").start().waitFor() == 0)
            Os.chmod(privateTemp.path, 448) // 0700
            env.putString("TMPDIR", privateTemp.path)
            ownership = EngineStateOwnership(appUid, privateTemp).also { watcher -> managed.forEach { path ->
                File(path).mkdirs(); watcher.watch(File(path))
            } }
            @Suppress("DEPRECATION") Os.setgid(appUid)
            Os.umask(7)
            env.keySet().filter { !it.startsWith("engine_") }.forEach { key -> env.getString(key)?.let { Os.setenv(key, it, true) } }
            System.setProperty("dsh.native.dir", env.getString("DSH_NATIVE_DIR")!!)
            val stdin = ParcelFileDescriptor.createPipe()
            val stdout = ParcelFileDescriptor.createPipe()
            val nodeSide = FileDescriptor(); val appSide = FileDescriptor()
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0, nodeSide, appSide)
            val bridgeFd = ParcelFileDescriptor.dup(nodeSide).detachFd().also { Os.close(nodeSide) }
            Os.setenv("DSH_BRIDGE_FD", bridgeFd.toString(), true)
            val cwd = env.getString("engine_cwd")!!
            check(File(cwd).isDirectory) { "Project directory is unavailable: $cwd" }
            android.util.Log.i("RootEngineService", "Node identity uid=${Os.getuid()} gid=${Os.getgid()} pid=${Process.myPid()} cwd=$cwd")
            Os.setenv("DSH_ENGINE_CWD", cwd, true)
            File(env.getString("TMPDIR")!!).mkdirs()
            File(env.getString("HOME")!!).mkdirs()
            val stderr = ParcelFileDescriptor.open(File(env.getString("engine_stderr")!!), ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
            val inRead = stdin[0].detachFd(); val outWrite = stdout[1].detachFd(); val errWrite = stderr.detachFd()
            val args = env.getStringArray("engine_argv")!!
            started = true
            Thread(null, {
                try { NodeRuntime.nativeStart(args, inRead, outWrite, errWrite) }
                finally { releaseOwnership(); Process.killProcess(Process.myPid()) }
            }, "root-node-main", 16L * 1024 * 1024).start()
            Bundle().apply {
                putParcelable("sdk_in", stdin[1]); putParcelable("sdk_out", stdout[0])
                putParcelable("bridge", ParcelFileDescriptor.dup(appSide).also { Os.close(appSide) })
            }
        }
    }
}
