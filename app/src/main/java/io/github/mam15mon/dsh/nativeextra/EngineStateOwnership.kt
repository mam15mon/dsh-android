package io.github.mam15mon.dsh.nativeextra

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
        val path = file.path
        lateinit var observer: FileObserver
        observer = object : FileObserver(file, CREATE or MOVED_TO or CLOSE_WRITE or DELETE_SELF or MOVE_SELF) {
            override fun onEvent(event: Int, child: String?) {
                if (event and (DELETE_SELF or MOVE_SELF) != 0) {
                    unwatch(path, observer)
                    return
                }
                if (child != null) try { watch(File(file, child)) }
                catch (e: Exception) { android.util.Log.e("RootEngineService", "Cannot return engine state ownership", e) }
            }
        }
        watches[path] = observer
        observer.startWatching()
        file.listFiles()?.forEach(::watch)
    }

    @Synchronized private fun unwatch(path: String, observer: FileObserver) {
        if (watches[path] === observer) watches.remove(path)
        observer.stopWatching()
    }
    @Synchronized fun close() {
        watches.values.forEach { it.stopWatching() }
        watches.clear()
    }
}
