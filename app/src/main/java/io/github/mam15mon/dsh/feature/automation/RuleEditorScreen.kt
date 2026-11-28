package io.github.mam15mon.dsh.feature.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mam15mon.dsh.R
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
import io.github.mam15mon.dsh.core.designsystem.FilterPill
import io.github.mam15mon.dsh.core.designsystem.IconBtn
import io.github.mam15mon.dsh.core.designsystem.Radius
import io.github.mam15mon.dsh.core.designsystem.SettingsCard
import io.github.mam15mon.dsh.core.designsystem.SettingsGroupTitle
import io.github.mam15mon.dsh.core.designsystem.SettingsRow
import io.github.mam15mon.dsh.core.designsystem.Spinner
import io.github.mam15mon.dsh.core.designsystem.StateDot
import io.github.mam15mon.dsh.core.model.Condition
import io.github.mam15mon.dsh.core.model.RuleAction
import io.github.mam15mon.dsh.core.model.RuleSpec
import io.github.mam15mon.dsh.core.model.Step
import io.github.mam15mon.dsh.core.model.Trigger
import io.github.mam15mon.dsh.core.provider.DEEPSEEK_MODELS
import io.github.mam15mon.dsh.core.trigger.MAX_RULE_TRIGGERS
import io.github.mam15mon.dsh.feature.conversation.ToolPresentation
import io.github.mam15mon.dsh.feature.settings.isZh
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class RuleTemplate(
    val key: String,
    val titleZh: String,
    val titleEn: String,
    val descZh: String,
    val descEn: String,
    val specZh: RuleSpec,
    val specEn: RuleSpec,
)

private fun silentStep(mode: String = "silent") =
    RuleAction.Steps(listOf(Step("sound_mode", buildJsonObject { put("mode", mode) })))

internal val RULE_TEMPLATES: List<RuleTemplate> = listOf(
    RuleTemplate(
        key = "morning_brief",
        titleZh = "早间重要通知摘要",
        titleEn = "Morning notification brief",
        descZh = "每天 08:00 · 智能体汇总重要未读消息",
        descEn = "Daily 08:00 · Summarize important unread notifications",
        specZh = RuleSpec(
            name = "早间重要通知摘要",
            trigger = Trigger.Time(at = "08:00"),
            action = RuleAction.Agent("读取未读通知，过滤推广广告，按优先级整理重要消息并生成简短汇报。"),
            allowedTools = listOf("notification_list"),
        ),
        specEn = RuleSpec(
            name = "Morning notification brief",
            trigger = Trigger.Time(at = "08:00"),
            action = RuleAction.Agent("Read unread notifications, skip promos, and write a concise prioritized brief."),
            allowedTools = listOf("notification_list"),
        ),
    ),
    RuleTemplate(
        key = "bedtime_silent",
        titleZh = "睡前自动静音",
        titleEn = "Bedtime silent mode",
        descZh = "每天 23:00 · 固定动作免 token 静音",
        descEn = "Daily 23:00 · Fixed steps (no tokens) set silent",
        specZh = RuleSpec(
            name = "睡前自动静音",
            trigger = Trigger.Time(at = "23:00"),
            action = silentStep("silent"),
            allowedTools = listOf("sound_mode"),
            notifyOnSuccess = false,
        ),
        specEn = RuleSpec(
            name = "Bedtime silent mode",
            trigger = Trigger.Time(at = "23:00"),
            action = silentStep("silent"),
            allowedTools = listOf("sound_mode"),
            notifyOnSuccess = false,
        ),
    ),
    RuleTemplate(
        key = "low_battery",
        titleZh = "低电量提醒与检查",
        titleEn = "Low-battery check",
        descZh = "电量降到 20% · 检查连接状态并提醒",
        descEn = "Battery drops to 20% · Check status and alert",
        specZh = RuleSpec(
            name = "低电量提醒",
            trigger = Trigger.BatteryLow(20),
            conditions = listOf(Condition.Charging(false)),
            action = RuleAction.Agent("当前手机未充电且电量降到 20%，检查当前网络、蓝牙与屏幕状态，给出简短省电提醒。"),
            allowedTools = listOf("device_status"),
            cooldownSeconds = 3600,
        ),
        specEn = RuleSpec(
            name = "Low-battery alert",
            trigger = Trigger.BatteryLow(20),
            conditions = listOf(Condition.Charging(false)),
            action = RuleAction.Agent("Battery dropped to 20% while unplugged. Check device status and give a brief power-saving summary."),
            allowedTools = listOf("device_status"),
            cooldownSeconds = 3600,
        ),
    ),
    RuleTemplate(
        key = "manual_recap",
        titleZh = "手动一键整理待办",
        titleEn = "Manual daily recap",
        descZh = "手动触发 · 一键汇总今日通知与待办",
        descEn = "Manual trigger · Run on demand to summarize today",
        specZh = RuleSpec(
            name = "今日待办整理",
            trigger = Trigger.Manual,
            action = RuleAction.Agent("读取今天收到的重要通知，提取需要跟进的待办和回复建议，列出清晰清单。"),
            allowedTools = listOf("notification_list"),
            cooldownSeconds = 0,
        ),
        specEn = RuleSpec(
            name = "Daily todo recap",
            trigger = Trigger.Manual,
            action = RuleAction.Agent("Read today's notifications and produce a prioritized action checklist."),
            allowedTools = listOf("notification_list"),
            cooldownSeconds = 0,
        ),
    ),
    RuleTemplate(
        key = "wifi_silent",
        titleZh = "工作日连公司 Wi‑Fi 静音",
        titleEn = "Silent on work Wi‑Fi",
        descZh = "连上指定 Wi‑Fi 且为工作日 · 固定动作免 token 静音",
        descEn = "Wi‑Fi connected + weekdays · Fixed steps (no tokens) mute",
        specZh = RuleSpec(
            name = "到公司自动静音",
            trigger = Trigger.Wifi(connected = true, ssid = "Office"),
            conditions = listOf(Condition.Days(listOf(1, 2, 3, 4, 5))),
            action = silentStep("vibrate"),
            allowedTools = listOf("sound_mode"),
            notifyOnSuccess = false,
        ),
        specEn = RuleSpec(
            name = "Vibrate on work Wi‑Fi",
            trigger = Trigger.Wifi(connected = true, ssid = "Office"),
            conditions = listOf(Condition.Days(listOf(1, 2, 3, 4, 5))),
            action = silentStep("vibrate"),
            allowedTools = listOf("sound_mode"),
            notifyOnSuccess = false,
        ),
    ),
)

internal fun findRuleTemplate(key: String?): RuleTemplate? =
    key?.let { k -> RULE_TEMPLATES.firstOrNull { it.key == k } }

/** Full-screen visual rule editor supporting create, edit, templates, multi-triggers (OR) and conditions (AND). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    vm: AutomationViewModel = hiltViewModel(),
) {
    val c = Dsh.colors
    val zh = isZh()
    val editingId = vm.editingRuleId
    val templateKey = vm.templateKey
    val existingRow by vm.rule.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rootEnabled = remember { vm.rootEnabled() }
    val rootProjects = remember(rootEnabled) { if (rootEnabled) vm.rootProjects() else emptyList() }

    val seedSpec = remember(editingId, templateKey, existingRow?.entity?.id, zh) {
        when {
            editingId != null -> existingRow?.spec
            else -> findRuleTemplate(templateKey)?.let { if (zh) it.specZh else it.specEn }
                ?: RuleSpec(name = "", trigger = Trigger.Time(at = "08:00"), action = RuleAction.Agent(""))
        }
    }

    var initialized by remember(editingId, templateKey) { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var triggers by remember { mutableStateOf<List<Trigger>>(listOf(Trigger.Time(at = "08:00"))) }
    var conditions by remember { mutableStateOf<List<Condition>>(emptyList()) }
    var useSteps by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf<List<Step>>(listOf(Step("sound_mode", buildJsonObject { put("mode", "silent") }))) }
    var allowedTools by remember { mutableStateOf<Set<String>>(emptySet()) }
    var cooldownText by remember { mutableStateOf("60") }
    var notifyOnSuccess by remember { mutableStateOf(true) }
    var modelProvider by remember { mutableStateOf<String?>(null) }
    var modelId by remember { mutableStateOf<String?>(null) }
    var workspace by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(seedSpec) {
        if (!initialized && seedSpec != null) {
            initialized = true
            name = seedSpec.name
            triggers = seedSpec.allTriggers.ifEmpty { listOf(Trigger.Time(at = "08:00")) }
            conditions = seedSpec.conditions
            when (val a = seedSpec.action) {
                is RuleAction.Agent -> {
                    useSteps = false
                    prompt = a.prompt
                }
                is RuleAction.Steps -> {
                    useSteps = true
                    steps = a.steps
                }
            }
            allowedTools = seedSpec.allowedTools.toSet()
            cooldownText = seedSpec.cooldownSeconds.toString()
            notifyOnSuccess = seedSpec.notifyOnSuccess
            modelProvider = seedSpec.modelProvider
            modelId = seedSpec.modelId
            workspace = seedSpec.workspace
        }
    }

    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editTriggerIdx by remember { mutableStateOf<Int?>(null) }
    var addingTrigger by remember { mutableStateOf(false) }
    var editConditionIdx by remember { mutableStateOf<Int?>(null) }
    var addingCondition by remember { mutableStateOf(false) }
    var editStepIdx by remember { mutableStateOf<Int?>(null) }
    var addingStep by remember { mutableStateOf(false) }
    var pickingTools by remember { mutableStateOf(false) }

    val allManual = triggers.all { it is Trigger.Manual }

    fun saveRule() {
        if (saving || !initialized) return
        val cooldown = if (allManual) cooldownText.trim().toIntOrNull() ?: 0 else cooldownText.trim().toIntOrNull()
        if (cooldown == null) {
            error = if (zh) "冷却秒数必须是数字（0–86400）" else "Cooldown must be a number (0–86400)"
            return
        }
        val action = if (useSteps) RuleAction.Steps(steps) else RuleAction.Agent(prompt.trim())
        val spec = RuleSpec(
            name = name.trim(),
            trigger = triggers.first(),
            conditions = conditions,
            action = action,
            allowedTools = if (useSteps) steps.map { it.tool }.distinct() else allowedTools.sorted(),
            cooldownSeconds = cooldown,
            notifyOnSuccess = notifyOnSuccess,
            modelProvider = if (useSteps) null else modelProvider,
            modelId = if (useSteps) null else modelId,
            workspace = if (useSteps) null else workspace,
            extraTriggers = triggers.drop(1),
        )
        saving = true
        error = null
        if (editingId != null) {
            vm.saveEditedRule(editingId, spec) { result ->
                saving = false
                result.onSuccess { onSaved() }.onFailure { error = it.message ?: it.javaClass.simpleName }
            }
        } else {
            vm.createRule(spec) { result ->
                saving = false
                result.onSuccess { onSaved() }.onFailure { error = it.message ?: it.javaClass.simpleName }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding()) {
        DTopBar(
            title = stringResource(if (editingId != null) R.string.rule_editor_edit_title else R.string.rule_editor_new_title),
            onBack = onBack,
        ) {
            DButton(
                text = stringResource(R.string.save),
                onClick = ::saveRule,
                enabled = initialized && !saving,
                size = BtnSize.Sm,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        if (editingId != null && !initialized) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(24.dp) }
            return@Column
        }
        Column(
            Modifier.fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            error?.let { msg ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clip(Radius.md).background(c.bgModule).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StateDot(DotKind.Error)
                    Text(msg, style = DshType.xs13, color = c.error)
                }
            }

            SettingsGroupTitle(stringResource(R.string.rule_name_label))
            DInput(name, { name = it; error = null }, placeholder = if (zh) "例如：早间通知摘要" else "e.g. Morning brief")

            SettingsGroupTitle("${stringResource(R.string.rule_section_triggers)} (${triggers.size}/$MAX_RULE_TRIGGERS)")
            SettingsCard {
                triggers.forEachIndexed { idx, t ->
                    SettingsRow(
                        title = describeTrigger(t, zh),
                        divider = idx > 0,
                        onClick = { editTriggerIdx = idx },
                        leading = { DIcon(triggerIcon(t), size = 16.dp, tint = c.labelSecondary) },
                        trailing = if (triggers.size > 1) ({
                            IconBtn(
                                res = R.drawable.dsw_trash_outline,
                                onClick = { triggers = triggers.filterIndexed { i, _ -> i != idx } },
                                size = 32.dp,
                                iconSize = 15.dp,
                                tint = c.labelTertiary,
                            )
                        }) else null,
                    )
                }
                if (triggers.size < MAX_RULE_TRIGGERS) {
                    SettingsRow(
                        title = stringResource(R.string.rule_add_trigger),
                        titleColor = c.link,
                        divider = true,
                        onClick = { addingTrigger = true },
                        leading = { DIcon(R.drawable.dsw_plus_outline, size = 16.dp, tint = c.link) },
                    )
                }
            }

            SettingsGroupTitle(stringResource(R.string.rule_section_conditions))
            SettingsCard {
                if (conditions.isEmpty()) {
                    SettingsRow(stringResource(R.string.rule_no_conditions), divider = false)
                }
                conditions.forEachIndexed { idx, cond ->
                    SettingsRow(
                        title = describeCondition(cond, zh),
                        divider = idx > 0,
                        onClick = { editConditionIdx = idx },
                        trailing = {
                            IconBtn(
                                res = R.drawable.dsw_trash_outline,
                                onClick = { conditions = conditions.filterIndexed { i, _ -> i != idx } },
                                size = 32.dp,
                                iconSize = 15.dp,
                                tint = c.labelTertiary,
                            )
                        },
                    )
                }
                SettingsRow(
                    title = stringResource(R.string.rule_add_condition),
                    titleColor = c.link,
                    divider = true,
                    onClick = { addingCondition = true },
                    leading = { DIcon(R.drawable.dsw_plus_outline, size = 16.dp, tint = c.link) },
                )
            }

            SettingsGroupTitle(stringResource(R.string.rule_section_action))
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill(stringResource(R.string.rule_agent_run), !useSteps) { useSteps = false }
                        FilterPill(stringResource(R.string.rule_fixed_steps), useSteps) { useSteps = true }
                    }
                    if (!useSteps) {
                        Text(stringResource(R.string.rule_prompt_label), style = DshType.xs13m, color = c.labelSecondary)
                        DInput(
                            prompt,
                            { prompt = it; error = null },
                            placeholder = if (zh) "描述触发后让智能体做什么…" else "Describe what the agent should do…",
                            singleLine = false,
                            minHeight = 96.dp,
                        )
                        Text(stringResource(R.string.rule_allowed_tools), style = DshType.xs13m, color = c.labelSecondary)
                        Text(
                            allowedTools.sorted().joinToString(", ").ifEmpty { stringResource(R.string.automation_no_tools) },
                            style = DshType.mono12,
                            color = c.labelTertiary,
                        )
                        DButton(
                            stringResource(R.string.automation_edit_tools),
                            { pickingTools = true },
                            variant = BtnVariant.Outline,
                            size = BtnSize.Sm,
                        )
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
                    } else {
                        Column(Modifier.fillMaxWidth()) {
                            steps.forEachIndexed { idx, st ->
                                SettingsRow(
                                    title = "${idx + 1}. ${describeStep(st, zh)}",
                                    divider = idx > 0,
                                    onClick = { editStepIdx = idx },
                                    trailing = if (steps.size > 1) ({
                                        IconBtn(
                                            res = R.drawable.dsw_trash_outline,
                                            onClick = { steps = steps.filterIndexed { i, _ -> i != idx } },
                                            size = 32.dp,
                                            iconSize = 15.dp,
                                            tint = c.labelTertiary,
                                        )
                                    }) else null,
                                )
                            }
                            SettingsRow(
                                title = stringResource(R.string.rule_add_step),
                                titleColor = c.link,
                                divider = true,
                                onClick = { addingStep = true },
                                leading = { DIcon(R.drawable.dsw_plus_outline, size = 16.dp, tint = c.link) },
                            )
                        }
                    }
                }
            }

            SettingsGroupTitle(stringResource(R.string.rule_section_options))
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!allManual) {
                        Text(stringResource(R.string.rule_cooldown_label), style = DshType.xs13m, color = c.labelSecondary)
                        DInput(cooldownText, { cooldownText = it }, keyboardType = KeyboardType.Number, minHeight = 36.dp)
                    }
                    SettingsRow(
                        title = stringResource(R.string.rule_notify_switch),
                        divider = false,
                        trailing = { DSwitch(notifyOnSuccess, { notifyOnSuccess = it }) },
                    )
                }
            }
        }
    }

    if (addingTrigger || editTriggerIdx != null) {
        val current = editTriggerIdx?.let { triggers.getOrNull(it) }
        TriggerEditDialog(
            initial = current,
            zh = zh,
            onDismiss = { addingTrigger = false; editTriggerIdx = null },
            onConfirm = { built ->
                triggers = if (editTriggerIdx != null) {
                    triggers.mapIndexed { idx, old -> if (idx == editTriggerIdx) built else old }
                } else {
                    triggers + built
                }
                addingTrigger = false
                editTriggerIdx = null
            },
        )
    }

    if (addingCondition || editConditionIdx != null) {
        val current = editConditionIdx?.let { conditions.getOrNull(it) }
        ConditionEditDialog(
            initial = current,
            zh = zh,
            onDismiss = { addingCondition = false; editConditionIdx = null },
            onConfirm = { built ->
                conditions = if (editConditionIdx != null) {
                    conditions.mapIndexed { idx, old -> if (idx == editConditionIdx) built else old }
                } else {
                    conditions + built
                }
                addingCondition = false
                editConditionIdx = null
            },
        )
    }

    if (addingStep || editStepIdx != null) {
        val current = editStepIdx?.let { steps.getOrNull(it) }
        StepEditDialog(
            initial = current,
            zh = zh,
            onDismiss = { addingStep = false; editStepIdx = null },
            onConfirm = { built ->
                steps = if (editStepIdx != null) {
                    steps.mapIndexed { idx, old -> if (idx == editStepIdx) built else old }
                } else {
                    steps + built
                }
                addingStep = false
                editStepIdx = null
            },
        )
    }

    if (pickingTools) {
        ToolPickerDialog(
            initial = allowedTools,
            allTools = vm.editableTools(),
            saving = false,
            failed = false,
            onDismiss = { pickingTools = false },
            onConfirm = { updated ->
                allowedTools = updated
                pickingTools = false
            },
        )
    }
}

private enum class TriggerKind {
    TimeAt, TimeEvery, Manual, Call, Sms, Wifi, Bluetooth, Network, Power, BatteryLow, BatteryHigh,
    Headset, Screen, AirplaneMode, Notification, AppOpened, AppClosed, Geofence, Boot,
}

private fun kindOf(t: Trigger): TriggerKind = when (t) {
    is Trigger.Time -> if (t.everyMinutes != null) TriggerKind.TimeEvery else TriggerKind.TimeAt
    is Trigger.Manual -> TriggerKind.Manual
    is Trigger.Call -> TriggerKind.Call
    is Trigger.Sms -> TriggerKind.Sms
    is Trigger.Wifi -> TriggerKind.Wifi
    is Trigger.Bluetooth -> TriggerKind.Bluetooth
    is Trigger.Network -> TriggerKind.Network
    is Trigger.Power -> TriggerKind.Power
    is Trigger.BatteryLow -> TriggerKind.BatteryLow
    is Trigger.BatteryHigh -> TriggerKind.BatteryHigh
    is Trigger.Headset -> TriggerKind.Headset
    is Trigger.Screen -> TriggerKind.Screen
    is Trigger.AirplaneMode -> TriggerKind.AirplaneMode
    is Trigger.Notification -> TriggerKind.Notification
    is Trigger.AppOpened -> TriggerKind.AppOpened
    is Trigger.AppClosed -> TriggerKind.AppClosed
    is Trigger.Geofence -> TriggerKind.Geofence
    is Trigger.Boot -> TriggerKind.Boot
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggerEditDialog(
    initial: Trigger?,
    zh: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Trigger) -> Unit,
) {
    val c = Dsh.colors
    var kind by remember(initial) { mutableStateOf(initial?.let(::kindOf) ?: TriggerKind.TimeAt) }
    var at by remember(initial) { mutableStateOf((initial as? Trigger.Time)?.at ?: "08:00") }
    var days by remember(initial) { mutableStateOf((initial as? Trigger.Time)?.days?.toSet() ?: emptySet()) }
    var everyText by remember(initial) { mutableStateOf((initial as? Trigger.Time)?.everyMinutes?.toString() ?: "60") }
    var callEvent by remember(initial) { mutableStateOf((initial as? Trigger.Call)?.event ?: "ringing") }
    var callNumber by remember(initial) { mutableStateOf((initial as? Trigger.Call)?.number.orEmpty()) }
    var smsSender by remember(initial) { mutableStateOf((initial as? Trigger.Sms)?.sender.orEmpty()) }
    var smsKey by remember(initial) { mutableStateOf((initial as? Trigger.Sms)?.keyword.orEmpty()) }
    var smsEx by remember(initial) { mutableStateOf((initial as? Trigger.Sms)?.excludeKeyword.orEmpty()) }
    var wifiConn by remember(initial) { mutableStateOf((initial as? Trigger.Wifi)?.connected ?: true) }
    var wifiSsid by remember(initial) { mutableStateOf((initial as? Trigger.Wifi)?.ssid.orEmpty()) }
    var btConn by remember(initial) { mutableStateOf((initial as? Trigger.Bluetooth)?.connected ?: true) }
    var btName by remember(initial) { mutableStateOf((initial as? Trigger.Bluetooth)?.deviceName.orEmpty()) }
    var netType by remember(initial) { mutableStateOf((initial as? Trigger.Network)?.type ?: "wifi") }
    var powerConn by remember(initial) { mutableStateOf((initial as? Trigger.Power)?.connected ?: true) }
    var battLowText by remember(initial) { mutableStateOf((initial as? Trigger.BatteryLow)?.percent?.toString() ?: "20") }
    var battHighText by remember(initial) { mutableStateOf((initial as? Trigger.BatteryHigh)?.percent?.toString() ?: "80") }
    var headsetPlug by remember(initial) { mutableStateOf((initial as? Trigger.Headset)?.plugged ?: true) }
    var screenEv by remember(initial) { mutableStateOf((initial as? Trigger.Screen)?.event ?: "unlock") }
    var airplaneOn by remember(initial) { mutableStateOf((initial as? Trigger.AirplaneMode)?.enabled ?: true) }
    var notifPkg by remember(initial) { mutableStateOf((initial as? Trigger.Notification)?.packageName.orEmpty()) }
    var notifKey by remember(initial) { mutableStateOf((initial as? Trigger.Notification)?.keyword.orEmpty()) }
    var notifEx by remember(initial) { mutableStateOf((initial as? Trigger.Notification)?.excludeKeyword.orEmpty()) }
    var appPkg by remember(initial) {
        mutableStateOf((initial as? Trigger.AppOpened)?.packageName ?: (initial as? Trigger.AppClosed)?.packageName.orEmpty())
    }
    var geoLat by remember(initial) { mutableStateOf((initial as? Trigger.Geofence)?.lat?.toString() ?: "") }
    var geoLng by remember(initial) { mutableStateOf((initial as? Trigger.Geofence)?.lng?.toString() ?: "") }
    var geoRad by remember(initial) { mutableStateOf((initial as? Trigger.Geofence)?.radiusM?.toInt()?.toString() ?: "200") }
    var geoEnter by remember(initial) { mutableStateOf((initial as? Trigger.Geofence)?.enter ?: true) }
    var err by remember { mutableStateOf<String?>(null) }

    val onceTrigger = (initial as? Trigger.Time)?.takeIf { it.onceAtEpochMs != null && it.at == null }

    DDialog(
        onDismiss = onDismiss,
        title = stringResource(if (initial != null) R.string.rule_trigger_dialog_edit else R.string.rule_trigger_dialog_add),
        buttons = {
            DButton(stringResource(R.string.cancel), onDismiss, variant = BtnVariant.Outline, size = BtnSize.Sm)
            DButton(stringResource(R.string.save), {
                err = null
                val built: Trigger? = when (kind) {
                    TriggerKind.TimeAt -> onceTrigger ?: Trigger.Time(at = at.trim(), days = days.sorted())
                    TriggerKind.TimeEvery -> {
                        val m = everyText.trim().toIntOrNull()
                        if (m == null || m < 15) {
                            err = if (zh) "间隔至少 15 分钟" else "Interval must be at least 15 minutes"; null
                        } else Trigger.Time(everyMinutes = m)
                    }
                    TriggerKind.Manual -> Trigger.Manual
                    TriggerKind.Call -> Trigger.Call(callEvent, callNumber.trim().takeIf { it.isNotEmpty() })
                    TriggerKind.Sms -> Trigger.Sms(
                        smsSender.trim().takeIf { it.isNotEmpty() },
                        smsKey.trim().takeIf { it.isNotEmpty() },
                        smsEx.trim().takeIf { it.isNotEmpty() },
                    )
                    TriggerKind.Wifi -> Trigger.Wifi(wifiConn, wifiSsid.trim().takeIf { it.isNotEmpty() })
                    TriggerKind.Bluetooth -> Trigger.Bluetooth(btConn, btName.trim().takeIf { it.isNotEmpty() })
                    TriggerKind.Network -> Trigger.Network(netType)
                    TriggerKind.Power -> Trigger.Power(powerConn)
                    TriggerKind.BatteryLow -> {
                        val p = battLowText.trim().toIntOrNull()
                        if (p == null || p !in 1..99) {
                            err = if (zh) "电量需在 1–99 之间" else "Percent must be 1–99"; null
                        } else Trigger.BatteryLow(p)
                    }
                    TriggerKind.BatteryHigh -> {
                        val p = battHighText.trim().toIntOrNull()
                        if (p == null || p !in 2..100) {
                            err = if (zh) "电量需在 2–100 之间" else "Percent must be 2–100"; null
                        } else Trigger.BatteryHigh(p)
                    }
                    TriggerKind.Headset -> Trigger.Headset(headsetPlug)
                    TriggerKind.Screen -> Trigger.Screen(screenEv)
                    TriggerKind.AirplaneMode -> Trigger.AirplaneMode(airplaneOn)
                    TriggerKind.Boot -> Trigger.Boot
                    TriggerKind.Notification -> Trigger.Notification(
                        notifPkg.trim().takeIf { it.isNotEmpty() },
                        notifKey.trim().takeIf { it.isNotEmpty() },
                        notifEx.trim().takeIf { it.isNotEmpty() },
                    )
                    TriggerKind.AppOpened -> {
                        val p = appPkg.trim()
                        if (p.isEmpty()) {
                            err = if (zh) "请填写应用包名" else "Package name required"; null
                        } else Trigger.AppOpened(p)
                    }
                    TriggerKind.AppClosed -> {
                        val p = appPkg.trim()
                        if (p.isEmpty()) {
                            err = if (zh) "请填写应用包名" else "Package name required"; null
                        } else Trigger.AppClosed(p)
                    }
                    TriggerKind.Geofence -> {
                        val la = geoLat.trim().toDoubleOrNull()
                        val ln = geoLng.trim().toDoubleOrNull()
                        val r = geoRad.trim().toFloatOrNull() ?: 200f
                        if (la == null || ln == null || la !in -90.0..90.0 || ln !in -180.0..180.0) {
                            err = if (zh) "请输入合法经纬度" else "Valid lat/lng required"; null
                        } else Trigger.Geofence(la, ln, r.coerceIn(50f, 100_000f), geoEnter)
                    }
                }
                if (built != null) onConfirm(built)
            }, size = BtnSize.Sm)
        },
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    TriggerKind.TimeAt to (if (zh) "定时" else "Time"),
                    TriggerKind.TimeEvery to (if (zh) "间隔" else "Interval"),
                    TriggerKind.Manual to (if (zh) "手动" else "Manual"),
                    TriggerKind.Call to (if (zh) "来电/通话" else "Call"),
                    TriggerKind.Sms to (if (zh) "短信" else "SMS"),
                    TriggerKind.Notification to (if (zh) "通知" else "Notification"),
                    TriggerKind.AppOpened to (if (zh) "打开应用" else "App opened"),
                    TriggerKind.AppClosed to (if (zh) "退出应用" else "App closed"),
                    TriggerKind.Wifi to "Wi‑Fi",
                    TriggerKind.Bluetooth to (if (zh) "蓝牙" else "Bluetooth"),
                    TriggerKind.Network to (if (zh) "网络切换" else "Network"),
                    TriggerKind.Power to (if (zh) "充电" else "Power"),
                    TriggerKind.BatteryLow to (if (zh) "低电量" else "Low battery"),
                    TriggerKind.BatteryHigh to (if (zh) "高电量" else "High battery"),
                    TriggerKind.Headset to (if (zh) "耳机" else "Headset"),
                    TriggerKind.Screen to (if (zh) "屏幕" else "Screen"),
                    TriggerKind.AirplaneMode to (if (zh) "飞行模式" else "Airplane"),
                    TriggerKind.Geofence to (if (zh) "地点围栏" else "Geofence"),
                    TriggerKind.Boot to (if (zh) "开机" else "Boot"),
                ).forEach { (k, label) ->
                    FilterPill(label, kind == k) { kind = k; err = null }
                }
            }
            when (kind) {
                TriggerKind.TimeAt -> if (onceTrigger != null) {
                    Text(describeTrigger(onceTrigger, zh), style = DshType.xs13, color = c.labelSecondary)
                } else {
                    Text(stringResource(R.string.rule_time_at_label), style = DshType.xs13m, color = c.labelSecondary)
                    DInput(at, { at = it }, placeholder = "08:00", minHeight = 36.dp)
                    WeekdayPills(days, zh) { d -> days = if (d in days) days - d else days + d }
                }
                TriggerKind.TimeEvery -> {
                    Text(stringResource(R.string.rule_interval_label), style = DshType.xs13m, color = c.labelSecondary)
                    DInput(everyText, { everyText = it }, keyboardType = KeyboardType.Number, minHeight = 36.dp)
                }
                TriggerKind.Manual -> Text(
                    if (zh) "仅通过列表或详情页按钮手动一键触发。" else "Runs only when you tap Run manually.",
                    style = DshType.xs13, color = c.labelTertiary,
                )
                TriggerKind.Call -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "ringing" to (if (zh) "来电响铃" else "Ringing"),
                            "answered" to (if (zh) "已接听" else "Answered"),
                            "missed" to (if (zh) "未接来电" else "Missed"),
                            "ended" to (if (zh) "已挂断" else "Ended"),
                        ).forEach { (ev, label) ->
                            FilterPill(label, callEvent == ev) { callEvent = ev }
                        }
                    }
                    DInput(callNumber, { callNumber = it }, placeholder = if (zh) "指定号码片段（留空匹配所有来电）" else "Number filter (optional)", minHeight = 36.dp)
                }
                TriggerKind.Sms -> {
                    DInput(smsSender, { smsSender = it }, placeholder = if (zh) "发件人号码（留空不限）" else "Sender (optional)", minHeight = 36.dp)
                    DInput(smsKey, { smsKey = it }, placeholder = if (zh) "短信包含关键词（可选）" else "Keyword (optional)", minHeight = 36.dp)
                    DInput(smsEx, { smsEx = it }, placeholder = if (zh) "排除关键词（可选）" else "Exclude keyword (optional)", minHeight = 36.dp)
                }
                TriggerKind.Wifi -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill(if (zh) "连上 Wi‑Fi" else "Connected", wifiConn) { wifiConn = true }
                        FilterPill(if (zh) "断开 Wi‑Fi" else "Disconnected", !wifiConn) { wifiConn = false }
                    }
                    DInput(wifiSsid, { wifiSsid = it }, placeholder = if (zh) "Wi‑Fi 名称 SSID（留空不限）" else "SSID (optional)", minHeight = 36.dp)
                }
                TriggerKind.Bluetooth -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill(if (zh) "蓝牙已连接" else "Connected", btConn) { btConn = true }
                        FilterPill(if (zh) "蓝牙断开" else "Disconnected", !btConn) { btConn = false }
                    }
                    DInput(btName, { btName = it }, placeholder = if (zh) "设备名称关键字（留空不限）" else "Device name (optional)", minHeight = 36.dp)
                }
                TriggerKind.Network -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("Wi‑Fi", netType == "wifi") { netType = "wifi" }
                    FilterPill(if (zh) "移动流量" else "Cellular", netType == "cellular") { netType = "cellular" }
                    FilterPill(if (zh) "断网" else "Offline", netType == "offline") { netType = "offline" }
                }
                TriggerKind.Power -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(if (zh) "开始充电" else "Plugged in", powerConn) { powerConn = true }
                    FilterPill(if (zh) "拔掉充电器" else "Unplugged", !powerConn) { powerConn = false }
                }
                TriggerKind.BatteryLow -> DInput(battLowText, { battLowText = it }, keyboardType = KeyboardType.Number, placeholder = "20", minHeight = 36.dp)
                TriggerKind.BatteryHigh -> DInput(battHighText, { battHighText = it }, keyboardType = KeyboardType.Number, placeholder = "80", minHeight = 36.dp)
                TriggerKind.Headset -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(if (zh) "插入耳机" else "Plugged", headsetPlug) { headsetPlug = true }
                    FilterPill(if (zh) "拔出耳机" else "Unplugged", !headsetPlug) { headsetPlug = false }
                }
                TriggerKind.Screen -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(if (zh) "亮屏" else "On", screenEv == "on") { screenEv = "on" }
                    FilterPill(if (zh) "息屏" else "Off", screenEv == "off") { screenEv = "off" }
                    FilterPill(if (zh) "解锁" else "Unlock", screenEv == "unlock") { screenEv = "unlock" }
                }
                TriggerKind.AirplaneMode -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(if (zh) "开启飞行模式" else "Turned on", airplaneOn) { airplaneOn = true }
                    FilterPill(if (zh) "关闭飞行模式" else "Turned off", !airplaneOn) { airplaneOn = false }
                }
                TriggerKind.Boot -> Text(
                    if (zh) "手机重启并开机完成后触发一次。" else "Runs once after device boot completes.",
                    style = DshType.xs13, color = c.labelTertiary,
                )
                TriggerKind.Notification -> {
                    DInput(notifPkg, { notifPkg = it }, placeholder = if (zh) "应用包名（留空匹配所有 App）" else "Package name (optional)", minHeight = 36.dp)
                    DInput(notifKey, { notifKey = it }, placeholder = if (zh) "必须包含的关键词（可选）" else "Must contain keyword (optional)", minHeight = 36.dp)
                    DInput(notifEx, { notifEx = it }, placeholder = if (zh) "排除包含此词的通知（可选）" else "Exclude keyword (optional)", minHeight = 36.dp)
                }
                TriggerKind.AppOpened, TriggerKind.AppClosed ->
                    DInput(appPkg, { appPkg = it }, placeholder = if (zh) "应用包名，如 com.tencent.mm" else "Package name, e.g. com.example.app", minHeight = 36.dp)
                TriggerKind.Geofence -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill(if (zh) "到达地点" else "Enter", geoEnter) { geoEnter = true }
                        FilterPill(if (zh) "离开地点" else "Exit", !geoEnter) { geoEnter = false }
                    }
                    DInput(geoLat, { geoLat = it }, placeholder = if (zh) "纬度 (lat)" else "Latitude", keyboardType = KeyboardType.Decimal, minHeight = 36.dp)
                    DInput(geoLng, { geoLng = it }, placeholder = if (zh) "经度 (lng)" else "Longitude", keyboardType = KeyboardType.Decimal, minHeight = 36.dp)
                    DInput(geoRad, { geoRad = it }, placeholder = if (zh) "半径米（50–100000）" else "Radius m (50–100000)", keyboardType = KeyboardType.Number, minHeight = 36.dp)
                }
            }
            err?.let { Text(it, style = DshType.xs13, color = c.error) }
        }
    }
}

private enum class CondKind {
    TimeRange, Days, Battery, Charging, Wifi, Network, Screen, Bluetooth, InCall, SoundMode, Headset, ForegroundApp,
}

private fun condKindOf(c: Condition): CondKind = when (c) {
    is Condition.TimeRange -> CondKind.TimeRange
    is Condition.Days -> CondKind.Days
    is Condition.Battery -> CondKind.Battery
    is Condition.Charging -> CondKind.Charging
    is Condition.Wifi -> CondKind.Wifi
    is Condition.Network -> CondKind.Network
    is Condition.Screen -> CondKind.Screen
    is Condition.Bluetooth -> CondKind.Bluetooth
    is Condition.InCall -> CondKind.InCall
    is Condition.SoundMode -> CondKind.SoundMode
    is Condition.Headset -> CondKind.Headset
    is Condition.ForegroundApp -> CondKind.ForegroundApp
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionEditDialog(
    initial: Condition?,
    zh: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Condition) -> Unit,
) {
    val c = Dsh.colors
    var kind by remember(initial) { mutableStateOf(initial?.let(::condKindOf) ?: CondKind.TimeRange) }
    var fromTime by remember(initial) { mutableStateOf((initial as? Condition.TimeRange)?.from ?: "09:00") }
    var toTime by remember(initial) { mutableStateOf((initial as? Condition.TimeRange)?.to ?: "18:00") }
    var days by remember(initial) { mutableStateOf((initial as? Condition.Days)?.days?.toSet() ?: setOf(1, 2, 3, 4, 5)) }
    var minBat by remember(initial) { mutableStateOf((initial as? Condition.Battery)?.min?.toString().orEmpty()) }
    var maxBat by remember(initial) { mutableStateOf((initial as? Condition.Battery)?.max?.toString().orEmpty()) }
    var charging by remember(initial) { mutableStateOf((initial as? Condition.Charging)?.charging ?: true) }
    var wifiConn by remember(initial) { mutableStateOf((initial as? Condition.Wifi)?.connected ?: true) }
    var wifiSsid by remember(initial) { mutableStateOf((initial as? Condition.Wifi)?.ssid.orEmpty()) }
    var netType by remember(initial) { mutableStateOf((initial as? Condition.Network)?.type ?: "wifi") }
    var screenState by remember(initial) { mutableStateOf((initial as? Condition.Screen)?.state ?: "unlocked") }
    var btConn by remember(initial) { mutableStateOf((initial as? Condition.Bluetooth)?.connected ?: true) }
    var inCall by remember(initial) { mutableStateOf((initial as? Condition.InCall)?.inCall ?: false) }
    var soundMode by remember(initial) { mutableStateOf((initial as? Condition.SoundMode)?.mode ?: "normal") }
    var headsetPlug by remember(initial) { mutableStateOf((initial as? Condition.Headset)?.plugged ?: true) }
    var fgPkg by remember(initial) { mutableStateOf((initial as? Condition.ForegroundApp)?.packageName.orEmpty()) }
    var fgInvert by remember(initial) { mutableStateOf((initial as? Condition.ForegroundApp)?.invert ?: false) }
    var err by remember { mutableStateOf<String?>(null) }

    DDialog(
        onDismiss = onDismiss,
        title = stringResource(if (initial != null) R.string.rule_condition_dialog_edit else R.string.rule_condition_dialog_add),
        buttons = {
            DButton(stringResource(R.string.cancel), onDismiss, variant = BtnVariant.Outline, size = BtnSize.Sm)
            DButton(stringResource(R.string.save), {
                err = null
                val built: Condition? = when (kind) {
                    CondKind.TimeRange -> Condition.TimeRange(fromTime.trim(), toTime.trim())
                    CondKind.Days -> if (days.isEmpty()) {
                        err = if (zh) "请至少选一天" else "Select at least one day"; null
                    } else Condition.Days(days.sorted())
                    CondKind.Battery -> {
                        val min = minBat.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                        val max = maxBat.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                        if ((minBat.isNotBlank() && min == null) || (maxBat.isNotBlank() && max == null) || (min == null && max == null)) {
                            err = if (zh) "请填写最小或最大电量（0–100）" else "Enter min or max battery"; null
                        } else Condition.Battery(min, max)
                    }
                    CondKind.Charging -> Condition.Charging(charging)
                    CondKind.Wifi -> Condition.Wifi(wifiConn, wifiSsid.trim().takeIf { it.isNotEmpty() })
                    CondKind.Network -> Condition.Network(netType)
                    CondKind.Screen -> Condition.Screen(screenState)
                    CondKind.Bluetooth -> Condition.Bluetooth(btConn)
                    CondKind.InCall -> Condition.InCall(inCall)
                    CondKind.SoundMode -> Condition.SoundMode(soundMode)
                    CondKind.Headset -> Condition.Headset(headsetPlug)
                    CondKind.ForegroundApp -> {
                        val p = fgPkg.trim()
                        if (p.isEmpty()) {
                            err = if (zh) "请填写应用包名" else "Package name required"; null
                        } else Condition.ForegroundApp(p, fgInvert)
                    }
                }
                if (built != null) onConfirm(built)
            }, size = BtnSize.Sm)
        },
    ) {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    CondKind.TimeRange to (if (zh) "时间段" else "Time range"),
                    CondKind.Days to (if (zh) "星期" else "Days"),
                    CondKind.Battery to (if (zh) "电量" else "Battery"),
                    CondKind.Charging to (if (zh) "充电中" else "Charging"),
                    CondKind.Wifi to "Wi‑Fi",
                    CondKind.Network to (if (zh) "网络类型" else "Network"),
                    CondKind.Screen to (if (zh) "屏幕" else "Screen"),
                    CondKind.Bluetooth to (if (zh) "蓝牙" else "Bluetooth"),
                    CondKind.InCall to (if (zh) "通话中" else "In call"),
                    CondKind.SoundMode to (if (zh) "响铃/勿扰" else "Sound mode"),
                    CondKind.Headset to (if (zh) "耳机" else "Headset"),
                    CondKind.ForegroundApp to (if (zh) "前台应用" else "Foreground app"),
                ).forEach { (k, label) ->
                    FilterPill(label, kind == k) { kind = k; err = null }
                }
            }
            when (kind) {
                CondKind.TimeRange -> {
                    DInput(fromTime, { fromTime = it }, placeholder = if (zh) "开始时间，如 09:00" else "From (09:00)", minHeight = 36.dp)
                    DInput(toTime, { toTime = it }, placeholder = if (zh) "结束时间，如 18:00" else "To (18:00)", minHeight = 36.dp)
                }
                CondKind.Days -> WeekdayPills(days, zh) { d -> days = if (d in days) days - d else days + d }
                CondKind.Battery -> {
                    DInput(minBat, { minBat = it }, placeholder = if (zh) "最低电量 %（可选）" else "Min % (optional)", keyboardType = KeyboardType.Number, minHeight = 36.dp)
                    DInput(maxBat, { maxBat = it }, placeholder = if (zh) "最高电量 %（可选）" else "Max % (optional)", keyboardType = KeyboardType.Number, minHeight = 36.dp)
                }
                CondKind.Charging -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(if (zh) "正在充电" else "Charging", charging) { charging = true }
                    FilterPill(if (zh) "未充电" else "Not charging", !charging) { charging = false }
                }
                CondKind.Wifi -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill(if (zh) "已连 Wi‑Fi" else "On Wi‑Fi", wifiConn) { wifiConn = true }
                        FilterPill(if (zh) "未连 Wi‑Fi" else "Off Wi‑Fi", !wifiConn) { wifiConn = false }
                    }
                    DInput(wifiSsid, { wifiSsid = it }, placeholder = if (zh) "指定 SSID（留空匹配任意）" else "SSID (optional)", minHeight = 36.dp)
                }
                CondKind.Network -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("Wi‑Fi", netType == "wifi") { netType = "wifi" }
                    FilterPill(if (zh) "移动流量" else "Cellular", netType == "cellular") { netType = "cellular" }
                    FilterPill(if (zh) "无网络" else "Offline", netType == "offline") { netType = "offline" }
                }
                CondKind.Screen -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "unlocked" to (if (zh) "已解锁" else "Unlocked"),
                        "locked" to (if (zh) "锁屏/息屏" else "Locked"),
                        "on" to (if (zh) "亮屏中" else "Screen on"),
                        "off" to (if (zh) "息屏中" else "Screen off"),
                    ).forEach { (v, label) ->
                        FilterPill(label, screenState == v) { screenState = v }
                    }
                }
                CondKind.Bluetooth -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(if (zh) "蓝牙已连" else "Connected", btConn) { btConn = true }
                    FilterPill(if (zh) "蓝牙未连" else "Disconnected", !btConn) { btConn = false }
                }
                CondKind.InCall -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(if (zh) "正在通话" else "In call", inCall) { inCall = true }
                    FilterPill(if (zh) "不在通话中" else "Not in call", !inCall) { inCall = false }
                }
                CondKind.SoundMode -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "normal" to (if (zh) "正常响铃" else "Normal"),
                        "vibrate" to (if (zh) "震动" else "Vibrate"),
                        "silent" to (if (zh) "静音" else "Silent"),
                        "dnd" to (if (zh) "勿扰中" else "DND on"),
                    ).forEach { (m, label) ->
                        FilterPill(label, soundMode == m) { soundMode = m }
                    }
                }
                CondKind.Headset -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(if (zh) "已连接耳机" else "Connected", headsetPlug) { headsetPlug = true }
                    FilterPill(if (zh) "未连接耳机" else "Disconnected", !headsetPlug) { headsetPlug = false }
                }
                CondKind.ForegroundApp -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill(if (zh) "当前处于该应用" else "In app", !fgInvert) { fgInvert = false }
                        FilterPill(if (zh) "排除该应用" else "Not in app", fgInvert) { fgInvert = true }
                    }
                    DInput(fgPkg, { fgPkg = it }, placeholder = if (zh) "应用包名，如 com.tencent.mm" else "Package name", minHeight = 36.dp)
                }
            }
            err?.let { Text(it, style = DshType.xs13, color = c.error) }
        }
    }
}

private enum class StepKind(val tool: String) {
    SoundMode("sound_mode"),
    Volume("volume_set"),
    Dnd("dnd_set"),
    Brightness("brightness_set"),
    Flashlight("flashlight"),
    Wifi("wifi_toggle"),
    Bluetooth("bluetooth_toggle"),
    MobileData("mobile_data_toggle"),
    Media("media_control"),
    AppLaunch("app_launch"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepEditDialog(
    initial: Step?,
    zh: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Step) -> Unit,
) {
    val c = Dsh.colors
    var kind by remember(initial) { mutableStateOf(StepKind.entries.firstOrNull { it.tool == initial?.tool } ?: StepKind.SoundMode) }
    var soundMode by remember(initial) { mutableStateOf(initial?.args?.get("mode")?.jsonPrimitive?.contentOrNull ?: "silent") }
    var stream by remember(initial) { mutableStateOf(initial?.args?.get("stream")?.jsonPrimitive?.contentOrNull ?: "music") }
    var volText by remember(initial) { mutableStateOf((initial?.args?.get("percent")?.jsonPrimitive?.intOrNull ?: 50).toString()) }
    var boolOn by remember(initial) { mutableStateOf(initial?.args?.get("on")?.jsonPrimitive?.booleanOrNull ?: true) }
    var brightAuto by remember(initial) { mutableStateOf(initial?.args?.get("auto")?.jsonPrimitive?.booleanOrNull ?: false) }
    var brightPct by remember(initial) { mutableStateOf((initial?.args?.get("percent")?.jsonPrimitive?.intOrNull ?: 60).toString()) }
    var mediaAct by remember(initial) { mutableStateOf(initial?.args?.get("action")?.jsonPrimitive?.contentOrNull ?: "toggle") }
    var launchPkg by remember(initial) { mutableStateOf(initial?.args?.get("package")?.jsonPrimitive?.contentOrNull.orEmpty()) }
    var err by remember { mutableStateOf<String?>(null) }

    val customStep = initial?.takeIf { StepKind.entries.none { k -> k.tool == it.tool } }

    DDialog(
        onDismiss = onDismiss,
        title = stringResource(if (initial != null) R.string.rule_step_dialog_edit else R.string.rule_step_dialog_add),
        buttons = {
            DButton(stringResource(R.string.cancel), onDismiss, variant = BtnVariant.Outline, size = BtnSize.Sm)
            DButton(stringResource(R.string.save), {
                if (customStep != null) {
                    onConfirm(customStep)
                    return@DButton
                }
                err = null
                val built: Step? = when (kind) {
                    StepKind.SoundMode -> Step("sound_mode", buildJsonObject { put("mode", soundMode) })
                    StepKind.Volume -> {
                        val p = volText.trim().toIntOrNull()
                        if (p == null || p !in 0..100) {
                            err = if (zh) "音量需在 0–100" else "Volume 0–100"; null
                        } else Step("volume_set", buildJsonObject { put("stream", stream); put("percent", p) })
                    }
                    StepKind.Dnd -> Step("dnd_set", buildJsonObject { put("on", boolOn) })
                    StepKind.Brightness -> if (brightAuto) {
                        Step("brightness_set", buildJsonObject { put("auto", true) })
                    } else {
                        val p = brightPct.trim().toIntOrNull()
                        if (p == null || p !in 0..100) {
                            err = if (zh) "亮度需在 0–100" else "Brightness 0–100"; null
                        } else Step("brightness_set", buildJsonObject { put("percent", p) })
                    }
                    StepKind.Flashlight -> Step("flashlight", buildJsonObject { put("on", boolOn) })
                    StepKind.Wifi -> Step("wifi_toggle", buildJsonObject { put("on", boolOn) })
                    StepKind.Bluetooth -> Step("bluetooth_toggle", buildJsonObject { put("on", boolOn) })
                    StepKind.MobileData -> Step("mobile_data_toggle", buildJsonObject { put("on", boolOn) })
                    StepKind.Media -> Step("media_control", buildJsonObject { put("action", mediaAct) })
                    StepKind.AppLaunch -> {
                        val pkg = launchPkg.trim()
                        if (pkg.isEmpty()) {
                            err = if (zh) "请填写应用包名" else "Package name required"; null
                        } else Step("app_launch", buildJsonObject { put("package", pkg) })
                    }
                }
                if (built != null) onConfirm(built)
            }, size = BtnSize.Sm)
        },
    ) {
        if (customStep != null) {
            Text(customStep.toString(), style = DshType.mono12, color = c.labelSecondary)
            Text(stringResource(R.string.rule_steps_custom_readonly), style = DshType.xxs12, color = c.labelTertiary)
            return@DDialog
        }
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    StepKind.SoundMode to (if (zh) "铃声模式" else "Ringer"),
                    StepKind.Volume to (if (zh) "音量" else "Volume"),
                    StepKind.Dnd to (if (zh) "勿扰" else "DND"),
                    StepKind.Brightness to (if (zh) "亮度" else "Brightness"),
                    StepKind.Flashlight to (if (zh) "手电筒" else "Flashlight"),
                    StepKind.Wifi to "Wi‑Fi",
                    StepKind.Bluetooth to (if (zh) "蓝牙" else "Bluetooth"),
                    StepKind.MobileData to (if (zh) "移动数据" else "Mobile data"),
                    StepKind.Media to (if (zh) "媒体控制" else "Media"),
                    StepKind.AppLaunch to (if (zh) "打开应用" else "Launch app"),
                ).forEach { (k, label) ->
                    FilterPill(label, kind == k) { kind = k; err = null }
                }
            }
            when (kind) {
                StepKind.SoundMode -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(if (zh) "静音" else "Silent", soundMode == "silent") { soundMode = "silent" }
                    FilterPill(if (zh) "震动" else "Vibrate", soundMode == "vibrate") { soundMode = "vibrate" }
                    FilterPill(if (zh) "响铃" else "Normal", soundMode == "normal") { soundMode = "normal" }
                }
                StepKind.Volume -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "music" to (if (zh) "媒体" else "Media"),
                            "ring" to (if (zh) "铃声" else "Ring"),
                            "alarm" to (if (zh) "闹钟" else "Alarm"),
                            "notification" to (if (zh) "通知" else "Notification"),
                            "call" to (if (zh) "通话" else "Call"),
                        ).forEach { (st, label) ->
                            FilterPill(label, stream == st) { stream = st }
                        }
                    }
                    DInput(volText, { volText = it }, placeholder = "0–100", keyboardType = KeyboardType.Number, minHeight = 36.dp)
                }
                StepKind.Brightness -> {
                    SettingsRow(
                        title = if (zh) "自动调节亮度" else "Adaptive brightness",
                        divider = false,
                        trailing = { DSwitch(brightAuto, { brightAuto = it }) },
                    )
                    if (!brightAuto) {
                        DInput(brightPct, { brightPct = it }, placeholder = "0–100", keyboardType = KeyboardType.Number, minHeight = 36.dp)
                    }
                }
                StepKind.Dnd, StepKind.Flashlight, StepKind.Wifi, StepKind.Bluetooth, StepKind.MobileData ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill(if (zh) "开启" else "On", boolOn) { boolOn = true }
                        FilterPill(if (zh) "关闭" else "Off", !boolOn) { boolOn = false }
                    }
                StepKind.Media -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "toggle" to (if (zh) "播放/暂停" else "Play/Pause"),
                        "play" to (if (zh) "播放" else "Play"),
                        "pause" to (if (zh) "暂停" else "Pause"),
                        "next" to (if (zh) "下一首" else "Next"),
                        "previous" to (if (zh) "上一首" else "Previous"),
                        "stop" to (if (zh) "停止" else "Stop"),
                    ).forEach { (a, label) ->
                        FilterPill(label, mediaAct == a) { mediaAct = a }
                    }
                }
                StepKind.AppLaunch ->
                    DInput(launchPkg, { launchPkg = it }, placeholder = if (zh) "应用包名，如 com.netease.cloudmusic" else "Package name", minHeight = 36.dp)
            }
            err?.let { Text(it, style = DshType.xs13, color = c.error) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayPills(selected: Set<Int>, zh: Boolean, onToggle: (Int) -> Unit) {
    val labels = if (zh) listOf("一", "二", "三", "四", "五", "六", "日") else listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { idx, label ->
            val day = idx + 1
            FilterPill(label, day in selected) { onToggle(day) }
        }
    }
}

@Composable
internal fun ToolPickerDialog(
    initial: Set<String>,
    allTools: List<String>,
    saving: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selected by remember(initial) { mutableStateOf(initial) }
    var names by remember(initial, allTools) { mutableStateOf((allTools + initial).toSortedSet()) }
    var query by remember { mutableStateOf("") }
    val searched = query.trim()
    val canAdd = searched.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.:-]*")) && searched !in names
    DDialog({ if (!saving) onDismiss() }, stringResource(R.string.automation_edit_tools), buttons = {
        DButton(stringResource(R.string.cancel), onDismiss, enabled = !saving, variant = BtnVariant.Outline)
        DButton(stringResource(R.string.save), { onConfirm(selected) }, enabled = !saving && selected != initial)
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
