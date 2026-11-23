package io.github.jackwener.dsh.nativeextra

import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.designsystem.BtnSize
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.DDialog
import io.github.jackwener.dsh.core.designsystem.DIcon
import io.github.jackwener.dsh.core.designsystem.DInput
import io.github.jackwener.dsh.core.designsystem.DTopBar
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.EmptyNote
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.Segmented
import io.github.jackwener.dsh.core.designsystem.SettingsCard
import io.github.jackwener.dsh.core.designsystem.SettingsRow
import io.github.jackwener.dsh.core.designsystem.Spinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator

private data class DirectoryApp(val info: ApplicationInfo, val name: String) {
    val system get() = info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}

private val AppFilterTabs = listOf("全部", "用户应用", "系统应用")

@Composable
fun AppDirectoryPicker(onDismiss: () -> Unit, onSelect: (String, String) -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<DirectoryApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var tabIndex by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<DirectoryApp?>(null) }
    val tab = AppFilterTabs[tabIndex]

    BackHandler {
        if (selected != null) selected = null else onDismiss()
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val collator = Collator.getInstance()
            pm.getInstalledApplications(0).map { DirectoryApp(it, it.loadLabel(pm).toString()) }
                .sortedWith { a, b -> collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.info.packageName.compareTo(b.info.packageName) }
        }
    }
    val filtered = remember(apps, query, tab) {
        val text = query.trim()
        apps.orEmpty().filter { app ->
            (tab == "全部" || if (tab == "系统应用") app.system else !app.system) &&
                (text.isEmpty() || app.name.contains(text, true) || app.info.packageName.contains(text, true))
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding().imePadding()) {
        DTopBar("选择应用", onDismiss) {
            DButton("取消", onDismiss, variant = BtnVariant.Ghost, size = BtnSize.Sm)
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DInput(query, { query = it }, placeholder = "搜索应用名或包名")
            Segmented(AppFilterTabs, tabIndex, { tabIndex = it }, Modifier.fillMaxWidth())
            Text(
                text = if (apps == null) "正在读取应用…" else "${filtered.size} 个应用",
                modifier = Modifier.padding(start = 4.dp),
                style = DshType.xxs12,
                color = c.labelTertiary,
            )
        }
        when {
            apps == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner(20.dp) }
            filtered.isEmpty() -> EmptyNote("没有匹配的应用")
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).navigationBarsPadding()) {
                itemsIndexed(filtered, key = { _, app -> app.info.packageName }) { index, app ->
                    val icon by produceState<ImageBitmap?>(null, app.info.packageName) {
                        value = withContext(Dispatchers.IO) {
                            runCatching { app.info.loadIcon(context.packageManager).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                        }
                    }
                    SettingsRow(
                        title = app.name,
                        description = app.info.packageName,
                        divider = index > 0,
                        onClick = { selected = app },
                        leading = {
                            if (icon != null) Image(icon!!, null, Modifier.size(30.dp).clip(Radius.sm))
                            else DIcon(R.drawable.dsw_apps_add_outline, size = 20.dp, tint = c.labelSecondary)
                        },
                        trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    selected?.let { app ->
        val user = android.os.Process.myUid() / 100000
        val extData = "/storage/emulated/$user/Android/data/${app.info.packageName}"
        val extObb = "/storage/emulated/$user/Android/obb/${app.info.packageName}"
        DDialog(
            onDismiss = { selected = null },
            title = app.name,
            buttons = {
                DButton("返回应用列表", { selected = null }, variant = BtnVariant.Outline, size = BtnSize.Sm)
            },
        ) {
            Text("选择要浏览的位置", style = DshType.xs13, color = c.labelTertiary)
            SettingsCard {
                SettingsRow(
                    title = "内部数据 · 配置、数据库、私有文件",
                    description = app.info.dataDir,
                    divider = false,
                    onClick = { onSelect(app.info.dataDir, app.name) },
                    leading = { DIcon(R.drawable.dsw_database_outline, size = 16.dp, tint = c.labelSecondary) },
                    trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary) },
                )
                SettingsRow(
                    title = "外部文件 · Android/data",
                    description = extData,
                    onClick = { onSelect(extData, app.name) },
                    leading = { DIcon(R.drawable.dsw_folder_open_outline, size = 16.dp, tint = c.labelSecondary) },
                    trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary) },
                )
                SettingsRow(
                    title = "资源文件 · Android/obb",
                    description = extObb,
                    onClick = { onSelect(extObb, app.name) },
                    leading = { DIcon(R.drawable.dsw_archive_outline, size = 16.dp, tint = c.labelSecondary) },
                    trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary) },
                )
            }
            Text("尚未创建的位置会提示不存在，不会自动创建。", style = DshType.xxs12, color = c.labelTertiary)
        }
    }
}
