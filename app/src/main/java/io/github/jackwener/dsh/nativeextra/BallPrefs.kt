package io.github.jackwener.dsh.nativeextra

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

enum class BallSkin(val key: String) {
    DEFAULT("default"),
    PET("pet");

    companion object {
        fun of(key: String?): BallSkin = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

data class BallConfig(
    val skin: BallSkin = BallSkin.DEFAULT,
    val sizeDp: Int = BallPrefs.RECOMMENDED_SIZE_DP,
    val keepInApp: Boolean = false,
    val posXRatio: Float = 0.92f,
    val posYRatio: Float = 0.33f,
)

/**
 * Shared floating-ball configuration contract between Settings UI (Plan 2)
 * and FloatingBallService (Plan 1).
 */
object BallPrefs {
    private const val PREFS = "native_ball_prefs"
    private const val KEY_SKIN = "skin"
    private const val KEY_SIZE_DP = "size_dp"
    private const val KEY_KEEP_IN_APP = "keep_in_app"
    private const val KEY_POS_X = "pos_x_ratio"
    private const val KEY_POS_Y = "pos_y_ratio"

    const val MIN_SIZE_DP = 32
    const val MAX_SIZE_DP = 96
    const val RECOMMENDED_SIZE_DP = 52
    const val SNAP_RADIUS_DP = 3

    @Volatile
    private var state: MutableStateFlow<BallConfig>? = null

    fun snapSizeDp(rawDp: Float): Int {
        val clamped = rawDp.roundToInt().coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)
        return if (abs(clamped - RECOMMENDED_SIZE_DP) <= SNAP_RADIUS_DP) RECOMMENDED_SIZE_DP else clamped
    }

    fun read(context: Context): BallConfig {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BallConfig(
            skin = BallSkin.of(p.getString(KEY_SKIN, BallSkin.DEFAULT.key)),
            sizeDp = p.getInt(KEY_SIZE_DP, RECOMMENDED_SIZE_DP).coerceIn(MIN_SIZE_DP, MAX_SIZE_DP),
            keepInApp = p.getBoolean(KEY_KEEP_IN_APP, false),
            posXRatio = p.getFloat(KEY_POS_X, 0.92f).coerceIn(0f, 1f),
            posYRatio = p.getFloat(KEY_POS_Y, 0.33f).coerceIn(0f, 1f),
        )
    }

    fun flow(context: Context): StateFlow<BallConfig> = ensure(context).asStateFlow()

    fun setSkin(context: Context, skin: BallSkin) {
        update(context) { it.copy(skin = skin) }
    }

    fun setSizeDp(context: Context, sizeDp: Int) {
        val clamped = sizeDp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)
        update(context) { it.copy(sizeDp = clamped) }
    }

    fun setKeepInApp(context: Context, keep: Boolean) {
        update(context) { it.copy(keepInApp = keep) }
    }

    fun setPosition(context: Context, xRatio: Float, yRatio: Float) {
        val x = xRatio.coerceIn(0f, 1f)
        val y = yRatio.coerceIn(0f, 1f)
        update(context) { it.copy(posXRatio = x, posYRatio = y) }
    }

    private fun ensure(context: Context): MutableStateFlow<BallConfig> {
        state?.let { return it }
        return synchronized(this) {
            state ?: MutableStateFlow(read(context)).also { state = it }
        }
    }

    private fun update(context: Context, transform: (BallConfig) -> BallConfig) {
        val flow = ensure(context)
        val next = transform(flow.value)
        if (next == flow.value) return
        flow.value = next
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SKIN, next.skin.key)
            .putInt(KEY_SIZE_DP, next.sizeDp)
            .putBoolean(KEY_KEEP_IN_APP, next.keepInApp)
            .putFloat(KEY_POS_X, next.posXRatio)
            .putFloat(KEY_POS_Y, next.posYRatio)
            .apply()
    }
}
