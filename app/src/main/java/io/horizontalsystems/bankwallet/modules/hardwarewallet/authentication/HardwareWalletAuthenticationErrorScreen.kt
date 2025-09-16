package io.horizontalsystems.bankwallet.modules.hardwarewallet.authentication

import androidx.compose.foundation.background
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
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow

@Preview
@Composable
private fun Preview_HardwareWalletAuthenticationErrorScreen() {
    ComposeAppTheme {
        HardwareWalletAuthenticationErrorScreen() {}
    }
}

@Composable
fun HardwareWalletAuthenticationErrorScreen(
    navController: NavController? = null,
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
                .background(color = ComposeAppTheme.colors.tyler)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                modifier = Modifier.size(112.dp),
                painter = painterResource(R.drawable.icon_24_warning_2),
                contentDescription = null,
                tint = ComposeAppTheme.colors.lucian,
            )
            Spacer(Modifier.height(32.dp))
            Text(
                text = "Unknown error occurred during authentication",
                style = ComposeAppTheme.typography.headline1,
                color = ComposeAppTheme.colors.leah,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            Text(
                text = "Try again later or contact support",
                style = ComposeAppTheme.typography.headline2,
                color = ComposeAppTheme.colors.leah,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 32.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ButtonPrimaryYellow(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                title = stringResource(R.string.Button_TryAgain),
                onClick = onButtonClick
            )
        }
    }
}