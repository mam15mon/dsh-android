package io.github.jackwener.dsh.core.capability

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LockState @Inject constructor(@ApplicationContext context: Context) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    /** The screen cannot be operated while it is off or the keyguard is showing. */
    val isLocked: Boolean get() = keyguard.isKeyguardLocked || !power.isInteractive

    /** Suspends until the user unlocks, or returns false after [timeoutMs]. Cancellable. */
    suspend fun awaitUnlock(timeoutMs: Long): Boolean {
        if (!isLocked) return true
        return withTimeoutOrNull(timeoutMs) {
            // A wake or unlock broadcast can arrive before the keyguard state changes.
            while (isLocked) delay(500)
            true
        } ?: false
    }
}
