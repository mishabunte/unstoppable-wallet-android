package io.horizontalsystems.bankwallet.modules.hardwarewallet.authentication

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.slideFromBottom
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.modules.hardwarewallet.TokenCheckResponse
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.AppBar
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.bankwallet.ui.compose.components.HsBackButton

@Preview
@Composable
private fun Preview_HardwareWalletAuthenticationSuccessScreen() {
    ComposeAppTheme {
        HardwareWalletAuthenticationSuccessScreen(
            deviceEdition = "Hito Founders Edition"
        ) {}
    }
}

@Composable
fun HardwareWalletAuthenticationSuccessScreen(
    navController: NavController? = null,
    deviceEdition: String?,
    onButtonClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.tyler),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = ComposeAppTheme.colors.tyler)
                .padding(start = 16.dp, end = 16.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        )
        {
            Text(
                text = "Your device is authentic!",
                style = ComposeAppTheme.typography.headline2,
                color = ComposeAppTheme.colors.leah,
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            Text(
                text = "Version: ${deviceEdition ?: "Unknown"}",
                style = ComposeAppTheme.typography.headline1,
                color = ComposeAppTheme.colors.leah,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            Row {
                Icon(
                    modifier = Modifier.size(112.dp),
                    painter = painterResource(R.drawable.icon_hardware_wallet_24),
                    contentDescription = null,
                    tint = ComposeAppTheme.colors.leah,
                )
                Icon(
                    modifier = Modifier.size(112.dp),
                    painter = painterResource(R.drawable.icon_check_1_24),
                    contentDescription = null,
                    tint = ComposeAppTheme.colors.green50,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 48.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ButtonPrimaryYellow(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                title = stringResource(R.string.Button_Done),
                onClick = {
                    onButtonClick()
                }
            )
        }
    }
}