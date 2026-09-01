package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import io.horizontalsystems.bankwallet.R
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.RowUniversal

fun LazyListScope.discoveredDeviceList(
    onItemClick: (HitoDevice) -> Unit,
    deviceList: List<HitoDevice>
) {
    items(
        items = deviceList
    ) { item ->
        DiscoveredDeviceCell(
            item.name,
            item.address,
            extras = {
                if (item.rssi != 0) {
                    RssiIcon(item.rssi)
                }
            },
            onItemClick = { onItemClick.invoke(item) })
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun DiscoveredDeviceCell(
    name: String?,
    address: String,
    modifier: Modifier = Modifier,
    borderModifier: Modifier = Modifier.border(1.5.dp, ComposeAppTheme.colors.blade, RoundedCornerShape(12.dp)),
    extras: @Composable () -> Unit = {},
    onItemClick: () -> Unit = {}
) {
        Surface(
            onClick = onItemClick,
            shape = RectangleShape,
            color = ComposeAppTheme.colors.tyler,
        ) {
            RowUniversal(
                modifier = modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .then(borderModifier)
                    .clickable(onClick = onItemClick),
                verticalAlignment = Alignment.CenterVertically,
            )
            {
                Icon(
                    painter = painterResource(R.drawable.icon_hardware_wallet_24),
                    modifier = Modifier.padding(start = 16.dp),
                    contentDescription = null,
                    tint = ComposeAppTheme.colors.leah
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    name?.let { name ->
                        Text(
                            text = name.shorten(12),
                            color = ComposeAppTheme.colors.leah,
                            style = ComposeAppTheme.typography.title3
                        )
                    } ?: Text(
                        text = stringResource(R.string.HardwareWalletBle_DeviceWithoutName),
                        color = ComposeAppTheme.colors.leah,
                        style = ComposeAppTheme.typography.title3,
                        modifier = Modifier.alpha(0.7f)
                    )
                    Text(
                        text = address,
                        color = ComposeAppTheme.colors.leah,
                        style = ComposeAppTheme.typography.body
                    )
                }
                extras()
            }
        }
}

@Preview(showBackground = true)
@Composable
private fun Preview_DiscoveredHitoView() {
    ComposeAppTheme(darkTheme = true) {
        DiscoveredDeviceCell(
            name = "antidisestablishmentarianism",
            address = "AA:BB:CC:DD:EE:FF",
            extras = { RssiIcon(rssi = -85) }
        )
    }
}

private const val MEDIUM_RSSI = -80
private const val MAX_RSSI = -60

@Composable
fun RssiIcon(rssi: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(end = 16.dp)) {
        Image(
            painter = painterResource(id = getImageRes(rssi)),
            contentDescription = null
        )
        Text(
            text = "$rssi dBm",
            modifier = Modifier.padding(top = 10.dp),
            color = ComposeAppTheme.colors.leah,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun String.shorten(maxLength: Int): String {
    if (this.length <= maxLength) return this
    val partLength = (maxLength - 3) / 2
    return "${this.take(partLength)}...${this.takeLast(partLength)}"
}

@DrawableRes
private fun getImageRes(rssi: Int): Int {
    return when {
        rssi < MEDIUM_RSSI -> R.drawable.ic_signal_min
        rssi < MAX_RSSI -> R.drawable.ic_signal_medium
        else -> R.drawable.ic_signal_max
    }
}