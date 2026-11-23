package io.github.jackwener.dsh.nativeextra

import android.os.FileObserver
import android.system.Os
import android.system.OsConstants
import java.io.File

/** App-private engine state remains usable after Root authorization is removed. */
class EngineStateOwnership(private val uid: Int, private val privateTemp: File) {
    private val watches = HashMap<String, FileObserver>()
    @Synchronized fun watch(file: File) {
        // spill-local validates the running engine's uid; transfer this tree only after exit.
        if (file == privateTemp) return
        val stat = try { Os.lstat(file.path) } catch (_: android.system.ErrnoException) { return }
        if (OsConstants.S_ISLNK(stat.st_mode)) return
        if (stat.st_uid == 0) Os.chown(file.path, uid, uid)
        if (!OsConstants.S_ISDIR(stat.st_mode) || watches.containsKey(file.path)) return
        val observer = object : FileObserver(file, CREATE or MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null) try { watch(File(file, path)) }
                catch (e: Exception) { android.util.Log.e("RootEngineService", "Cannot return engine state ownership", e) }
            }
        }
        watches[file.path] = observer
        observer.startWatching()
        file.listFiles()?.forEach(::watch)
    }
    @Synchronized fun close() {
        watches.values.forEach { it.stopWatching() }
        watches.clear()
    }
}
