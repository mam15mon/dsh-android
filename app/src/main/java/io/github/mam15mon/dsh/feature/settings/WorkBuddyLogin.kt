package io.github.mam15mon.dsh.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mam15mon.dsh.R
import io.github.mam15mon.dsh.core.designsystem.BtnSize
import io.github.mam15mon.dsh.core.designsystem.BtnVariant
import io.github.mam15mon.dsh.core.designsystem.DButton
import io.github.mam15mon.dsh.core.designsystem.Dsh
import io.github.mam15mon.dsh.core.designsystem.DshType
import io.github.mam15mon.dsh.core.designsystem.Spinner
import io.github.mam15mon.dsh.core.provider.WbAccount
import io.github.mam15mon.dsh.core.provider.WbState
import io.github.mam15mon.dsh.core.provider.wbCompact
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The WorkBuddy preset's 登录 block (ProvidersUi.kt): the sign-in the engine keeps (plugin-workbuddy), as an
 * alternative to an API key. 浏览器登录 opens Tencent's page in the browser; while it waits the engine is asked
 * every 1.5 s until the attempt settles. [onState] reports each state read, for the editor's input check.
 */
@Composable
internal fun WorkBuddyLogin(vm: SettingsViewModel, site: String, onState: (WbState) -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<WbState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        runCatching { vm.workbuddy.state(site) }.onSuccess { st = it; onState(it) }.onFailure { error = it.message }
    }
    LaunchedEffect(site) { refresh() }
    // While the browser sign-in waits, read the engine until it is done, failed or cancelled; then let the engine idle again.
    LaunchedEffect(st?.attempt?.waiting) {
        if (st?.attempt?.waiting != true) return@LaunchedEffect
        while (st?.attempt?.waiting == true) { delay(1500); refresh() }
        vm.workbuddy.settled()
    }

    fun signIn() {
        scope.launch {
            busy = true; error = null
            runCatching { vm.workbuddy.startSignIn(site) }
                .onSuccess { a ->
                    st = (st ?: WbState()).copy(attempt = a)
                    a.url?.let { u -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { error = it.message } }
                }
                .onFailure { error = it.message }
            busy = false
        }
    }

    val now = st
    val attempt = now?.attempt
    Text(stringResource(R.string.prov_wb_login), style = DshType.xs13m, color = c.labelSecondary)
    when {
        now == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Spinner(12.dp) }
        attempt?.waiting == true -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spinner(12.dp); Text(stringResource(R.string.prov_wb_waiting), style = DshType.xxs12, color = c.labelTertiary)
        }
        now.accounts.isEmpty() -> Text(stringResource(R.string.prov_wb_signed_out), style = DshType.xxs12, color = c.labelTertiary)
        else -> {}
    }
    // Each signed-in account with its credits; the one the last chat used is marked. Chats go to the account with the most
    // credits left and move on to the next when one is rate-limited, out of credits or restricted.
    now?.accounts?.forEach { a ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(a.name + if (a.current) "  ✓" else "", style = DshType.xs13, color = c.labelPrimary)
                Text(accountLine(context, a), style = DshType.xxs12, color = if (a.expired || a.restingWhy == "restricted") c.error else c.labelTertiary)
            }
            DButton(stringResource(R.string.prov_wb_sign_out), {
                scope.launch { runCatching { vm.workbuddy.signOut(site, a.id) }.onSuccess { st = it; onState(it) } }
            }, variant = BtnVariant.Outline, size = BtnSize.Sm)
        }
    }
    if (attempt?.phase == "failed") Text(stringResource(R.string.prov_wb_failed, attempt.error.orEmpty()), style = DshType.xxs12, color = c.error)
    error?.let { Text(stringResource(R.string.prov_wb_failed, it), style = DshType.xxs12, color = c.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (attempt?.waiting == true) {
            DButton(stringResource(R.string.prov_wb_cancel), { scope.launch { runCatching { vm.workbuddy.cancel(site) }.onSuccess { st = it; onState(it) } } }, variant = BtnVariant.Outline, size = BtnSize.Sm)
        } else {
            DButton(stringResource(if (now?.accounts?.isNotEmpty() == true) R.string.prov_wb_add_account else R.string.prov_wb_sign_in), { signIn() },
                variant = BtnVariant.Outline, size = BtnSize.Sm, enabled = !busy && now != null)
            if (now?.accounts?.isNotEmpty() == true) DButton(stringResource(R.string.prov_wb_refresh_credits), {
                scope.launch {
                    busy = true
                    runCatching { vm.workbuddy.usage(site) }.onSuccess { st = it; onState(it) }.onFailure { error = it.message }
                    busy = false
                }
            }, variant = BtnVariant.Outline, size = BtnSize.Sm, enabled = !busy)
        }
    }
    Text(stringResource(R.string.prov_wb_hint), style = DshType.xxs12, color = c.labelTertiary)
    Text(stringResource(R.string.prov_wb_reasoning_hint), style = DshType.xxs12, color = c.labelTertiary)
}

/** One account's second line: its sign-in or rest state, else its plan and credits. */
private fun accountLine(context: android.content.Context, a: WbAccount): String = when {
    a.expired -> context.getString(R.string.prov_wb_expired)
    a.restingWhy == "rate" -> context.getString(R.string.prov_wb_resting_rate)
    a.restingWhy == "credits" -> context.getString(R.string.prov_wb_resting_credits)
    a.restingWhy == "restricted" -> context.getString(R.string.prov_wb_resting_restricted)
    a.left != null && a.total != null -> context.getString(R.string.prov_wb_credits_of, wbCompact(a.left), wbCompact(a.total), a.plan.orEmpty())
    a.left != null -> context.getString(R.string.prov_wb_credits_left, wbCompact(a.left), a.plan.orEmpty())
    a.creditsError != null -> context.getString(R.string.prov_wb_credits_unknown)
    else -> context.getString(R.string.prov_wb_credits_unread)
}
