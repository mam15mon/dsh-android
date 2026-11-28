package io.github.mam15mon.dsh.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mam15mon.dsh.R
import io.github.mam15mon.dsh.core.data.BuiltInCommandRule
import io.github.mam15mon.dsh.core.data.BuiltInCommandRule.*
import io.github.mam15mon.dsh.core.data.CommandPolicy
import io.github.mam15mon.dsh.core.data.CommandPolicyRepository
import io.github.mam15mon.dsh.core.data.CommandRuleSpec
import io.github.mam15mon.dsh.core.data.CustomCommandRule
import io.github.mam15mon.dsh.core.designsystem.BtnVariant
import io.github.mam15mon.dsh.core.designsystem.DButton
import io.github.mam15mon.dsh.core.designsystem.DDialog
import io.github.mam15mon.dsh.core.designsystem.DInput
import io.github.mam15mon.dsh.core.designsystem.DSwitch
import io.github.mam15mon.dsh.core.designsystem.Dsh
import io.github.mam15mon.dsh.core.designsystem.DshType
import io.github.mam15mon.dsh.core.designsystem.SettingsCard
import io.github.mam15mon.dsh.core.designsystem.SettingsGroupTitle
import io.github.mam15mon.dsh.core.designsystem.SettingsRow
import io.github.mam15mon.dsh.core.engine.EngineManager
import io.github.mam15mon.dsh.core.engine.RpcJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

private val draftJson = Json { prettyPrint = true }
private fun decodeDraft(text: String) = draftJson.decodeFromString<CommandRuleSpec>(text).also { it.validate() }

@HiltViewModel
class CommandRulesViewModel @Inject constructor(
    private val repo: CommandPolicyRepository,
    private val engine: EngineManager,
) : ViewModel() {
    val error = MutableStateFlow<String?>(null)
    val policy = repo.policy.catch { error.value = it.message ?: it.javaClass.simpleName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setBuiltIn(rule: BuiltInCommandRule, enabled: Boolean) = update { repo.setBuiltIn(rule, enabled) }
    fun setCustom(id: String, enabled: Boolean) = update { repo.setCustom(id, enabled) }
    suspend fun save(id: String?, spec: CommandRuleSpec) = repo.save(id, spec)
    suspend fun delete(id: String) { repo.delete(id) }

    private fun update(change: suspend () -> Unit) = viewModelScope.launch {
        error.value = null
        try { change() } catch (e: Exception) {
            if (e is CancellationException) throw e
            error.value = e.message ?: e.javaClass.simpleName
        }
    }

    suspend fun preview(command: String, id: String?, draft: CommandRuleSpec?): Pair<String, String>? {
        val result = try {
            engine.control().request("android/command-policy/check", buildJsonObject {
                put("command", command)
                if (draft != null) put("draft", RpcJson.encodeToJsonElement(CustomCommandRule(id ?: "custom-draft", draft)))
            }).jsonObject.getValue("verdict")
        } finally {
            engine.scheduleIdleShutdown()
        }
        if (result is JsonNull) return null
        return result.jsonObject.let { it.getValue("rule").jsonPrimitive.content to it.getValue("summary").jsonPrimitive.content }
    }
}

private val builtInGroups = listOf(
    R.string.command_rules_delete_group to mapOf(
        SystemDirectory to R.string.command_rule_system_directory,
        StorageRoot to R.string.command_rule_storage_root,
        FindDelete to R.string.command_rule_find_delete,
    ),
    R.string.command_rules_disk_group to mapOf(
        DeviceRedirect to R.string.command_rule_device_redirect,
        DeviceCopy to R.string.command_rule_device_copy,
        DeviceFormat to R.string.command_rule_device_format,
        DiskTool to R.string.command_rule_disk_tool,
    ),
    R.string.command_rules_system_group to mapOf(
        Power to R.string.command_rule_power,
        Wipe to R.string.command_rule_wipe,
        SettingDelete to R.string.command_rule_setting_delete,
        SystemPackage to R.string.command_rule_system_package,
    ),
    R.string.command_rules_script_group to mapOf(
        ForkBomb to R.string.command_rule_fork_bomb,
        EncodedPayload to R.string.command_rule_encoded_payload,
        RemotePayload to R.string.command_rule_remote_payload,
    ),
)

@Composable
fun CommandRulesSection(vm: CommandRulesViewModel, openEditor: (id: String?, base: String?) -> Unit) {
    val current by vm.policy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    error?.let { RuleMessage(stringResource(R.string.command_rules_error, it), isError = true) }
    val policy = current ?: return

    SettingsGroupTitle(stringResource(R.string.command_rules_custom))
    SettingsCard {
        SettingsRow(stringResource(R.string.command_rules_add), divider = false, onClick = { openEditor(null, null) })
        policy.customRules.forEach { rule ->
            SettingsRow(rule.spec.name, rule.spec.command ?: rule.spec.baseRule, onClick = { openEditor(rule.id, null) },
                trailing = { DSwitch(rule.enabled, { vm.setCustom(rule.id, it) }) })
        }
    }
    RulePreview(vm, policy)
    RuleMessage(stringResource(R.string.command_rules_built_in_hint))
    for ((group, rules) in builtInGroups) {
        SettingsGroupTitle(stringResource(group))
        SettingsCard {
            rules.entries.forEachIndexed { index, (rule, label) ->
                SettingsRow(stringResource(label), rule.id, divider = index > 0, onClick = { openEditor(null, rule.id) },
                    trailing = { DSwitch(rule.id !in policy.disabledBuiltIns, { vm.setBuiltIn(rule, it) }) })
            }
        }
    }
}

@Composable
fun CommandRuleEditor(id: String?, baseRule: String?, onBack: () -> Unit, vm: CommandRulesViewModel) {
    val policy by vm.policy.collectAsStateWithLifecycle()
    SettingsPage(stringResource(if (id == null) R.string.command_rules_add else R.string.command_rules_edit), onBack) {
        val current = policy ?: return@SettingsPage
        val rule = current.customRules.find { it.id == id }
        if (id != null && rule == null) return@SettingsPage
        val baseLabel = builtInGroups.firstNotNullOfOrNull { (_, group) -> group.entries.find { it.key.id == baseRule }?.value }
        val copyName = if (baseLabel != null) stringResource(R.string.command_rules_copy_name, stringResource(baseLabel)) else null
        val starter = rule?.spec ?: if (copyName != null) CommandRuleSpec(name = copyName, baseRule = baseRule)
            else CommandRuleSpec(name = stringResource(R.string.command_rules_example), command = "git", argumentPrefix = listOf("push"), anyArguments = listOf("--force", "-f"))
        var text by rememberSaveable(id, baseRule) { mutableStateOf(draftJson.encodeToString(CommandRuleSpec.serializer(), starter)) }
        var message by rememberSaveable { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var confirmDelete by rememberSaveable { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val submit: (suspend () -> Unit) -> Unit = { operation ->
            busy = true
            scope.launch {
                try { operation(); onBack() } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    message = e.message ?: e.javaClass.simpleName
                } finally { busy = false }
            }
        }

        RuleMessage(stringResource(R.string.command_rules_json_help))
        DInput(text, { text = it; message = null }, singleLine = false, minHeight = 144.dp)
        message?.let { RuleMessage(stringResource(R.string.command_rules_error, it), isError = true) }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DButton(stringResource(R.string.save), { submit { vm.save(id, decodeDraft(text)) } }, enabled = !busy)
            if (id != null) DButton(stringResource(R.string.delete), { confirmDelete = true }, enabled = !busy, variant = BtnVariant.Outline)
        }
        if (confirmDelete && id != null) DDialog({ confirmDelete = false }, title = stringResource(R.string.delete), buttons = {
            DButton(stringResource(R.string.cancel), { confirmDelete = false }, variant = BtnVariant.Outline)
            DButton(stringResource(R.string.delete), { confirmDelete = false; submit { vm.delete(id) } })
        }) { Text(rule?.spec?.name.orEmpty()) }
        RulePreview(vm, current, id, draftText = text)
    }
}

@Composable
private fun RulePreview(vm: CommandRulesViewModel, policy: CommandPolicy, id: String? = null, draftText: String? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var command by rememberSaveable { mutableStateOf("") }
    var result by remember(command, draftText, policy) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember(command, draftText, policy) { mutableStateOf(false) }
    SettingsGroupTitle(stringResource(R.string.command_rules_preview))
    RuleMessage(stringResource(R.string.command_rules_preview_hint))
    DInput(command, { command = it }, singleLine = false, placeholder = "git push --force")
    Spacer(Modifier.height(8.dp))
    DButton(stringResource(R.string.command_rules_check), {
        val sample = command
        busy = true
        scope.launch {
            try {
                failed = false
                val match = vm.preview(sample, id, draftText?.let(::decodeDraft))
                result = if (match == null) context.getString(R.string.command_rules_no_match)
                    else context.getString(R.string.command_rules_match, match.first, match.second)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed = true
                result = context.getString(R.string.command_rules_error, e.message ?: e.javaClass.simpleName)
            } finally { busy = false }
        }
    }, enabled = !busy && command.isNotBlank(), variant = BtnVariant.Outline)
    result?.let { RuleMessage(it, isError = failed) }
}

@Composable
private fun RuleMessage(text: String, isError: Boolean = false) {
    Text(text, Modifier.padding(vertical = 8.dp, horizontal = 4.dp), style = DshType.xs13,
        color = if (isError) Dsh.colors.error else Dsh.colors.labelSecondary)
}
