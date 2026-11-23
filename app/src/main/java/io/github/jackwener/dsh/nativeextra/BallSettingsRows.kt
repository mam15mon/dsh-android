package io.github.jackwener.dsh.nativeextra

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jackwener.dsh.core.designsystem.DSwitch
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.Segmented
import io.github.jackwener.dsh.core.designsystem.SettingsRow
import io.github.jackwener.dsh.core.designsystem.Tag
import io.github.jackwener.dsh.core.designsystem.Tone

private val SkinLabels = listOf("默认", "桌宠")

/**
 * Floating-ball appearance & behavior rows inside 设置 → 通用 → 快捷入口.
 */
@Composable
fun BallSettingsRows() {
    val context = LocalContext.current
    val cfg by remember(context) { BallPrefs.flow(context) }.collectAsStateWithLifecycle()

    SettingsRow(
        title = "对话框出现时不隐藏",
        description = "开启后，打开 DSH 主界面或快捷对话框时悬浮球保持显示（截屏瞬间仍会自动隐藏）",
        trailing = {
            DSwitch(cfg.keepInApp, { BallPrefs.setKeepInApp(context, it) })
        },
    )

    SettingsRow(
        title = "悬浮球皮肤",
        description = "切换原版悬浮球或桌宠贴纸形象",
        stacked = true,
    ) {
        Segmented(
            options = SkinLabels,
            selected = if (cfg.skin == BallSkin.PET) 1 else 0,
            onSelect = { idx -> BallPrefs.setSkin(context, if (idx == 1) BallSkin.PET else BallSkin.DEFAULT) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    SettingsRow(
        title = "悬浮球大小",
        description = "拖动滑块调整桌宠或悬浮球尺寸，在推荐大小处有吸附停留",
        stacked = true,
    ) {
        BallSizeSlider(
            valueDp = cfg.sizeDp,
            onChange = { BallPrefs.setSizeDp(context, it) },
        )
    }
}

@Composable
private fun BallSizeSlider(
    valueDp: Int,
    onChange: (Int) -> Unit,
) {
    val c = Dsh.colors
    val haptic = LocalHapticFeedback.current
    val min = BallPrefs.MIN_SIZE_DP.toFloat()
    val max = BallPrefs.MAX_SIZE_DP.toFloat()
    val rec = BallPrefs.RECOMMENDED_SIZE_DP.toFloat()
    val recFraction = (rec - min) / (max - min)
    val valueFraction = ((valueDp - min) / (max - min)).coerceIn(0f, 1f)
    var widthPx by remember { mutableFloatStateOf(1f) }
    var lastDp by remember { mutableFloatStateOf(valueDp.toFloat()) }

    fun applyAt(xPx: Float) {
        val pad = 24f
        val span = (widthPx - pad * 2f).coerceAtLeast(1f)
        val frac = ((xPx - pad) / span).coerceIn(0f, 1f)
        val raw = min + frac * (max - min)
        val snapped = BallPrefs.snapSizeDp(raw)
        if (snapped != lastDp.toInt()) {
            if (snapped == BallPrefs.RECOMMENDED_SIZE_DP) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            lastDp = snapped.toFloat()
            onChange(snapped)
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("当前尺寸", style = DshType.xxs12, color = c.labelTertiary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${valueDp} dp", style = DshType.xs13m, color = c.labelPrimary)
                if (valueDp == BallPrefs.RECOMMENDED_SIZE_DP) {
                    Tag("推荐", Tone.Info)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    detectTapGestures { offset -> applyAt(offset.x) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        applyAt(change.position.x)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                val pad = 10.dp.toPx()
                val trackH = 4.dp.toPx()
                val trackY = (size.height - trackH) / 2f
                val usableW = (size.width - pad * 2f).coerceAtLeast(1f)
                val activeW = usableW * valueFraction
                val recX = pad + usableW * recFraction
                val thumbX = pad + activeW
                val centerY = size.height / 2f

                // Inactive track
                drawRoundRect(
                    color = c.borderL2,
                    topLeft = Offset(pad, trackY),
                    size = Size(usableW, trackH),
                    cornerRadius = CornerRadius(trackH / 2f, trackH / 2f),
                )
                // Active track
                drawRoundRect(
                    color = c.info,
                    topLeft = Offset(pad, trackY),
                    size = Size(activeW, trackH),
                    cornerRadius = CornerRadius(trackH / 2f, trackH / 2f),
                )
                // Recommended detent dot
                drawCircle(
                    color = if (valueFraction >= recFraction) Color.White else c.labelTertiary,
                    radius = 3.dp.toPx(),
                    center = Offset(recX, centerY),
                )
                // Thumb shadow + fill + hairline border
                drawCircle(
                    color = Color(0x26000000),
                    radius = 10.dp.toPx(),
                    center = Offset(thumbX, centerY + 1.dp.toPx()),
                )
                drawCircle(
                    color = if (c.isDark) c.bgLayer3 else Color.White,
                    radius = 9.5.dp.toPx(),
                    center = Offset(thumbX, centerY),
                )
                drawCircle(
                    color = if (valueDp == BallPrefs.RECOMMENDED_SIZE_DP) c.info else c.borderL3,
                    radius = 9.5.dp.toPx(),
                    center = Offset(thumbX, centerY),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${BallPrefs.MIN_SIZE_DP} dp", style = DshType.xxs12, color = c.labelTertiary)
            Text("推荐 ${BallPrefs.RECOMMENDED_SIZE_DP} dp", style = DshType.xxs12, color = if (valueDp == BallPrefs.RECOMMENDED_SIZE_DP) c.info else c.labelTertiary)
            Text("${BallPrefs.MAX_SIZE_DP} dp", style = DshType.xxs12, color = c.labelTertiary)
        }
    }
}
