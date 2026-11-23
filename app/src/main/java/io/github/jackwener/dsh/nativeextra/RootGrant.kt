package io.github.jackwener.dsh.nativeextra

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object RootGrant {
    suspend fun authorize(context: Context): Boolean {
        context.getSharedPreferences("native_root", 0).edit().putBoolean("disabled_by_user", false).commit()
        val granted = check(context)
        dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, EngineIdentityEntry::class.java)
            .engine().rootPreferenceChanged()
        return granted
    }
    fun setAllowed(context: Context, allowed: Boolean) {
        context.getSharedPreferences("native_root", 0).edit().putBoolean("disabled_by_user", !allowed).commit()
        if (!allowed) NativeRoot.setEnabled(context, false)
        dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, EngineIdentityEntry::class.java)
            .engine().rootPreferenceChanged()
    }
    /** New su authorization on each check; a cached Root Binder cannot prove a grant still exists. */
    suspend fun check(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (context.getSharedPreferences("native_root", 0).getBoolean("disabled_by_user", false)) {
            NativeRoot.setEnabled(context, false)
            return@withContext false
        }
        val process = try { ProcessBuilder("su", "--mount-master", "-c", "id -u").redirectErrorStream(true).start() }
            catch (_: java.io.IOException) { NativeRoot.setEnabled(context, false); return@withContext false }
        try {
            val granted = process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0 && process.inputStream.bufferedReader().readText().trim() == "0"
            NativeRoot.setEnabled(context, granted)
            granted
        } finally { process.destroyForcibly() }
    }
}
