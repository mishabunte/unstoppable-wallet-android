package io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryDefault

@Preview
@Composable
private fun Preview_PermissionDeniedScreen() {
    ComposeAppTheme {
        Column(modifier = Modifier.background(ComposeAppTheme.colors.tyler)) {
            PermissionDeniedScreen(
                "Open Settings and grant Bluetooth and Location permissions to scan for hardware wallet devices.",
                "Open Settings",
                isLocationRequired = true) {}
        }
    }
}

@Composable
fun PermissionDeniedScreen(
    message: String,
    buttonText: String,
    isLocationRequired: Boolean,
    onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                tint = ComposeAppTheme.colors.laguna,
                modifier = Modifier
                    .height(48.dp)
                    .width(48.dp)
                    .align(Alignment.CenterVertically)
            )
            if (isLocationRequired) {
                Spacer(Modifier.width(48.dp))
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = ComposeAppTheme.colors.laguna,
                    modifier = Modifier
                        .height(48.dp)
                        .width(48.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text=message,
            color = ComposeAppTheme.colors.leah,
            style = ComposeAppTheme.typography.headline1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(32.dp))
        ButtonPrimaryDefault(
            title = buttonText,
            modifier = Modifier.fillMaxWidth(),
            onClick = onRequestPermission
        )
    }
}