package io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleOperation
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletSelectDeviceInput
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.AppBar
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryDefault
import io.horizontalsystems.bankwallet.ui.compose.components.HsBackButton

class HardwareWalletFirmwareUpgradeInitialFragment : BaseComposeFragment() {
    @Composable
    override fun GetContent(navController: NavController) {
        ComposeAppTheme {
            HardwareWalletFirmwareUpgradeInitialScreen(navController)
        }
    }
}

@Preview
@Composable
private fun Preview_HardwareWalletFirmwareUpgradeScreen() {
    ComposeAppTheme {
        HardwareWalletFirmwareUpgradeInitialScreen()
    }
}

fun LazyListScope.firmwareUpgradeCheckList(
    checkList: List<String>
) {

    itemsIndexed(
        items = checkList
    ) { index, item ->
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Text(
                text = "${index + 1}.", // numeration
                modifier = Modifier.padding(end = 8.dp),
                style = ComposeAppTheme.typography.body,
                color = ComposeAppTheme.colors.leah
            )
            Text(
                text = item,
                style = ComposeAppTheme.typography.body,
                color = ComposeAppTheme.colors.leah
            )
        }
    }
}

@Composable
private fun HardwareWalletFirmwareUpgradeInitialScreen(navController: NavController? = null) {
    Column(modifier = Modifier
        .background(color = ComposeAppTheme.colors.tyler).fillMaxSize()) {
        AppBar(
            title = stringResource(R.string.HardwareWalletFirmwareUpgrade_Title),
            navigationIcon = {
                HsBackButton(onClick = { navController?.popBackStack() })
            },
        )
        Box(modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter) {
            Column(Modifier
                .padding(horizontal=32.dp, vertical=16.dp)
                .align(Alignment.TopCenter)
            ) {
                Text(
                    text = "Prepare your Hito Wallet for the firmware upgrade",
                    style = ComposeAppTheme.typography.title3,
                    color = ComposeAppTheme.colors.leah,
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center,
                )
                LazyColumn(Modifier.fillMaxSize().padding(vertical = 32.dp)) {
                    firmwareUpgradeCheckList(
                        listOf(
                            "Turn on Bluetooth on your phone and allow the app to use it",
                            "Turn off Hito Wallet and wait 3 seconds",
                            "Press power button and hold it until Blue LED turns on (approx. 15 seconds)",
                            "Release power button and press it again",
                            "Blue LED will start blinking",
                            "Press the \"Start\" button on your phone to scan for nearby Hito devices",
                            "Find your device from the list and connect to it",
                            "The device is now ready to flash!"
                        )
                    )
                }
            }

            Column(Modifier.align(Alignment.BottomCenter)) {
                ButtonPrimaryDefault(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 48.dp, start = 32.dp, end = 32.dp),
                    title = "Start",
                    onClick = {
                        navController?.slideFromRight(
                            R.id.hardwareWalletSelectDeviceFragment,
                            HardwareWalletSelectDeviceInput(HardwareWalletBleOperation.FirmwareUpgrade),
                        )
                    }
                )
            }
        }
    }
}
