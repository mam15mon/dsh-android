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
class RootTools @Inject constructor(@ApplicationContext private val context: Context, private val shell: io.github.mam15mon.dsh.nativeextra.PhoneShell) : ToolSet {
    private suspend fun command(cmd: String, timeout: Long = 30_000): ToolResult {
        check(NativeRoot.enabled(context)) { "Root 操作未开启" }
        val result = shell.exec(cmd, timeout)
        return if (result.code == 0) ToolResult.text("Root exit 0\n${result.output.ifBlank { "(no output)" }}")
        else ToolResult.error("root_command_failed", "Root exit ${result.code}\n${result.output}")
    }
    override val tools = listOf(
        object : Tool("phone_sh", ToolGroup.System, Risk.dangerous, false,
            "Run a command on this phone as root through su. Requires the user's Root switch. No app allowlist. Use for Android processes, dumpsys and privileged operations; regular bash runs as the app user. Output is bounded. Do not run interactive commands.", {
                string("cmd", "Command; omit to check root identity")
                int("timeout_s", "1-120 seconds, default 30")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                require(call.viaEngine) { "Root commands must be called through an agent conversation" }
                return command(args.str("cmd") ?: "id", (args.int("timeout_s") ?: 30).coerceIn(1, 120) * 1000L)
            }
        },
        object : Tool("phone_workspace", ToolGroup.Files, Risk.read, false,
            "Show the selected original phone project path. A Root-authorized engine runs as uid 0 directly in this directory, without a FUSE alias or copy. Ordinary App engines cannot use Root-only projects. Changes affect original files. Use this to explain the real project path, not the old phone-workspace alias.", {}) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = ToolResult.text(
                "Root enabled: ${NativeRoot.enabled(context)}\nSelected source: ${NativeRoot.selected(context).ifEmpty { "none" }}\nEngine identity is verified at startup; Root engines use the selected source directly.\nManage the project in Phone permissions → Root 与项目. Root identity switches automatically when idle. Without Root, the saved project is paused and the App engine uses its own home; do not claim it is accessing the Root-only project. The manually selected project is persistent across app and engine restarts and is applied to resumed conversations before the next prompt. Without Root its access pauses; restoring Root resumes the same saved project."
            )
        },
    )
}
