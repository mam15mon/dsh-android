package io.github.jackwener.dsh.core.capability

import android.app.Application
import android.app.KeyguardManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class LockStateTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
    private val power = shadowOf(context.getSystemService(PowerManager::class.java))
    private val lock = LockState(context)

    @Test fun resumesWhenKeyguardDisappearsWithoutAnotherBroadcast() = runBlocking {
        power.setIsInteractive(false)
        keyguard.setKeyguardLocked(true)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { lock.awaitUnlock(2_000) }

        power.setIsInteractive(true)
        delay(600)
        assertFalse(waiting.isCompleted)

        keyguard.setKeyguardLocked(false)
        assertTrue(waiting.await())
    }

    @Test fun screenOffStillBlocksWithoutKeyguard() = runBlocking {
        power.setIsInteractive(false)
        keyguard.setKeyguardLocked(false)
        assertFalse(lock.awaitUnlock(50))
    }

    @Test fun cancelledWaitDoesNotResumeAfterUnlock() = runBlocking {
        power.setIsInteractive(true)
        keyguard.setKeyguardLocked(true)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { lock.awaitUnlock(2_000) }
        waiting.cancelAndJoin()

        keyguard.setKeyguardLocked(false)
        assertTrue(waiting.isCancelled)
        assertTrue(lock.awaitUnlock(0))
    }
}
