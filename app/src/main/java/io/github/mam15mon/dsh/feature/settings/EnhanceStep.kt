package io.github.mam15mon.dsh.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mam15mon.dsh.R
import io.github.mam15mon.dsh.core.designsystem.BtnSize
import io.github.mam15mon.dsh.core.designsystem.BtnVariant
import io.github.mam15mon.dsh.core.designsystem.DButton
import io.github.mam15mon.dsh.core.designsystem.Dsh
import io.github.mam15mon.dsh.core.designsystem.DshType

/**
 * Optional onboarding step after the credential: 增强能力 with the same Shizuku card as Settings.
 * Never blocking — 跳过 goes home, and everything here is also offered just in time later.
 */
@Composable
fun EnhanceStep(onDone: () -> Unit) {
    val c = Dsh.colors
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Text("选择手机操作方式", style = DshType.xl24, color = c.labelPrimary)
        Spacer(Modifier.height(10.dp))
        Text("有 Root 可直接授权；无障碍与 Shizuku 按需开启，都不是聊天的前提。", style = DshType.s14, color = c.labelTertiary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        io.github.mam15mon.dsh.nativeextra.PhoneAccessCard()
        Spacer(Modifier.height(20.dp))
        DButton("进入 DSH", onDone, Modifier.fillMaxWidth(),
            variant = BtnVariant.Primary, size = BtnSize.Lg)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.shz_onb_later), style = DshType.xxs12, color = c.labelCaption, textAlign = TextAlign.Center)
    }
}
