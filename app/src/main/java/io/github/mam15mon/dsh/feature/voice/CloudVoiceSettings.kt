package io.github.mam15mon.dsh.feature.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.mam15mon.dsh.R
import io.github.mam15mon.dsh.core.data.SettingsRepository
import io.github.mam15mon.dsh.core.designsystem.DButton
import io.github.mam15mon.dsh.core.designsystem.DIcon
import io.github.mam15mon.dsh.core.designsystem.DInput
import io.github.mam15mon.dsh.core.designsystem.Dsh
import io.github.mam15mon.dsh.core.designsystem.DshTheme
import io.github.mam15mon.dsh.core.designsystem.DshType
import io.github.mam15mon.dsh.core.designsystem.Segmented
import io.github.mam15mon.dsh.core.designsystem.SettingsCard
import io.github.mam15mon.dsh.core.designsystem.SettingsRow
import io.github.mam15mon.dsh.core.platform.AppLanguage
import io.github.mam15mon.dsh.feature.settings.SettingsPage
import javax.inject.Inject

@AndroidEntryPoint
class CloudVoiceSettingsActivity : ComponentActivity() {
    @Inject lateinit var settingsRepo: SettingsRepository

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle(null)
            val theme = settings?.theme ?: return@setContent
            DshTheme(theme) {
                val savedKey = remember { CloudVoiceConfigStore.read(applicationContext) }
                val savedMode = remember { CloudVoiceConfigStore.readMode(applicationContext) }
                SettingsPage(stringResource(R.string.cloud_voice_title), ::finish) {
                    CloudVoiceEditor(savedMode, savedKey, ::finish)
                }
            }
        }
    }
}

internal fun openCloudVoiceSettings(context: Context) {
    context.startActivity(Intent(context, CloudVoiceSettingsActivity::class.java))
}

@Composable
internal fun CloudVoiceSettings() {
    val context = LocalContext.current
    SettingsRow(stringResource(R.string.cloud_voice_title), stringResource(R.string.cloud_voice_choose_mode),
        onClick = { openCloudVoiceSettings(context) }, trailing = { VoiceChevron() })
}

@Composable
private fun VoiceChevron() = DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = Dsh.colors.labelTertiary)

@Composable
private fun CloudVoiceEditor(savedMode: VoiceRecognitionMode, savedKey: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val browser = LocalUriHandler.current
    val colors = Dsh.colors
    var mode by remember { mutableStateOf(savedMode) }
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val retainedKey = savedKey.orEmpty()
    Spacer(Modifier.height(8.dp))
    SettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Segmented(
                listOf(stringResource(R.string.cloud_voice_doubao), stringResource(R.string.cloud_voice_system)),
                mode.ordinal,
                { mode = VoiceRecognitionMode.entries[it]; error = null },
            )
            if (mode == VoiceRecognitionMode.Doubao) {
                Text(stringResource(R.string.cloud_voice_doubao_hint), style = DshType.xxs12, color = colors.labelTertiary)
                Text(stringResource(R.string.api_key_label), style = DshType.xs13m, color = colors.labelSecondary)
                DInput(apiKey, { apiKey = it; error = null }, placeholder = stringResource(
                    if (retainedKey.isNotEmpty()) R.string.cloud_voice_keep_key else R.string.cloud_voice_key),
                    secret = true, keyboardType = KeyboardType.Password)
                Text(stringResource(R.string.prov_get_key), Modifier.clickable {
                    runCatching { browser.openUri("https://console.volcengine.com/speech/new/setting/apikeys") }
                        .onFailure { error = R.string.cloud_voice_link_failed }
                }.padding(vertical = 4.dp), style = DshType.xs13, color = colors.link)
                Text(stringResource(R.string.cloud_voice_doubao_consent), style = DshType.xxs12, color = colors.labelTertiary)
            } else {
                Text(stringResource(R.string.cloud_voice_system_hint), style = DshType.xxs12, color = colors.labelTertiary)
            }
            error?.let { Text(stringResource(it), style = DshType.xs13, color = colors.warnLabel) }
            DButton(stringResource(R.string.cloud_voice_save), {
                if (mode == VoiceRecognitionMode.Doubao) {
                    val key = CloudVoiceConfigStore.validateKey(apiKey.ifBlank { retainedKey })
                    if (key == null) { error = R.string.cloud_voice_key; return@DButton }
                    if (key != savedKey && runCatching { CloudVoiceConfigStore.write(context, key) }.isFailure) {
                        error = R.string.cloud_voice_save_failed
                        return@DButton
                    }
                }
                CloudVoiceConfigStore.writeMode(context, mode)
                onDone()
            })
        }
    }
}
