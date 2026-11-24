package io.github.mam15mon.dsh.feature.settings

import android.content.Context
import io.github.mam15mon.dsh.R
import io.github.mam15mon.dsh.core.provider.VerifyFailure
import io.github.mam15mon.dsh.core.provider.VerifyOutcome

/** One failed connection test in plain words; the server's own message is kept where it explains more. */
fun describeFailure(context: Context, f: VerifyFailure): String = when (f) {
    is VerifyFailure.KeyInvalid -> context.getString(R.string.verify_key, f.message)
    is VerifyFailure.ModelNotAvailable -> context.getString(R.string.verify_unavailable, f.message.trimEnd('.', '。', ' '))
    is VerifyFailure.ModelNotFound -> context.getString(R.string.verify_not_found, f.message)
    is VerifyFailure.ToolsUnsupported -> context.getString(R.string.verify_no_tools, f.message)
    is VerifyFailure.NoToolCall -> if (f.reply.isBlank()) context.getString(R.string.verify_no_tool_call) else context.getString(R.string.verify_no_tool_call_reply, f.reply)
    is VerifyFailure.Quota -> context.getString(R.string.verify_quota, f.message)
    // Same wording as a failed chat turn and 获取模型列表: cause, hint, raw detail.
    is VerifyFailure.Network -> context.getString(R.string.verify_network, context.getString(f.diagnosis.cause.message)) +
        (f.diagnosis.cause.hint?.let { " " + context.getString(it) } ?: "") + " (" + f.diagnosis.detail.take(160) + ")"
    is VerifyFailure.Server -> if (f.status in 100..599) context.getString(R.string.verify_server, f.status, f.message) else context.getString(R.string.verify_bad_response, f.message)
}

/** Every requested model names its own result; skipped requests cannot look like successes. */
fun describeOutcome(context: Context, outcome: VerifyOutcome, becameMain: Boolean): String {
    val lines = outcome.results.map { (model, failure) ->
        if (failure == null) context.getString(R.string.prov_test_ok, model)
        else context.getString(R.string.prov_test_model_failed, model, describeFailure(context, failure))
    }.toMutableList()
    outcome.untested.forEach { lines += context.getString(R.string.prov_test_model_skipped, it) }
    if (becameMain) outcome.passed.firstOrNull()?.let { lines += context.getString(R.string.prov_test_default, it) }
    return lines.joinToString("\n")
}
