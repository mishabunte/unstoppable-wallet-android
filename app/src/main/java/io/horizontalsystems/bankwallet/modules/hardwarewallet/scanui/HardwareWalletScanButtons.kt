package io.horizontalsystems.bankwallet.modules.hardwarewallet.scanui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryTransparent
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellowWithIcon

@Preview(showBackground = true)
@Composable
private fun Preview_ScanButtons() {
    ComposeAppTheme {
        HardwareWalletScanButtons(
//            modifier = Modifier
//                .padding(horizontal = 16.dp)
//                .padding(bottom = 16.dp)
        )
    }
}

@Composable
fun HardwareWalletScanButtons (
    onContinueClick: () -> Unit = {},
    onTryAgainClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Try again if you detected an error",
            style = ComposeAppTheme.typography.body,
            color = ComposeAppTheme.colors.grey,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            ) {
                ButtonPrimaryYellowWithIcon(
                    modifier = Modifier.weight(0.5f),
                    title = stringResource(R.string.Button_Continue),
                    icon = R.drawable.ic_qr_scan_20,
                    onClick = {
                        onContinueClick()
                    }
                )
                ButtonPrimaryTransparent(
                    modifier = Modifier.weight(0.5f),
                    title = stringResource(R.string.Button_TryAgain),
                    onClick = {
                        onTryAgainClick()
                    }
                )
            }
        }
    }
}