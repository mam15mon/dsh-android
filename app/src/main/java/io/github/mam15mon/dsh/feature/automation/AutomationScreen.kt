package io.github.mam15mon.dsh.feature.automation

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mam15mon.dsh.R
import io.github.mam15mon.dsh.core.data.RuleDao
import io.github.mam15mon.dsh.core.data.RuleEntity
import io.github.mam15mon.dsh.core.data.RuleRunEntity
import io.github.mam15mon.dsh.core.data.SettingsRepository
import io.github.mam15mon.dsh.core.designsystem.BtnSize
import io.github.mam15mon.dsh.core.designsystem.BtnVariant
import io.github.mam15mon.dsh.core.designsystem.DButton
import io.github.mam15mon.dsh.core.designsystem.DDialog
import io.github.mam15mon.dsh.core.designsystem.DIcon
import io.github.mam15mon.dsh.core.designsystem.DInput
import io.github.mam15mon.dsh.core.designsystem.DSwitch
import io.github.mam15mon.dsh.core.designsystem.DTopBar
import io.github.mam15mon.dsh.core.designsystem.DotKind
import io.github.mam15mon.dsh.core.designsystem.Dsh
import io.github.mam15mon.dsh.core.designsystem.DshType
import io.github.mam15mon.dsh.core.designsystem.EmptyNote
import io.github.mam15mon.dsh.core.designsystem.FilterPill
import io.github.mam15mon.dsh.core.designsystem.Fmt
import io.github.mam15mon.dsh.core.designsystem.Hairline
import io.github.mam15mon.dsh.core.designsystem.IconBtn
import io.github.mam15mon.dsh.core.designsystem.KeyValue
import io.github.mam15mon.dsh.core.designsystem.Radius
import io.github.mam15mon.dsh.core.designsystem.SettingsCard
import io.github.mam15mon.dsh.core.designsystem.SettingsGroupTitle
import io.github.mam15mon.dsh.core.designsystem.SettingsRow
import io.github.mam15mon.dsh.core.designsystem.StateDot
import io.github.mam15mon.dsh.core.designsystem.Tag
import io.github.mam15mon.dsh.core.designsystem.Tone
import io.github.mam15mon.dsh.core.designsystem.Whale
import io.github.mam15mon.dsh.core.designsystem.pressable
import io.github.mam15mon.dsh.core.model.Condition
import io.github.mam15mon.dsh.core.model.RuleAction
import io.github.mam15mon.dsh.core.model.RuleSpec
import io.github.mam15mon.dsh.core.model.Trigger
import io.github.mam15mon.dsh.core.provider.DEEPSEEK_MODELS
import io.github.mam15mon.dsh.core.trigger.TriggerHub
import io.github.mam15mon.dsh.feature.conversation.ToolPresentation
import io.github.mam15mon.dsh.feature.settings.isZh
import io.github.mam15mon.dsh.nativeextra.NativeRoot
import io.github.mam15mon.dsh.nativeextra.RootProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RuleRow(val entity: RuleEntity, val spec: RuleSpec?)

@HiltViewModel
class AutomationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hub: TriggerHub,
    private val dao: RuleDao,
    settingsRepo: SettingsRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    val rules = dao.observe().map { list -> list.map { RuleRow(it, hub.decode(it)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val ruleId: Long? = saved.get<String>("id")?.toLongOrNull()
    val rule = (ruleId?.let { id -> dao.observeOne(id).map { e -> e?.let { RuleRow(it, hub.decode(it)) } } } ?: emptyFlow())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val runs: Flow<List<RuleRunEntity>> = ruleId?.let { dao.runs(it) } ?: emptyFlow()
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun toggle(id: Long, on: Boolean) = viewModelScope.launch { hub.setEnabled(id, on) }
    fun delete(id: Long) = viewModelScope.launch { hub.delete(id) }
    fun editableTools() = hub.editableTools()
    fun missingPrerequisites(spec: RuleSpec): List<String> = hub.missingPrerequisites(spec)
    fun caveats(spec: RuleSpec): List<String> = hub.caveats(spec)
    fun nextFireAt(row: RuleRow): Long? = row.spec?.let { hub.nextFireAt(row.entity, it) }
    fun rootEnabled(): Boolean = NativeRoot.enabled(context)
    fun rootProjects(): List<RootProject> = NativeRoot.projects(context)

    fun saveAllowedTools(id: Long, added: Set<String>, removed: Set<String>, result: (Boolean) -> Unit) = viewModelScope.launch {
        val saved = try { hub.editAllowedTools(id, added, removed) } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
        result(saved)
    }

    fun runNow(id: Long, onDone: (TriggerHub.ManualRunResult) -> Unit) = viewModelScope.launch {
        val res = try { hub.runNow(id) } catch (e: Exception) {
            if (e is CancellationException) throw e
            TriggerHub.ManualRunResult(false, message = e.message ?: "error")
        }
        onDone(res)
    }

    fun quickEdit(id: Long, transform: (RuleSpec) -> RuleSpec, onDone: (Result<RuleSpec>) -> Unit) = viewModelScope.launch {
        onDone(hub.editSpec(id, transform))
    }
}

private enum class RuleFilter { All, On, Off }

private data class RuleTemplate(
    val titleZh: String,
    val titleEn: String,
    val descZh: String,
    val descEn: String,
    val promptZh: String,
    val promptEn: String,
)

private val RULE_TEMPLATES = listOf(
    RuleTemplate(
        "早间重要通知摘要",
        "Morning notification brief",
        "每天 08:00 · 智能体汇总未读重要消息",
        "Daily 08:00 · Summarize unread notifications",
        "帮我创建一个自动化任务：每天 08:00 自动读取未读通知，总结重要消息并列出需要处理的事项，不重要的推广通知忽略。",
        "Create an automation: every day at 08:00, read unread notifications and summarize the important ones.",
    ),
    RuleTemplate(
        "睡前自动静音",
        "Bedtime silent mode",
        "每天 23:00 · 固定动作免 token 调成静音",
        "Daily 23:00 · Fixed steps (no tokens) set silent",
        "帮我创建一个自动化任务：每天 23:00 用固定步骤（sound_mode）把手机调成静音模式，静默运行不耗费模型 token。",
        "Create an automation: every day at 23:00, use fixed steps (sound_mode) to switch the phone to silent mode without using model tokens.",
    ),
    RuleTemplate(
        "低电量省电提醒",
        "Low-battery check",
        "电量降到 20% · 检查耗电应用并提醒",
        "Battery drops to 20% · Check status and alert",
        "帮我创建一个自动化任务：当未充电且电量降到 20% 时，提醒我并简要汇报当前连接状态与高耗电情况。",
        "Create an automation: when unplugged and battery drops to 20%, notify me with a brief device status check.",
    ),
    RuleTemplate(
        "手动一键整理今日待办",
        "Manual daily recap",
        "手动触发 · 点击即跑，汇总今天的消息与事项",
        "Manual trigger · Run on demand to summarize today",
        "帮我创建一个手动触发（trigger type 为 manual）的自动化任务：一键读取今天收到的重要通知，按优先级整理成今日待办清单。",
        "Create a manual automation (trigger type: manual): on demand, read today's notifications and organize a prioritized todo summary.",
    ),
    RuleTemplate(
        "连上指定 Wi‑Fi 自动静音",
        "Silent on office Wi‑Fi",
        "连上 Wi‑Fi · 固定动作免 token 静音",
        "Wi‑Fi connected · Fixed steps (no tokens) mute",
        "帮我创建一个自动化任务：连上指定 Wi‑Fi（先问我 Wi‑Fi 名称）时，用固定步骤（sound_mode）把手机切到震动或静音。",
        "Create an automation: when connected to a specific Wi-Fi (ask me the SSID first), use fixed steps (sound_mode) to set vibrate or silent.",
    ),
)

@Composable
private fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}

/**
 * dsh 自动化任务 list: search, filter strip, permission warnings, next trigger time, quick templates,
 * and one-tap manual execution.
 */
@Composable
fun AutomationScreen(
    onBack: () -> Unit,
    onNew: (String) -> Unit,
    onOpenRule: (Long) -> Unit,
    onOpenSession: (String) -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    vm: AutomationViewModel = hiltViewModel(),
) {
    val c = Dsh.colors
    val zh = isZh()
    val context = LocalContext.current
    val resumeTick = rememberResumeTick()
    val rules by vm.rules.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(RuleFilter.All) }
    var templatesOpen by remember { mutableStateOf(false) }
    var busyRuleId by remember { mutableStateOf<Long?>(null) }
    var statusBanner by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    val shown = rules.filter { r ->
        when (filter) {
            RuleFilter.All -> true
            RuleFilter.On -> r.entity.enabled
            RuleFilter.Off -> !r.entity.enabled
        }
    }.filter { r ->
        query.isBlank() ||
            (r.spec?.name ?: "").contains(query, true) ||
            (r.spec?.let { describeTrigger(it.trigger, zh) } ?: "").contains(query, true)
    }

    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding().imePadding()) {
        DTopBar(stringResource(R.string.automation), onBack) {
            DButton(stringResource(R.string.rule_templates_btn), { templatesOpen = true }, variant = BtnVariant.Ghost, size = BtnSize.Sm)
            DButton(stringResource(R.string.new_), { onNew(context.getString(R.string.new_rule_prefill)) }, size = BtnSize.Sm, icon = R.drawable.dsw_plus_outline, modifier = Modifier.padding(end = 8.dp))
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DInput(query, { query = it }, placeholder = stringResource(R.string.search_rules), minHeight = 36.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterPill(stringResource(R.string.filter_all_rules), filter == RuleFilter.All) { filter = RuleFilter.All }
                FilterPill(stringResource(R.string.filter_on), filter == RuleFilter.On) { filter = RuleFilter.On }
                FilterPill(stringResource(R.string.filter_off), filter == RuleFilter.Off) { filter = RuleFilter.Off }
            }
            statusBanner?.let { (ok, text) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(Radius.md).background(c.bgModule).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StateDot(if (ok) DotKind.Done else DotKind.Error)
                    Text(text, Modifier.weight(1f), style = DshType.xs13, color = c.labelSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconBtn(R.drawable.dsw_close_outline, { statusBanner = null }, size = 28.dp, iconSize = 14.dp, tint = c.labelTertiary)
                }
            }
        }
        if (rules.isEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Whale(width = 40.dp, tint = c.labelCaption)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.rules_empty_title), style = DshType.base16m, color = c.labelPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.rules_empty_body), style = DshType.xs13, color = c.labelTertiary)
                        Spacer(Modifier.height(14.dp))
                        DButton(stringResource(R.string.new_rule_via_chat), { onNew(context.getString(R.string.new_rule_prefill)) }, variant = BtnVariant.Outline)
                    }
                }
                item { SettingsGroupTitle(stringResource(R.string.rule_templates_title)) }
                item {
                    SettingsCard {
                        RULE_TEMPLATES.forEachIndexed { idx, tpl ->
                            SettingsRow(
                                title = if (zh) tpl.titleZh else tpl.titleEn,
                                description = if (zh) tpl.descZh else tpl.descEn,
                                divider = idx > 0,
                                onClick = { onNew(if (zh) tpl.promptZh else tpl.promptEn) },
                            )
                        }
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shown.isEmpty()) item { EmptyNote(stringResource(R.string.no_matching_rules)) }
                items(shown, key = { it.entity.id }) { r ->
                    val missing = remember(r.entity.enabled, r.entity.specJson, resumeTick) {
                        if (r.entity.enabled && r.spec != null) vm.missingPrerequisites(r.spec) else emptyList()
                    }
                    val nextAt = remember(r.entity.enabled, r.entity.lastFiredAt, r.entity.specJson) { vm.nextFireAt(r) }
                    RuleCard(
                        r = r,
                        zh = zh,
                        nextAt = nextAt,
                        missingCount = missing.size,
                        busy = busyRuleId == r.entity.id,
                        onClick = { onOpenRule(r.entity.id) },
                        onToggle = { vm.toggle(r.entity.id, it) },
                        onRunManual = {
                            if (busyRuleId != null) return@RuleCard
                            busyRuleId = r.entity.id
                            vm.runNow(r.entity.id) { res ->
                                busyRuleId = null
                                if (res.sessionId != null && res.ok) {
                                    onOpenSession(res.sessionId)
                                } else {
                                    val name = r.spec?.name ?: "#${r.entity.id}"
                                    statusBanner = res.ok to if (res.ok) context.getString(R.string.rule_run_steps_ok, name)
                                    else context.getString(R.string.rule_run_steps_fail, name, res.message)
                                }
                            }
                        },
                        onFixPermissions = onOpenPermissions,
                    )
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }

    if (templatesOpen) {
        DDialog(
            onDismiss = { templatesOpen = false },
            title = stringResource(R.string.rule_templates_title),
            buttons = { DButton(stringResource(R.string.cancel), { templatesOpen = false }, variant = BtnVariant.Outline, size = BtnSize.Sm) },
        ) {
            Text(stringResource(R.string.rule_templates_subtitle), style = DshType.xs13, color = c.labelTertiary)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(RULE_TEMPLATES, key = { it.titleEn }) { tpl ->
                    SettingsRow(
                        title = if (zh) tpl.titleZh else tpl.titleEn,
                        description = if (zh) tpl.descZh else tpl.descEn,
                        divider = false,
                        onClick = {
                            templatesOpen = false
                            onNew(if (zh) tpl.promptZh else tpl.promptEn)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleCard(
    r: RuleRow,
    zh: Boolean,
    nextAt: Long?,
    missingCount: Int,
    busy: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRunManual: () -> Unit,
    onFixPermissions: () -> Unit,
) {
    val c = Dsh.colors
    val context = LocalContext.current
    val isManual = r.spec?.trigger is Trigger.Manual
    Row(
        Modifier.fillMaxWidth().clip(Radius.lg).background(c.bgLayer1).border(Hairline, c.borderL2, Radius.lg).pressable(onClick, Radius.lg)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(Radius.tile).background(c.selector), contentAlignment = Alignment.Center) {
            DIcon(triggerIcon(r.spec?.trigger), size = 18.dp, tint = c.labelSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                r.spec?.name ?: "#${r.entity.id}",
                style = DshType.s14m,
                color = if (r.entity.enabled || isManual) c.labelPrimary else c.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            r.spec?.let { Text(describeTrigger(it.trigger, zh), style = DshType.xs13, color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.spec?.let {
                    Tag(
                        stringResource(if (it.action is RuleAction.Steps) R.string.rule_fixed_steps else R.string.rule_agent_run),
                        if (it.action is RuleAction.Steps) Tone.Success else Tone.Info,
                    )
                }
                if (missingCount > 0) {
                    Tag(
                        stringResource(R.string.rule_missing_perm_tag, missingCount),
                        Tone.Warn,
                        Modifier.pressable(onFixPermissions, Radius.sm),
                    )
                }
            }
            val subline = when {
                nextAt != null -> stringResource(R.string.rule_next_fire, Fmt.dateTime(nextAt))
                r.entity.lastFiredAt != null -> stringResource(R.string.rule_last_fired, Fmt.relative(context, r.entity.lastFiredAt))
                else -> stringResource(R.string.rule_never_fired)
            }
            Text(subline, style = DshType.xxs12, color = c.labelCaption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isManual) {
            IconBtn(
                res = R.drawable.dsw_play_outline,
                onClick = onRunManual,
                enabled = !busy,
                contentDescription = stringResource(R.string.rule_run_now),
                tint = c.link,
            )
        } else {
            DSwitch(r.entity.enabled, onToggle)
        }
    }
}

private fun triggerIcon(t: Trigger?): Int = when (t) {
    is Trigger.Time -> R.drawable.dsw_alarm_clock_outline
    is Trigger.Manual -> R.drawable.dsw_play_outline
    is Trigger.Notification -> R.drawable.dsw_info_outline
    is Trigger.AppOpened -> R.drawable.dsw_apps_add_outline
    is Trigger.Geofence -> R.drawable.dsw_globe_outline
    is Trigger.BatteryLow, is Trigger.Power -> R.drawable.dsw_gauge_outline
    else -> R.drawable.dsw_clock_outline
}

/** Rule detail: prerequisites, one-tap run & quick edit, trigger/conditions, action, tools, runs, delete. */
@Composable
fun RuleDetailScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onEditInChat: (String) -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    vm: AutomationViewModel = hiltViewModel(),
) {
    val c = Dsh.colors
    val zh = isZh()
    val context = LocalContext.current
    val resumeTick = rememberResumeTick()
    val row by vm.rule.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle(emptyList())
    var deleting by remember { mutableStateOf(false) }
    var editingTools by remember { mutableStateOf(false) }
    var quickEditing by remember { mutableStateOf(false) }
    var runningNow by remember { mutableStateOf(false) }
    var runNotice by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    val r = row
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding()) {
        DTopBar(r?.spec?.name ?: stringResource(R.string.automation), onBack) {
            if (r != null && r.spec?.trigger !is Trigger.Manual) {
                DSwitch(r.entity.enabled, { on -> vm.toggle(r.entity.id, on) })
                Spacer(Modifier.width(8.dp))
            }
        }
        if (r == null) return@Column
        val s = r.spec
        val missing = remember(r.entity.enabled, r.entity.specJson, resumeTick) {
            if (s != null && r.entity.enabled) vm.missingPrerequisites(s) else emptyList()
        }
        val caveats = remember(r.entity.enabled, r.entity.specJson, resumeTick) {
            if (s != null && r.entity.enabled) vm.caveats(s) else emptyList()
        }
        val nextAt = remember(r.entity.enabled, r.entity.lastFiredAt, r.entity.specJson) { vm.nextFireAt(r) }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            if (missing.isNotEmpty() || caveats.isNotEmpty()) {
                SettingsCard(Modifier.padding(top = 12.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tag(stringResource(R.string.rule_prereq_title), if (missing.isNotEmpty()) Tone.Warn else Tone.Info)
                        missing.forEach { Text("• $it", style = DshType.xs13, color = c.labelPrimary) }
                        caveats.forEach { Text("• $it", style = DshType.xs13, color = c.labelSecondary) }
                        if (missing.isNotEmpty()) {
                            DButton(stringResource(R.string.rule_open_permissions), onOpenPermissions, variant = BtnVariant.Outline, size = BtnSize.Sm)
                        }
                    }
                }
            }

            if (s != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DButton(
                        text = stringResource(R.string.rule_run_now),
                        onClick = {
                            if (runningNow) return@DButton
                            runningNow = true
                            runNotice = null
                            vm.runNow(r.entity.id) { res ->
                                runningNow = false
                                if (res.sessionId != null && res.ok) {
                                    onOpenSession(res.sessionId)
                                } else {
                                    runNotice = res.ok to if (res.ok) context.getString(R.string.rule_run_steps_ok, s.name)
                                    else context.getString(R.string.rule_run_steps_fail, s.name, res.message)
                                }
                            }
                        },
                        enabled = !runningNow,
                        size = BtnSize.Sm,
                        icon = R.drawable.dsw_play_outline,
                    )
                    DButton(stringResource(R.string.rule_quick_edit), { quickEditing = true }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                    DButton(
                        stringResource(R.string.rule_edit_in_chat),
                        { onEditInChat(context.getString(R.string.rule_edit_chat_prefill, r.entity.id, s.name)) },
                        variant = BtnVariant.Outline,
                        size = BtnSize.Sm,
                    )
                }
            }

            runNotice?.let { (ok, text) ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(Radius.md).background(c.bgModule).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StateDot(if (ok) DotKind.Done else DotKind.Error)
                    Text(text, style = DshType.xs13, color = c.labelSecondary)
                }
            }

            SettingsGroupTitle(stringResource(R.string.rule_when))
            SettingsCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    KeyValue(stringResource(R.string.rule_trigger), s?.let { describeTrigger(it.trigger, zh) } ?: "—")
                    s?.conditions?.forEach { KeyValue(stringResource(R.string.rule_condition), describeCondition(it, zh)) }
                    nextAt?.let { KeyValue(stringResource(R.string.rule_next_trigger), Fmt.dateTime(it)) }
                    if (s != null && s.trigger !is Trigger.Manual) {
                        KeyValue(stringResource(R.string.rule_cooldown), stringResource(R.string.rule_cooldown_seconds, s.cooldownSeconds))
                    }
                    if (s != null) {
                        KeyValue(stringResource(R.string.rule_notify_setting), stringResource(if (s.notifyOnSuccess) R.string.rule_notify_on else R.string.rule_notify_fail_only))
                    }
                    KeyValue(stringResource(R.string.rule_created), Fmt.dateTime(r.entity.createdAt))
                    KeyValue(stringResource(R.string.rule_last_trigger), r.entity.lastFiredAt?.let { Fmt.dateTime(it) } ?: stringResource(R.string.rule_never_fired))
                }
            }

            SettingsGroupTitle(stringResource(R.string.rule_then))
            SettingsCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (val a = s?.action) {
                        is RuleAction.Agent -> {
                            Tag(stringResource(R.string.rule_agent_run), Tone.Info)
                            Text(a.prompt, style = DshType.s14, color = c.labelPrimary)
                        }
                        is RuleAction.Steps -> {
                            Tag(stringResource(R.string.rule_fixed_steps), Tone.Success)
                            a.steps.forEachIndexed { i, st -> Text("${i + 1}. ${st.tool} ${st.args}", style = DshType.mono12, color = c.labelSecondary) }
                        }
                        null -> Text("—")
                    }
                    if (s?.action is RuleAction.Agent) {
                        val modelDesc = s.modelId?.let { mid ->
                            if (s.modelProvider.isNullOrEmpty()) DEEPSEEK_MODELS.firstOrNull { it.first == mid }?.second ?: mid
                            else mid
                        } ?: stringResource(R.string.rule_model_default)
                        KeyValue(stringResource(R.string.rule_model_label), modelDesc)
                        if (s.workspace != null) {
                            val wsLabel = if (s.workspace.isEmpty()) stringResource(R.string.rule_workspace_app_home) else s.workspace
                            KeyValue(stringResource(R.string.rule_workspace_label), wsLabel)
                        }
                    }
                    if (s != null) {
                        Text(stringResource(R.string.rule_allowed_tools), style = DshType.xs13m, color = c.labelTertiary)
                        Text(s.allowedTools.joinToString(", ").ifEmpty { stringResource(R.string.automation_no_tools) }, style = DshType.mono12, color = c.labelSecondary)
                        if (s.action is RuleAction.Agent) {
                            DButton(stringResource(R.string.automation_edit_tools), { editingTools = true }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                        }
                    }
                }
            }

            SettingsGroupTitle(stringResource(R.string.rule_recent_runs))
            SettingsCard {
                if (runs.isEmpty()) SettingsRow(stringResource(R.string.rule_no_runs), divider = false)
                runs.forEachIndexed { i, run ->
                    val outcome = when {
                        run.outcome == "ok" -> stringResource(R.string.run_outcome_ok)
                        run.outcome == "started" -> stringResource(R.string.run_outcome_running)
                        run.outcome == "deferred" -> stringResource(R.string.run_outcome_deferred)
                        run.outcome == "stopped" -> stringResource(R.string.run_outcome_stopped)
                        run.outcome.startsWith("fail") || run.outcome.startsWith("error") ->
                            stringResource(R.string.run_outcome_failed, run.outcome.substringAfter(':', "").trim().ifEmpty { run.outcome })
                        else -> run.outcome
                    }
                    val dot = when {
                        run.outcome == "ok" -> DotKind.Done
                        run.outcome == "started" -> DotKind.Info
                        run.outcome == "deferred" -> DotKind.Warn
                        run.outcome.startsWith("fail") || run.outcome.startsWith("error") -> DotKind.Error
                        else -> DotKind.Idle
                    }
                    SettingsRow(
                        Fmt.dateTime(run.firedAt),
                        outcome,
                        onClick = run.sessionId?.let { sid -> { onOpenSession(sid) } },
                        divider = i > 0,
                        leading = { StateDot(dot) },
                        trailing = if (run.sessionId != null) ({ Text(stringResource(R.string.open_session), style = DshType.xs13, color = c.link) }) else null,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            DButton(stringResource(R.string.delete_rule), { deleting = true }, variant = BtnVariant.Danger, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
    }
    if (editingTools && r?.spec != null) AllowedToolsDialog(r, vm) { editingTools = false }
    if (quickEditing && r?.spec != null) QuickEditRuleDialog(r.entity.id, r.spec, vm) { quickEditing = false }
    if (deleting && r != null) DDialog({ deleting = false }, stringResource(R.string.delete_rule), buttons = {
        DButton(stringResource(R.string.cancel), { deleting = false }, variant = BtnVariant.Outline)
        DButton(stringResource(R.string.delete), { deleting = false; vm.delete(r.entity.id); onBack() }, variant = BtnVariant.Danger)
    }) { Text(stringResource(R.string.delete_rule_body), style = DshType.s14) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickEditRuleDialog(id: Long, spec: RuleSpec, vm: AutomationViewModel, onDismiss: () -> Unit) {
    val c = Dsh.colors
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rootEnabled = remember(id) { vm.rootEnabled() }
    val rootProjects = remember(id, rootEnabled) { if (rootEnabled) vm.rootProjects() else emptyList() }

    var name by remember(id) { mutableStateOf(spec.name) }
    val timeTrigger = spec.trigger as? Trigger.Time
    val notifTrigger = spec.trigger as? Trigger.Notification
    var atText by remember(id) { mutableStateOf(timeTrigger?.at.orEmpty()) }
    var everyText by remember(id) { mutableStateOf(timeTrigger?.everyMinutes?.toString().orEmpty()) }
    var keyword by remember(id) { mutableStateOf(notifTrigger?.keyword.orEmpty()) }
    var prompt by remember(id) { mutableStateOf((spec.action as? RuleAction.Agent)?.prompt.orEmpty()) }
    var cooldownText by remember(id) { mutableStateOf(spec.cooldownSeconds.toString()) }
    var notifyOnSuccess by remember(id) { mutableStateOf(spec.notifyOnSuccess) }
    var modelProvider by remember(id) { mutableStateOf(spec.modelProvider) }
    var modelId by remember(id) { mutableStateOf(spec.modelId) }
    var workspace by remember(id) { mutableStateOf(spec.workspace) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DDialog(
        onDismiss = { if (!saving) onDismiss() },
        title = stringResource(R.string.rule_quick_edit),
        buttons = {
            DButton(stringResource(R.string.cancel), onDismiss, enabled = !saving, variant = BtnVariant.Outline)
            DButton(
                text = stringResource(R.string.save),
                onClick = {
                    val cooldown = if (spec.trigger is Trigger.Manual) spec.cooldownSeconds else cooldownText.trim().toIntOrNull()
                    if (cooldown == null) {
                        error = "Invalid cooldown"
                        return@DButton
                    }
                    val every = if (timeTrigger?.everyMinutes != null) everyText.trim().toIntOrNull() else null
                    if (timeTrigger?.everyMinutes != null && every == null) {
                        error = "Invalid interval"
                        return@DButton
                    }
                    saving = true
                    error = null
                    vm.quickEdit(
                        id = id,
                        transform = { cur ->
                            val nextTrigger = when (val t = cur.trigger) {
                                is Trigger.Time -> when {
                                    t.at != null -> t.copy(at = atText)
                                    t.everyMinutes != null -> t.copy(everyMinutes = every)
                                    else -> t
                                }
                                is Trigger.Notification -> t.copy(keyword = keyword.trim().takeIf { it.isNotEmpty() })
                                else -> t
                            }
                            val nextAction = when (val a = cur.action) {
                                is RuleAction.Agent -> a.copy(prompt = prompt)
                                is RuleAction.Steps -> a
                            }
                            cur.copy(
                                name = name.trim(),
                                trigger = nextTrigger,
                                action = nextAction,
                                cooldownSeconds = cooldown,
                                notifyOnSuccess = notifyOnSuccess,
                                modelProvider = modelProvider,
                                modelId = modelId,
                                workspace = workspace,
                            )
                        },
                    ) { result ->
                        saving = false
                        result.onSuccess { onDismiss() }.onFailure { error = it.message ?: it.javaClass.simpleName }
                    }
                },
                enabled = !saving && name.isNotBlank(),
            )
        },
    ) {
        Column(
            Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.rule_name_label), style = DshType.xs13m, color = c.labelSecondary)
            DInput(name, { name = it }, minHeight = 36.dp)

            if (timeTrigger?.at != null) {
                Text(stringResource(R.string.rule_time_at_label), style = DshType.xs13m, color = c.labelSecondary)
                DInput(atText, { atText = it }, placeholder = "08:00", minHeight = 36.dp)
            }
            if (timeTrigger?.everyMinutes != null) {
                Text(stringResource(R.string.rule_interval_label), style = DshType.xs13m, color = c.labelSecondary)
                DInput(everyText, { everyText = it }, keyboardType = KeyboardType.Number, minHeight = 36.dp)
            }
            if (notifTrigger != null) {
                Text(stringResource(R.string.rule_keyword_label), style = DshType.xs13m, color = c.labelSecondary)
                DInput(keyword, { keyword = it }, minHeight = 36.dp)
            }
            if (spec.action is RuleAction.Agent) {
                Text(stringResource(R.string.rule_prompt_label), style = DshType.xs13m, color = c.labelSecondary)
                DInput(prompt, { prompt = it }, singleLine = false, minHeight = 88.dp)
            }
            if (spec.trigger !is Trigger.Manual) {
                Text(stringResource(R.string.rule_cooldown_label), style = DshType.xs13m, color = c.labelSecondary)
                DInput(cooldownText, { cooldownText = it }, keyboardType = KeyboardType.Number, minHeight = 36.dp)
            }
            SettingsRow(
                title = stringResource(R.string.rule_notify_switch),
                divider = false,
                trailing = { DSwitch(notifyOnSuccess, { notifyOnSuccess = it }) },
            )
            if (spec.action is RuleAction.Agent) {
                Text(stringResource(R.string.rule_model_label), style = DshType.xs13m, color = c.labelSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterPill(stringResource(R.string.rule_model_default), modelId == null) {
                        modelProvider = null; modelId = null
                    }
                    DEEPSEEK_MODELS.forEach { (mid, label) ->
                        FilterPill(label, modelProvider == "" && modelId == mid) {
                            modelProvider = ""; modelId = mid
                        }
                    }
                    settings?.providers.orEmpty().filter { it.enabled }.forEach { prov ->
                        prov.models.forEach { mid ->
                            FilterPill("${prov.name} / $mid", modelProvider == prov.id && modelId == mid) {
                                modelProvider = prov.id; modelId = mid
                            }
                        }
                    }
                }
                if (rootEnabled) {
                    Text(stringResource(R.string.rule_workspace_label), style = DshType.xs13m, color = c.labelSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterPill(stringResource(R.string.rule_workspace_default), workspace == null) { workspace = null }
                        FilterPill(stringResource(R.string.rule_workspace_app_home), workspace == "") { workspace = "" }
                        rootProjects.forEach { proj ->
                            FilterPill(proj.name, workspace == proj.path) { workspace = proj.path }
                        }
                    }
                }
            }
            error?.let { Text(it, style = DshType.xs13, color = c.error) }
        }
    }
}

@Composable
private fun AllowedToolsDialog(row: RuleRow, vm: AutomationViewModel, onDismiss: () -> Unit) {
    val initial = remember(row.entity.id) { row.spec?.allowedTools.orEmpty().toSet() }
    var selected by remember(row.entity.id) { mutableStateOf(initial) }
    var names by remember(row.entity.id) { mutableStateOf((vm.editableTools() + selected).toSortedSet()) }
    var query by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val searched = query.trim()
    val canAdd = searched.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.:-]*")) && searched !in names
    DDialog({ if (!saving) onDismiss() }, stringResource(R.string.automation_edit_tools), buttons = {
        DButton(stringResource(R.string.cancel), onDismiss, enabled = !saving, variant = BtnVariant.Outline)
        DButton(stringResource(R.string.save), {
            saving = true
            vm.saveAllowedTools(row.entity.id, selected - initial, initial - selected) { ok ->
                saving = false
                if (ok) onDismiss() else failed = true
            }
        }, enabled = !saving && selected != initial)
    }) {
        DInput(query, { query = it }, placeholder = stringResource(R.string.automation_find_tool))
        if (canAdd) DButton(stringResource(R.string.automation_add_named_tool, searched), {
            names = (names + searched).toSortedSet()
            selected = selected + searched
            query = ""
        }, enabled = !saving, variant = BtnVariant.Outline, size = BtnSize.Sm)
        val labels = names.map { name ->
            val title = ToolPresentation.info(name).title
            name to if (title == R.string.tool_generic) name else stringResource(title)
        }
        val shown = labels.filter { (name, label) -> name.contains(searched, true) || label.contains(searched, true) }
        LazyColumn(Modifier.heightIn(max = 280.dp)) {
            items(shown, key = { it.first }) { (name, label) ->
                SettingsRow(label, name.takeUnless { it == label }, divider = false, trailing = {
                    DSwitch(name in selected, { on -> selected = if (on) selected + name else selected - name }, enabled = !saving)
                })
            }
        }
        if (failed) Text(stringResource(R.string.automation_tools_save_failed), style = DshType.xs13, color = Dsh.colors.error)
    }
}

fun describeTrigger(t: Trigger, zh: Boolean): String = when (t) {
    is Trigger.Manual -> if (zh) "手动触发" else "Manual trigger"
    is Trigger.Time -> t.at?.let { (if (zh) "每天 " else "Daily ") + it + if (t.days.isNotEmpty()) " (${t.days.joinToString(if (zh) "、" else ", ") { weekday(it, zh) }})" else "" }
        ?: t.everyMinutes?.let { if (zh) "每 $it 分钟" else "Every $it min" }
        ?: t.onceAtEpochMs?.let { (if (zh) "单次 · " else "Once · ") + Fmt.dateTime(it) } ?: (if (zh) "单次" else "Once")
    is Trigger.Wifi -> (if (t.connected) (if (zh) "连上 Wi‑Fi" else "Wi‑Fi connected") else (if (zh) "断开 Wi‑Fi" else "Wi‑Fi disconnected")) + (t.ssid?.let { " $it" } ?: "")
    is Trigger.Bluetooth -> (if (t.connected) (if (zh) "蓝牙连接" else "Bluetooth connected") else (if (zh) "蓝牙断开" else "Bluetooth disconnected")) + (t.deviceName?.let { " $it" } ?: "")
    is Trigger.Power -> if (t.connected) (if (zh) "开始充电" else "Charger connected") else (if (zh) "拔掉充电器" else "Charger removed")
    is Trigger.BatteryLow -> (if (zh) "电量降到 " else "Battery at ") + "${t.percent}%"
    is Trigger.Headset -> if (t.plugged) (if (zh) "插入耳机" else "Headset plugged") else (if (zh) "拔出耳机" else "Headset unplugged")
    is Trigger.Screen -> when (t.event) { "on" -> if (zh) "屏幕点亮" else "Screen on"; "off" -> if (zh) "屏幕关闭" else "Screen off"; else -> if (zh) "解锁手机" else "Phone unlocked" }
    is Trigger.Notification -> (if (zh) "收到通知 " else "Notification ") + listOfNotNull(t.packageName, t.keyword).joinToString(" · ")
    is Trigger.AppOpened -> (if (zh) "打开 " else "Opened ") + t.packageName
    is Trigger.Geofence -> (if (t.enter) (if (zh) "到达" else "Arrive") else (if (zh) "离开" else "Leave")) + " ${"%.4f".format(t.lat)},${"%.4f".format(t.lng)}"
}

fun describeCondition(c: Condition, zh: Boolean): String = when (c) {
    is Condition.TimeRange -> (if (zh) "时间 " else "Time ") + "${c.from}–${c.to}"
    is Condition.Days -> (if (zh) "星期" else "Days ") + c.days.joinToString(if (zh) "、" else ", ") { weekday(it, zh) }
    is Condition.Battery -> (if (zh) "电量 " else "Battery ") + listOfNotNull(c.min?.let { "≥$it%" }, c.max?.let { "≤$it%" }).joinToString(" ")
    is Condition.Charging -> if (c.charging) (if (zh) "充电中" else "Charging") else (if (zh) "未充电" else "Not charging")
}

/** 1 = Monday … 7 = Sunday (ISO), as 一…日 / Mon…Sun. */
private fun weekday(d: Int, zh: Boolean): String =
    runCatching { java.time.DayOfWeek.of(d) }.getOrNull()?.let {
        if (zh) "一二三四五六日"[it.value - 1].toString() else it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
    } ?: d.toString()
