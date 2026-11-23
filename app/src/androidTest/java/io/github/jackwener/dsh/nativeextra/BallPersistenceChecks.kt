package io.github.jackwener.dsh.nativeextra

import android.content.Context
import android.os.Bundle

/** Two instrumentation runs, separated by process death: verifies the supplied repository's disk state. */
object BallPersistenceChecks {
    private val expected = BallConfig(BallSkin.PET, 73, true, .31f, .67f)
    fun run(context: Context, mode: String): Bundle {
        val backup = context.getSharedPreferences("ball_backend_persistence_test", 0)
        val live = context.getSharedPreferences("native_ball_prefs", 0)
        fun set(c: BallConfig) {
            BallPrefs.setSkin(context, c.skin); BallPrefs.setSizeDp(context, c.sizeDp)
            BallPrefs.setKeepInApp(context, c.keepInApp); BallPrefs.setPosition(context, c.posXRatio, c.posYRatio)
        }
        if (mode == "save") {
            check(!backup.getBoolean("pending", false)) { "Restore the previous persistence test first" }
            val c = BallPrefs.read(context)
            check(backup.edit().putBoolean("pending", true).putString("skin", c.skin.key).putInt("size", c.sizeDp)
                .putBoolean("keep", c.keepInApp).putFloat("x", c.posXRatio).putFloat("y", c.posYRatio).commit())
            set(expected)
            check(live.edit().putBoolean("test_disk_barrier", true).commit())
            return Bundle().apply { putString("persistence_save", "PASS: fixture committed; terminate and reopen before verify") }
        }
        require(mode == "verify" && backup.getBoolean("pending", false))
        try {
            check(BallPrefs.read(context) == expected)
            check(BallPrefs.flow(context).value == expected)
            return Bundle().apply { putString("persistence_reopen", "PASS: all five fields restored after process death") }
        } finally {
            set(BallConfig(BallSkin.of(backup.getString("skin", null)), backup.getInt("size", 52), backup.getBoolean("keep", false), backup.getFloat("x", .92f), backup.getFloat("y", .33f)))
            check(live.edit().remove("test_disk_barrier").commit())
            check(backup.edit().clear().commit())
        }
    }
}
