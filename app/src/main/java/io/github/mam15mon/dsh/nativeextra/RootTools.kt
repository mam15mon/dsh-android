package io.github.mam15mon.dsh.nativeextra

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mam15mon.dsh.core.capability.*
import io.github.mam15mon.dsh.core.model.Risk
import io.github.mam15mon.dsh.core.model.ToolGroup
import io.github.mam15mon.dsh.service.FloatingBallService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RootTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: io.github.mam15mon.dsh.nativeextra.PhoneShell,
    private val sessions: io.github.mam15mon.dsh.core.data.SessionDao,
) : ToolSet {
    private suspend fun command(cmd: String, timeout: Long = 30_000): ToolResult {
        check(NativeRoot.enabled(context)) { "Root 操作未开启" }
        val result = shell.exec(cmd, timeout)
        return if (result.code == 0) ToolResult.text("Root exit 0\n${result.output.ifBlank { "(no output)" }}")
        else ToolResult.error("root_command_failed", "Root exit ${result.code}\n${result.output}")
    }
    override val tools = listOf(
        object : Tool("phone_sh", ToolGroup.System, Risk.dangerous, false,
            "Run a command on this phone as root through su. Requires the user's Root switch. Subject to DSH's enabled command rules. Use for Android processes, dumpsys and privileged operations. Output is bounded. Do not run interactive commands.", {
                string("cmd", "Command; omit to check root identity")
                int("timeout_s", "1-120 seconds, default 30")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                require(call.viaEngine) { "Root commands must be called through an agent conversation" }
                return command(args.str("cmd") ?: "id", (args.int("timeout_s") ?: 30).coerceIn(1, 120) * 1000L)
            }
        },
        object : Tool("phone_workspace", ToolGroup.Files, Risk.read, false,
            "Show the active working directory for this conversation and the default Root project. A Root-authorized engine runs as uid 0 directly in that directory, without a FUSE alias or copy. Ordinary App engines cannot use Root-only projects. Changes affect original files.", {}) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val enabled = NativeRoot.enabled(context)
                val defaultProject = NativeRoot.selected(context)
                val appHome = File(context.filesDir, "home").path
                val sessionWorkspace = call.sessionId.substringBefore('/').takeIf { it.isNotEmpty() }?.let { sessions.get(it)?.workspace }
                val active = io.github.mam15mon.dsh.core.session.SessionWorkspaces.resolve(sessionWorkspace, defaultProject, enabled, appHome)
                return ToolResult.text(
                    "Root enabled: $enabled\nActive session workspace: $active\nDefault project: ${defaultProject.ifEmpty { "none" }}\nManage projects in Phone permissions → Root 与项目 or the composer '+' menu."
                )
            }
        },
    )
}
