package io.github.jackwener.dsh.nativeextra

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jackwener.dsh.core.designsystem.BtnSize
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.DSwitch
import io.github.jackwener.dsh.core.designsystem.DotKind
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.HDivider
import io.github.jackwener.dsh.core.designsystem.SettingsCard
import io.github.jackwener.dsh.core.designsystem.SettingsRow
import io.github.jackwener.dsh.core.designsystem.StateDot
import io.github.jackwener.dsh.core.designsystem.Tag
import io.github.jackwener.dsh.core.designsystem.Tone
import io.github.jackwener.dsh.feature.settings.rememberShellState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PhoneShellEntryPoint { fun phoneShell(): PhoneShell }

@Composable
fun BackgroundScreenCard(checked: Boolean, onChange: (Boolean) -> Unit, degraded: Boolean) {
    val c = Dsh.colors
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val (_, shizuku) = rememberShellState()
    val shell = remember { EntryPointAccessors.fromApplication(context.applicationContext, PhoneShellEntryPoint::class.java).phoneShell() }
    var tick by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在检查手机操作授权…") }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { ready = false; tick++ } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(tick, shizuku) {
        ready = false
        if (Build.VERSION.SDK_INT < 30) { status = "需要 Android 11 或更新版本"; return@LaunchedEffect }
        if (!shell.available) { status = "请先开启 Root，或启动并授权 Shizuku；无障碍本身不能创建后台屏。"; return@LaunchedEffect }
        try {
            val result = withContext(Dispatchers.IO) { shell.exec("id -u", 5000) }
            check(result.code == 0 && result.output.trim() in listOf("0", "2000")) { "系统服务身份验证失败" }
            ready = true
            status = "${shell.label} 已就绪" + if (degraded) " · 本机仅支持受限后台屏，部分应用可能无法运行" else ""
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            status = "手机操作服务不可用：${e.message}"
        }
    }
    val paused = checked && !ready
    SettingsCard(Modifier.padding(top = 8.dp)) {
        SettingsRow(
            title = "后台屏操作",
            description = "让助手在独立的虚拟屏幕运行 App，尽量不打扰你当前的画面。关闭后使用手机当前屏幕。",
            divider = false,
            leading = { StateDot(if (ready) DotKind.Done else if (paused) DotKind.Warn else DotKind.Idle) },
            trailing = { DSwitch(checked && ready, onChange, enabled = ready) },
        )
        HDivider(Modifier.padding(horizontal = 16.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(
                    text = if (ready) "${shell.label} 就绪" else if (paused) "已暂停" else "未就绪",
                    tone = if (ready) Tone.Success else if (paused) Tone.Warn else Tone.Neutral,
                )
                Text(
                    text = if (paused) "$status · 恢复授权后才能使用。" else status,
                    modifier = Modifier.weight(1f),
                    style = DshType.xxs12,
                    color = c.labelTertiary,
                )
            }
            if (paused) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    DButton("关闭后台屏", { onChange(false) }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                }
            }
        }
    }
}
