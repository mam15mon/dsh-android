package io.github.mam15mon.dsh.nativeextra

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import io.github.mam15mon.dsh.R
import io.github.mam15mon.dsh.core.designsystem.Chevron
import io.github.mam15mon.dsh.core.designsystem.DIcon
import io.github.mam15mon.dsh.core.designsystem.DotKind
import io.github.mam15mon.dsh.core.designsystem.Dsh
import io.github.mam15mon.dsh.core.designsystem.DshType
import io.github.mam15mon.dsh.core.designsystem.ExpandBody
import io.github.mam15mon.dsh.core.designsystem.SettingsCard
import io.github.mam15mon.dsh.core.designsystem.SettingsRow
import io.github.mam15mon.dsh.core.designsystem.StateDot
import io.github.mam15mon.dsh.core.designsystem.Tag
import io.github.mam15mon.dsh.core.designsystem.Tone
import io.github.mam15mon.dsh.feature.settings.PermissionCatalog
import io.github.mam15mon.dsh.feature.settings.ShellAccessCard

@Composable
fun PhoneAccessCard() {
    val c = Dsh.colors
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var root by remember { mutableStateOf(NativeRoot.enabled(context)) }
    var a11y by remember { mutableStateOf(PermissionCatalog.byId("accessibility")?.check(context) == true) }
    var showShizuku by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                root = NativeRoot.enabled(context)
                a11y = PermissionCatalog.byId("accessibility")?.check(context) == true
                if (root) showShizuku = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsCard {
            SettingsRow(
                title = "Root 与项目",
                description = if (root) "已开启 · 管理项目和直连文件夹" else "手机已 Root？点这里授权，并添加工作项目",
                divider = false,
                onClick = { context.startActivity(Intent(context, NativeRootActivity::class.java)) },
                leading = { StateDot(if (root) DotKind.Done else DotKind.Idle) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tag(if (root) "已开启" else "未开启", if (root) Tone.Success else Tone.Neutral)
                        DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary)
                    }
                },
            )
            SettingsRow(
                title = "无障碍读屏与操作",
                description = "抓树就是读取屏幕上的文字和控件；无障碍还支持按控件点击、输入。可与 Root 一起使用。",
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                leading = { StateDot(if (a11y) DotKind.Done else DotKind.Idle) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tag(if (a11y) "已授权" else "未授权", if (a11y) Tone.Success else Tone.Neutral)
                        DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary)
                    }
                },
            )
            SettingsRow(
                title = "Shizuku（可选）",
                description = if (root) "当前使用 Root，无需安装 Shizuku" else "没有 Root 时可用；与 Root 共用同一套系统能力服务",
                onClick = { showShizuku = !showShizuku },
                leading = { DIcon(R.drawable.dsw_apps_add_outline, size = 18.dp, tint = c.labelSecondary) },
                trailing = { Chevron(open = showShizuku) },
            )
        }
        Text(
            text = if (root) "Root 与 Shizuku 共用抓树、操控、截图、系统命令、文件和副屏服务。抓树是读取文字和控件；操控是点击、滑动和输入。特殊滚轮控件仍需无障碍。"
            else "选择你需要的授权即可；不操作手机也可以直接聊天。",
            modifier = Modifier.padding(horizontal = 4.dp),
            style = DshType.xxs12,
            color = c.labelTertiary,
        )
        ExpandBody(showShizuku) {
            ShellAccessCard()
        }
    }
}
