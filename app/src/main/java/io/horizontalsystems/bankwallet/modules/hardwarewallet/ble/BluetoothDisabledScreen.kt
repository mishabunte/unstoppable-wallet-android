package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryDefault

@Preview
@Composable
private fun Preview_BluetoothDisabledScreen() {
    ComposeAppTheme {
        Column(modifier = Modifier.background(ComposeAppTheme.colors.tyler)) {
            BluetoothDisabledScreen {}
        }
    }
}

@Composable
fun BluetoothDisabledScreen(onEnableClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Bluetooth,
            contentDescription = null,
            tint = ComposeAppTheme.colors.laguna,
            modifier = Modifier
                .padding(bottom = 12.dp)
                .fillMaxWidth()
                .height(48.dp)
        )
        Text(
            text = stringResource(io.horizontalsystems.bankwallet.R.string.HardwareWalletBle_BluetoothDisabled),
            color = ComposeAppTheme.colors.leah,
            style = ComposeAppTheme.typography.headline1,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(io.horizontalsystems.bankwallet.R.string.HardwareWalletBle_BluetoothRequired),
            color = ComposeAppTheme.colors.leah,
            style = ComposeAppTheme.typography.headline2,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        ButtonPrimaryDefault(
            title = stringResource(io.horizontalsystems.bankwallet.R.string.HardwareWalletBle_EnableBluetooth),
            onClick = onEnableClick,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
        )
    }
}
