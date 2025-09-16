package io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.core.slideFromBottom
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.entities.DataState
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.FirmwareDownloadState
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.HardwareWalletFirmwareUpgradeViewModel
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.UpgradeState
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.BootloaderVersion
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.DeviceVersionInfo
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.AppBar
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryDefault
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryTransparent
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.bankwallet.ui.compose.components.HsBackButton

class HardwareWalletFirmwareUpgradeFragment : BaseComposeFragment() {
    @Composable
    override fun GetContent(navController: NavController) {
        ComposeAppTheme {
            val viewModel = viewModel<HardwareWalletFirmwareUpgradeViewModel>(
                viewModelStoreOwner = requireActivity()
            )
            HardwareWalletFirmwareUpgradeScreen(navController, viewModel)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview_FirmwareUpgradeData() {
    val isDarkTheme = false
    ComposeAppTheme(isDarkTheme) {
        FirmwareUpgradeData(
            upgradeState = UpgradeState.Connected,
            firmwareDownloadState = FirmwareDownloadState.NewestVersion,
            deviceVersionInfo = DeviceVersionInfo(
                bootloaderVersion = BootloaderVersion.GENESIS,
                deviceVersion = "0.4.6'10"
            ),
            downloadedFirmwareVersion = "0.4.7'12",
            deviceName = "hito",
            installationProgress = 0.15
        )
    }
}

@Composable
private fun InstallationProgressBar(installationProgress: Double) {
    Text(
        text = "Uploading the firmware: ${(installationProgress * 100).toInt()}%",
        color = ComposeAppTheme.colors.leah,
        style = ComposeAppTheme.typography.body,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
    )
    LinearProgressIndicator(
        progress = { installationProgress.toFloat() },
        color = ComposeAppTheme.colors.jacob,
        trackColor = ComposeAppTheme.colors.grey,
    )
    CircularProgressIndicator(
        modifier = Modifier.padding(top = 16.dp),
        color = ComposeAppTheme.colors.grey
    )
    Text(
        text = "Do not close the app while the device is flashing",
        color = ComposeAppTheme.colors.leah,
        style = ComposeAppTheme.typography.headline1,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 32.dp, bottom = 16.dp)
    )
}


@Composable
private fun FirmwareUpgradeData(
    upgradeState: UpgradeState,
    firmwareDownloadState: FirmwareDownloadState,
    deviceVersionInfo: DeviceVersionInfo?,
    downloadedFirmwareVersion: String?,
    deviceName: String? = null,
    onInstallClick : () -> Unit = { },
    installationProgress: Double = -1.0,
    onCloseClick: () -> Unit = { },
    onTryAgainClick: () -> Unit = { },
    onFinishClick: () -> Unit = { },
) {
    Box(Modifier
        .fillMaxSize()
        .background(ComposeAppTheme.colors.tyler)) {
        Column(Modifier
            .align(Alignment.Center)
            .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (upgradeState) {
                is UpgradeState.NotVerified -> {
                    Text(
                        text = "Caution!",
                        color = ComposeAppTheme.colors.leah,
                        style = ComposeAppTheme.typography.title1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
                    )
                    Row {
                        Icon(
                            painter = painterResource(R.drawable.icon_hardware_wallet_24),
                            contentDescription = null,
                            tint = ComposeAppTheme.colors.leah,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_attention_24),
                            contentDescription = null,
                            tint = ComposeAppTheme.colors.jacob,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Firmware upgrade is not verified or couldn't be downloaded.",
                        color = ComposeAppTheme.colors.leah,
                        style = ComposeAppTheme.typography.body,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    ButtonPrimaryDefault(
                        title = "Close",
                        enabled = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCloseClick
                    )
                }
                is UpgradeState.Connected, UpgradeState.LoadingFirmware -> {
                    Text(
                        text = "Your device is ready for the upgrade!",
                        color = ComposeAppTheme.colors.leah,
                        style = ComposeAppTheme.typography.headline1,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Row {
                        Text(
                            text = "Connected to $deviceName",
                            color = ComposeAppTheme.colors.leah,
                            style = ComposeAppTheme.typography.body,
                            textAlign = TextAlign.Center,
                        )
                        Icon(
                            painter = painterResource(R.drawable.icon_hardware_wallet_24),
                            modifier = Modifier.padding(start = 8.dp),
                            contentDescription = null,
                            tint = ComposeAppTheme.colors.grey
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Bootloader version: ${deviceVersionInfo?.bootloaderVersion ?: "unknown"}",
                        color = ComposeAppTheme.colors.leah,
                        style = ComposeAppTheme.typography.headline2,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Current firmware version: ${deviceVersionInfo?.deviceVersion ?: "unknown"}",
                        color = ComposeAppTheme.colors.leah,
                        style = ComposeAppTheme.typography.headline2,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    when (firmwareDownloadState) {
                        is FirmwareDownloadState.Loading -> {
                            Spacer(Modifier.height(16.dp))
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(64.dp),
                                color = ComposeAppTheme.colors.grey
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Downloading the firmware...",
                                color = ComposeAppTheme.colors.grey,
                                style = ComposeAppTheme.typography.body,
                                textAlign = TextAlign.Center
                            )
                        }
                        is FirmwareDownloadState.NewestVersion -> {
                            Spacer(Modifier.height(16.dp))
                            Row {
                                Icon(
                                    painter = painterResource(R.drawable.icon_hardware_wallet_24),
                                    contentDescription = null,
                                    tint = ComposeAppTheme.colors.leah,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = ComposeAppTheme.colors.remus,
                                    modifier = Modifier.size(64.dp)
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Your device is up to date",
                                color = ComposeAppTheme.colors.leah,
                                style = ComposeAppTheme.typography.headline1,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(24.dp))
                            ButtonPrimaryYellow(
                                title = "Finish",
                                enabled = true,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onFinishClick
                            )
                        }
                        is FirmwareDownloadState.Success -> {
                            Text(
                                text = "New firmware version: ${downloadedFirmwareVersion ?: "downloading..."}",
                                color = ComposeAppTheme.colors.leah,
                                style = ComposeAppTheme.typography.headline2,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(24.dp))
                            if (upgradeState is UpgradeState.Connected) {
                                ButtonPrimaryDefault(
                                    title = "Install firmware upgrade",
                                    enabled = downloadedFirmwareVersion?.isNotEmpty() == true,
                                    onClick = onInstallClick,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                InstallationProgressBar(installationProgress)
                            }
                        }
                        is FirmwareDownloadState.Error -> {
                            Spacer(Modifier.height(16.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.icon_hardware_wallet_24),
                                    contentDescription = null,
                                    tint = ComposeAppTheme.colors.leah,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = null,
                                    tint = ComposeAppTheme.colors.lucian,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "An error occurred during the firmware download:",
                                color = ComposeAppTheme.colors.leah,
                                style = ComposeAppTheme.typography.headline1,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "${firmwareDownloadState.message}",
                                color = ComposeAppTheme.colors.leah,
                                style = ComposeAppTheme.typography.body,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(24.dp))
                            ButtonPrimaryDefault(
                                title = "Close",
                                enabled = true,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onCloseClick
                            )
                        }
                    }
                }
                is UpgradeState.Loading -> {
                    CircularProgressIndicator(
                        color = ComposeAppTheme.colors.grey,
                        modifier = Modifier.size(112.dp)
                    )
                    Spacer(Modifier.height(32.dp))
                    Text(
                        text = "Connecting to the device...",
                        color = ComposeAppTheme.colors.grey,
                        style = ComposeAppTheme.typography.headline1,
                        textAlign = TextAlign.Center,
                    )
                }
                is UpgradeState.Error -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.icon_hardware_wallet_24),
                            contentDescription = null,
                            tint = ComposeAppTheme.colors.leah,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                            tint = ComposeAppTheme.colors.lucian,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "An error occurred during the upgrade process: ${upgradeState.errorOrNull}",
                        color = ComposeAppTheme.colors.leah,
                        style = ComposeAppTheme.typography.headline1,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Please try again or contact support",
                        color = ComposeAppTheme.colors.leah,
                        style = ComposeAppTheme.typography.body,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    ButtonPrimaryDefault(
                        title = "Close",
                        enabled = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCloseClick
                    )
                }
                is UpgradeState.BluetoothError -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.icon_hardware_wallet_24),
                            contentDescription = null,
                            tint = ComposeAppTheme.colors.leah,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = ComposeAppTheme.colors.lucian,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Bluetooth error: ${upgradeState.errorOrNull}",
                        color = ComposeAppTheme.colors.leah,
                        style = ComposeAppTheme.typography.headline1,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Please ensure Bluetooth is enabled and try again",
                        color = ComposeAppTheme.colors.leah,
                        style = ComposeAppTheme.typography.body,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    ButtonPrimaryDefault(
                        title = "Close",
                        enabled = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCloseClick
                    )
                }
                is UpgradeState.Finished -> {
                    Box(Modifier.padding(top = 16.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Finished uploading the firmware!",
                                color = ComposeAppTheme.colors.leah,
                                style = ComposeAppTheme.typography.headline1,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(32.dp))
                            Row {
                                Icon(
                                    painter = painterResource(R.drawable.icon_hardware_wallet_24),
                                    contentDescription = null,
                                    tint = ComposeAppTheme.colors.leah,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = ComposeAppTheme.colors.remus,
                                    modifier = Modifier.size(64.dp)
                                )
                            }
                            Spacer(Modifier.height(32.dp))
                            Text(
                                text = "Your device should be able to turn back on",
                                color = ComposeAppTheme.colors.leah,
                                style = ComposeAppTheme.typography.body,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "If it didn't, try upgrading the firmware again.",
                                color = ComposeAppTheme.colors.leah,
                                style = ComposeAppTheme.typography.body,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(32.dp))
                            Row{
                                ButtonPrimaryYellow(
                                    title = "Finish",
                                    enabled = true,
                                    modifier = Modifier
                                        .fillMaxWidth().weight(0.5f),
                                    onClick = onFinishClick
                                )
                                Spacer(Modifier.height(16.dp).padding(8.dp))
                                ButtonPrimaryTransparent(
                                    title = "Try Again",
                                    enabled = true,
                                    modifier = Modifier
                                        .fillMaxWidth().weight(0.5f),
                                    onClick = onTryAgainClick
                                )
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}

@Composable
fun HardwareWalletFirmwareUpgradeScreen(
    navController: NavController?,
    viewModel: HardwareWalletFirmwareUpgradeViewModel,
)
{
    val hitoBleManager by viewModel.hitoBleManager.collectAsState()
    LaunchedEffect(Unit) {
        hitoBleManager?.let { _ ->
            viewModel.onHitoDeviceSelected()
        }
    }
    val upgradeState by viewModel.upgradeState.collectAsStateWithLifecycle()
    val firmwareDownloadState by viewModel.firmwareDownloadState.collectAsStateWithLifecycle()
    val installationProgress by viewModel.installationProgress.collectAsStateWithLifecycle()
    val deviceVersionInfo by viewModel.deviceVersionInfo.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val downloadedFirmwareVersion by viewModel.downloadedFirmwareVersion.collectAsState()

    ComposeAppTheme {
        Column(Modifier.fillMaxSize().background(ComposeAppTheme.colors.tyler)) {
            AppBar(
                title = "Firmware Upgrade",
                navigationIcon = {
                    HsBackButton(onClick = {
                        navController?.popBackStack()
                        viewModel.onLeavingScreen()
                    })
                },
            )
            FirmwareUpgradeData(
                upgradeState,
                firmwareDownloadState,
                deviceVersionInfo,
                downloadedFirmwareVersion,
                deviceName,
                onInstallClick = {
                    viewModel.installFirmware()
                },
                installationProgress,
                onCloseClick = {
                    navController?.popBackStack()
                    viewModel.onLeavingScreen()
                },
                onFinishClick = {
                    navController?.slideFromBottom(R.id.mainFragment)
                    viewModel.onLeavingScreen()
                },
                onTryAgainClick = {
                    navController?.popBackStack(R.id.hardwareWalletFirmwareUpgradeInitialFragment, true)
                    viewModel.onLeavingScreen()
                }
            )
        }
    }
}