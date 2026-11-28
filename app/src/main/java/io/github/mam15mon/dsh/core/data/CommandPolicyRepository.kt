package io.github.mam15mon.dsh.core.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class BuiltInCommandRule(val id: String) {
    SystemDirectory("rm-system-dir"), StorageRoot("rm-protected-root"), FindDelete("find-delete-protected"),
    DeviceRedirect("write-block-device"), DeviceCopy("dd-block-device"), DeviceFormat("mkfs-block-device"), DiskTool("disk-tool"),
    Power("power"), Wipe("wipe"), SettingDelete("settings-delete"), SystemPackage("pm-uninstall-system"),
    ForkBomb("fork-bomb"), EncodedPayload("encoded-payload"), RemotePayload("remote-payload"),
}

@Serializable
data class CommandRuleSpec(
    val name: String,
    val command: String? = null,
    val argumentPrefix: List<String> = emptyList(),
    val anyArguments: List<String> = emptyList(),
    val pathUnder: String? = null,
    val baseRule: String? = null,
) {
    fun validate() {
        require(name.isNotBlank()) { "name must not be blank" }
        require(command != null || baseRule != null) { "command or baseRule is required" }
        require(command == null || command.matches(COMMAND_NAME)) { "command must be a program name, such as git" }
        require(command != null || (argumentPrefix.isEmpty() && anyArguments.isEmpty() && pathUnder == null)) {
            "argumentPrefix, anyArguments and pathUnder require command"
        }
        require(pathUnder == null || pathUnder.startsWith('/')) { "pathUnder must be an absolute path" }
        require(baseRule == null || BuiltInCommandRule.entries.any { it.id == baseRule }) { "unknown baseRule: $baseRule" }
    }

    companion object {
        private val COMMAND_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9._+-]*")
    }
}

@Serializable
data class CustomCommandRule(val id: String, val spec: CommandRuleSpec, val enabled: Boolean = true)

@Serializable
data class CommandPolicy(
    val disabledBuiltIns: Set<String> = emptySet(),
    val customRules: List<CustomCommandRule> = emptyList(),
)

/** The app owns the saved rules and serves the current policy over the capability channel. */
@Singleton
class CommandPolicyRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = DataStoreFactory.create(
        serializer = PolicySerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { CommandPolicy() },
    ) { file(context) }
    val policy = store.data

    suspend fun setBuiltIn(rule: BuiltInCommandRule, enabled: Boolean) = store.updateData { current ->
        val disabled = if (enabled) current.disabledBuiltIns - rule.id else current.disabledBuiltIns + rule.id
        current.copy(disabledBuiltIns = disabled)
    }

    suspend fun save(id: String?, spec: CommandRuleSpec) {
        spec.validate()
        store.updateData { current ->
            val rules = if (id == null) current.customRules + CustomCommandRule("custom-${UUID.randomUUID()}", spec)
            else current.customRules.map { if (it.id == id) it.copy(spec = spec) else it }
            current.copy(customRules = rules)
        }
    }

    suspend fun setCustom(id: String, enabled: Boolean) = store.updateData { current ->
        current.copy(customRules = current.customRules.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    suspend fun delete(id: String) = store.updateData { current ->
        current.copy(customRules = current.customRules.filterNot { it.id == id })
    }

    private companion object {
        fun file(context: Context) = File(context.filesDir, "command-policy.json")
    }

    private object PolicySerializer : Serializer<CommandPolicy> {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        override val defaultValue = CommandPolicy()

        override suspend fun readFrom(input: InputStream): CommandPolicy = try {
            json.decodeFromString(input.readBytes().decodeToString())
        } catch (error: SerializationException) {
            throw CorruptionException("Invalid command policy", error)
        }

        override suspend fun writeTo(t: CommandPolicy, output: OutputStream) {
            output.write(json.encodeToString(CommandPolicy.serializer(), t).encodeToByteArray())
        }
    }
}
