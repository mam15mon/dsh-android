package io.github.mam15mon.dsh.nativeextra

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mam15mon.dsh.feature.quick.ScreenContext
import io.github.mam15mon.dsh.service.DshAccessibilityService
import io.github.mam15mon.dsh.service.FloatingBallService
import javax.inject.Inject
import javax.inject.Singleton

/** Double-tap capture follows the same explicit Root/Shizuku/accessibility selection as screen tools. */
@Singleton
class BallScreenCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: PhoneShell,
    private val privileged: PrivilegedScreen,
) {
    suspend fun prepare() = FloatingBallService.withoutOverlays {
        ScreenContext.clear()
        RootGrant.check(context)
        val accessibility = DshAccessibilityService.instance
        val image = if (shell.available) privileged.capture(0)
        else {
            check(accessibility != null) { "请开启 Root、授权 Shizuku，或开启无障碍截图" }
            val capture = accessibility.capture()
            capture.bitmap ?: error("当前页面无法截图（错误 ${capture.errorCode}）")
        }
        // Text is optional metadata; Root/Shizuku screenshots do not require accessibility.
        if (accessibility != null) ScreenContext.captureFromAccessibility()
        ScreenContext.attachShot(image)
    }
}
