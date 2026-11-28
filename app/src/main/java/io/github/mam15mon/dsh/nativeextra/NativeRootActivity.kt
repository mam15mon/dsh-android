package io.github.mam15mon.dsh.nativeextra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mam15mon.dsh.R
import io.github.mam15mon.dsh.core.designsystem.BtnSize
import io.github.mam15mon.dsh.core.designsystem.BtnVariant
import io.github.mam15mon.dsh.core.designsystem.DButton
import io.github.mam15mon.dsh.core.designsystem.DDialog
import io.github.mam15mon.dsh.core.designsystem.DIcon
import io.github.mam15mon.dsh.core.designsystem.DInput
import io.github.mam15mon.dsh.core.designsystem.DTopBar
import io.github.mam15mon.dsh.core.designsystem.DotKind
import io.github.mam15mon.dsh.core.designsystem.Dsh
import io.github.mam15mon.dsh.core.designsystem.DshTheme
import io.github.mam15mon.dsh.core.designsystem.DshType
import io.github.mam15mon.dsh.core.designsystem.EmptyNote
import io.github.mam15mon.dsh.core.designsystem.FilterPill
import io.github.mam15mon.dsh.core.designsystem.HDivider
import io.github.mam15mon.dsh.core.designsystem.IconBtn
import io.github.mam15mon.dsh.core.designsystem.Radius
import io.github.mam15mon.dsh.core.designsystem.SettingsCard
import io.github.mam15mon.dsh.core.designsystem.SettingsGroupTitle
import io.github.mam15mon.dsh.core.designsystem.SettingsRow
import io.github.mam15mon.dsh.core.designsystem.Spinner
import io.github.mam15mon.dsh.core.designsystem.StateDot
import io.github.mam15mon.dsh.core.designsystem.Tag
import io.github.mam15mon.dsh.core.designsystem.Tone
import io.github.mam15mon.dsh.core.platform.ShellFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class NativeRootActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DshTheme { ProjectsPage() } }
    }

    @Composable
    private fun ProjectsPage() {
        val c = Dsh.colors
        val scope = rememberCoroutineScope()
        var projects by remember { mutableStateOf(NativeRoot.projects(this)) }
        var selected by remember { mutableStateOf(NativeRoot.selected(this)) }
        var root by remember { mutableStateOf(false) }
        var checkingRoot by remember { mutableStateOf(NativeRoot.enabled(this)) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var browsing by remember { mutableStateOf(false) }
        var appPicker by remember { mutableStateOf(false) }
        var directoryApp by remember { mutableStateOf("") }
        var directory by remember { mutableStateOf("/sdcard") }
        var entries by remember(browsing, directory) { mutableStateOf(emptyList<ShellFiles.Row>()) }
        var entryCount by remember(browsing, directory) { mutableStateOf(0) }
        var loading by remember(browsing, directory) { mutableStateOf(browsing) }
        var folderError by remember(browsing, directory) { mutableStateOf("") }
        var namePath by remember { mutableStateOf<String?>(null) }
        var name by remember { mutableStateOf("") }
        var manual by remember { mutableStateOf(false) }
        var input by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            if (!NativeRoot.enabled(this@NativeRootActivity)) return@LaunchedEffect
            try {
                val result = withContext(Dispatchers.IO) { RootConnection.service(this@NativeRootActivity).exec("id -u", 5000) }
                check(result.trim() == "0\n0") { "未获得 Root 授权" }
                root = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                message = "Root 授权不可用，不能挂载项目：${e.message}"
            } finally {
                checkingRoot = false
            }
        }

        fun run(action: suspend () -> Unit) {
            if (busy) return
            busy = true
            message = ""
            scope.launch {
                try {
                    action()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    message = e.message.orEmpty()
                } finally {
                    busy = false
                }
            }
        }

        fun store() = NativeRoot.saveProjects(this@NativeRootActivity, projects)

        suspend fun canonical(path: String): String {
            require(path.startsWith("/") && path != "/" && path.none { it == '\n' || it == '\t' || it == '\u0000' }) { "请选择具体文件夹" }
            val raw = withContext(Dispatchers.IO) {
                RootConnection.service(this@NativeRootActivity).exec("test -d ${NativeRoot.quote(path)} && realpath -- ${NativeRoot.quote(path)}", 15_000)
            }
            check(raw.substringBefore('\n') == "0") { "文件夹不存在或无法访问" }
            val real = raw.substringAfter('\n').trim()
            val prefix = "/storage/emulated/${android.os.Process.myUid() / 100000}"
            return if (real == prefix || real.startsWith("$prefix/")) "/data/media/${android.os.Process.myUid() / 100000}" + real.removePrefix(prefix) else real
        }

        LaunchedEffect(browsing, directory) {
            if (!browsing) return@LaunchedEffect
            try {
                val listing = withContext(Dispatchers.IO) {
                    RootConnection.service(this@NativeRootActivity).fsList(directory, false, 1000).lines()
                }
                entries = listing.mapNotNull(ShellFiles::parseRow)
                entryCount = listing.last().removePrefix("#total ").toInt()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                folderError = e.message.orEmpty()
            } finally {
                loading = false
            }
        }

        if (appPicker) {
            AppDirectoryPicker(
                onDismiss = { appPicker = false },
                onSelect = { path, label ->
                    directory = path
                    directoryApp = label
                    appPicker = false
                },
            )
        } else if (browsing) {
            BackHandler {
                if (manual) manual = false else browsing = false
            }
            Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding()) {
                DTopBar(
                    title = if (directoryApp.isEmpty()) "选择项目文件夹" else "$directoryApp · 文件夹",
                    onBack = { browsing = false },
                ) {
                    DButton("手动输入路径", { input = directory; manual = true }, variant = BtnVariant.Ghost, size = BtnSize.Sm)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterPill("手机存储", directory == "/sdcard" && directoryApp.isEmpty()) { directory = "/sdcard"; directoryApp = "" }
                    FilterPill("手机根目录", directory == "/" && directoryApp.isEmpty()) { directory = "/"; directoryApp = "" }
                    FilterPill("所有应用数据", directoryApp.isNotEmpty()) { appPicker = true }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(Radius.md).background(c.bgModule).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DIcon(R.drawable.dsw_folder_open_outline, size = 15.dp, tint = c.labelSecondary)
                    Text(directory, Modifier.weight(1f), style = DshType.mono12, color = c.labelSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (directory != "/") {
                        DButton("上一级", { directory = File(directory).parent ?: "/" }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(20.dp) }
                        folderError.isNotEmpty() -> Text(folderError, Modifier.padding(20.dp), style = DshType.s14, color = c.error)
                        entries.isEmpty() -> EmptyNote("此文件夹为空。")
                        else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                            itemsIndexed(entries, key = { _, item -> item.path }) { index, entry ->
                                SettingsRow(
                                    title = File(entry.path).name,
                                    divider = index > 0,
                                    onClick = if (entry.dir) { { directory = entry.path } } else null,
                                    leading = {
                                        val icon = if (entry.dir) R.drawable.dsw_folder_close else R.drawable.dsw_deliver_doc
                                        DIcon(icon, size = 18.dp, tint = c.labelSecondary)
                                    },
                                    trailing = if (entry.dir) {
                                        { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary) }
                                    } else null,
                                )
                            }
                            if (entryCount > entries.size) {
                                item {
                                    Text(
                                        "显示 ${entries.size} / $entryCount 项，可手动输入路径定位子文件夹。",
                                        Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                                        style = DshType.xxs12,
                                        color = c.labelTertiary,
                                    )
                                }
                            }
                            item { Spacer(Modifier.height(12.dp)) }
                        }
                    }
                }
                HDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().background(c.bgLayer2).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    Text(
                        text = if (directory == "/") "根目录不可直接作为项目" else File(directory).name,
                        modifier = Modifier.weight(1f),
                        style = DshType.xs13,
                        color = c.labelTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DButton("取消", { browsing = false }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                    DButton(
                        text = "选择此文件夹",
                        onClick = {
                            run {
                                namePath = canonical(directory)
                                name = if (directoryApp.isEmpty()) File(directory).name else "$directoryApp · ${File(directory).name}"
                                browsing = false
                            }
                        },
                        enabled = !loading && folderError.isEmpty() && directory != "/" && !busy,
                        size = BtnSize.Sm,
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding()) {
                DTopBar("Root 与项目", ::finish) {
                    DButton(
                        text = "添加项目",
                        onClick = { directory = "/sdcard"; directoryApp = ""; browsing = true },
                        enabled = root && !busy,
                        size = BtnSize.Sm,
                        icon = R.drawable.dsw_plus_outline,
                    )
                }
                Column(
                    modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                        .navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
                ) {
                    Spacer(Modifier.height(8.dp))
                    SettingsCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StateDot(when {
                                    root -> DotKind.Done
                                    checkingRoot -> DotKind.Info
                                    else -> DotKind.Idle
                                })
                                Text("Root 操作", Modifier.weight(1f), style = DshType.xs13m, color = c.labelTertiary)
                                Tag(
                                    text = if (checkingRoot) "验证中" else if (root) "已授权" else "未开启",
                                    tone = if (root) Tone.Success else if (checkingRoot) Tone.Info else Tone.Neutral,
                                )
                            }
                            Text(
                                text = if (checkingRoot) "正在验证 Root 授权…" else if (root) "授权有效 · 引擎直接访问项目原目录" else "Root 未开启 · 项目配置保留，受限目录暂停访问",
                                style = DshType.s14m,
                                color = c.labelPrimary,
                            )
                            if (!root && !checkingRoot) {
                                Text("没有 Root 或未授权时，不能挂载项目。Shizuku 不能代替 Root 挂载。", style = DshType.xs13, color = c.labelSecondary)
                            } else if (root) {
                                Text("Root 模式下引擎与已启用扩展均以 uid 0 运行，请仅安装可信扩展。", style = DshType.xs13, color = c.labelSecondary)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            if (root) {
                                DButton(
                                    text = "关闭 Root",
                                    onClick = {
                                        run {
                                            RootGrant.setAllowed(this@NativeRootActivity, false)
                                            root = false
                                            message = "已关闭 Root，空闲时自动切换普通引擎；有任务时等待结束。项目保留，Root 目录暂停访问。"
                                        }
                                    },
                                    variant = BtnVariant.Outline,
                                    size = BtnSize.Sm,
                                    enabled = !busy,
                                )
                            }
                            DButton(
                                text = if (root) "验证授权" else "开启 Root",
                                onClick = {
                                    run {
                                        try {
                                            check(RootGrant.authorize(this@NativeRootActivity)) { "未获得 Root 授权" }
                                            val result = withContext(Dispatchers.IO) { RootConnection.service(this@NativeRootActivity).exec("id -u", 15000) }
                                            check(result.trim() == "0\n0") { "未获得 Root 授权" }
                                            root = true
                                            message = "Root 授权有效，空闲时自动切换 Root 引擎；有任务时等待结束。"
                                        } catch (e: Exception) {
                                            root = NativeRoot.enabled(this@NativeRootActivity)
                                            throw e
                                        }
                                    }
                                },
                                size = BtnSize.Sm,
                                enabled = !busy && !checkingRoot,
                            )
                        }
                    }

                    if (busy || message.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(Radius.md).background(c.bgModule).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (busy) Spinner(14.dp) else DIcon(R.drawable.dsw_info_outline, size = 15.dp, tint = c.labelSecondary)
                            Text(if (busy) "正在处理…" else message, style = DshType.xs13, color = c.labelSecondary)
                        }
                    }

                    SettingsGroupTitle("工作项目")
                    Text(
                        "添加手机上的文件夹作为项目。助手直接读写原文件，修改立即影响这个文件夹。",
                        Modifier.padding(start = 4.dp, bottom = 10.dp),
                        style = DshType.xs13,
                        color = c.labelTertiary,
                    )

                    if (projects.isEmpty()) {
                        SettingsCard {
                            EmptyNote("还没有项目，点击右上角“添加项目”选择文件夹。")
                        }
                    } else {
                        SettingsCard {
                            projects.forEachIndexed { index, project ->
                                val label = project.name
                                val path = project.path
                                val isCurrent = selected == path
                                if (index > 0) HDivider(Modifier.padding(start = 16.dp))
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        DIcon(R.drawable.dsw_folder_open_outline, size = 18.dp, tint = if (isCurrent) c.success else c.labelSecondary)
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(label, style = DshType.s14m, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (isCurrent) Tag("当前项目", Tone.Success)
                                            }
                                            Text(path, style = DshType.xxs12, color = c.labelTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                                        IconBtn(
                                            res = R.drawable.dsw_trash_outline,
                                            onClick = {
                                                if (isCurrent) {
                                                    NativeRoot.clearDirectory(this@NativeRootActivity)
                                                    selected = ""
                                                }
                                                projects = projects.filterNot { it.path == path }
                                                store()
                                                message = "已移出列表，手机上的文件没有删除。"
                                            },
                                            enabled = !busy,
                                            size = 32.dp,
                                            contentDescription = "移出列表",
                                        )
                                        DButton(
                                            text = if (isCurrent) "取消使用" else "使用此项目",
                                            onClick = {
                                                run {
                                                    if (isCurrent) {
                                                        NativeRoot.clearDirectory(this@NativeRootActivity)
                                                        selected = ""
                                                    } else {
                                                        canonical(path)
                                                        NativeRoot.saveDirectory(this@NativeRootActivity, path)
                                                        selected = path
                                                    }
                                                    message = "已保存默认项目。空闲时自动切换引擎；已有会话可在输入栏「+」菜单中单独切换工作项目。"
                                                }
                                            },
                                            variant = if (isCurrent) BtnVariant.Outline else BtnVariant.Primary,
                                            size = BtnSize.Sm,
                                            enabled = root && !busy,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (projects.isNotEmpty()) {
                        Text(
                            "设为默认项目后新建会话将自动使用；也可在任意对话的输入栏「+」菜单按会话切换项目。",
                            Modifier.padding(start = 4.dp, top = 10.dp),
                            style = DshType.xxs12,
                            color = c.labelTertiary,
                        )
                    }
                }
            }
        }

        if (manual) {
            DDialog(
                onDismiss = { manual = false },
                title = "定位文件夹",
                buttons = {
                    DButton("取消", { manual = false }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                    DButton("打开", { directory = input.trim(); manual = false }, enabled = input.isNotBlank(), size = BtnSize.Sm)
                },
            ) {
                Text("绝对路径", style = DshType.xs13m, color = c.labelSecondary)
                DInput(input, { input = it }, placeholder = "/sdcard")
            }
        }

        if (namePath != null) {
            DDialog(
                onDismiss = { namePath = null },
                title = "添加项目",
                buttons = {
                    DButton("取消", { namePath = null }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                    DButton(
                        text = "添加",
                        onClick = {
                            val path = namePath!!
                            projects = projects.filterNot { it.path == path } + RootProject(name.trim(), path)
                            store()
                            namePath = null
                            message = "项目已添加，点击“使用此项目”开始使用。"
                        },
                        enabled = name.isNotBlank(),
                        size = BtnSize.Sm,
                    )
                },
            ) {
                Text("项目名称", style = DshType.xs13m, color = c.labelSecondary)
                DInput(name, { name = it }, placeholder = "输入项目名称")
                Text(
                    text = namePath.orEmpty(),
                    modifier = Modifier.fillMaxWidth().clip(Radius.sm).background(c.bgModule).padding(horizontal = 10.dp, vertical = 8.dp),
                    style = DshType.mono12,
                    color = c.labelSecondary,
                )
                Text("直接读写这个文件夹里的原文件。", style = DshType.xxs12, color = c.labelTertiary)
            }
        }
    }
}
