package io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ui

import android.Manifest
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.AppBar
import io.horizontalsystems.bankwallet.ui.compose.components.HsBackButton
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.BluetoothDisabledScreen
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.PermissionDeniedScreen


import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.HardwareWalletFirmwareUpgradeViewModel
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.HitoBleManager
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.HitoDevice
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.ScanningState
import io.horizontalsystems.bankwallet.ui.compose.HSSwipeRefresh
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import java.util.Scanner

class HardwareWalletSelectDeviceFragment : BaseComposeFragment() {
    @Composable
    override fun GetContent(navController: NavController) {
        ComposeAppTheme {
            val viewModel = viewModel<HardwareWalletFirmwareUpgradeViewModel>(
                viewModelStoreOwner = requireActivity()
            )
            HardwareWalletSelectDeviceScreen(navController, viewModel)
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun HardwareWalletSelectDeviceScreen(
    navController: NavController? = null,
    viewModel: HardwareWalletFirmwareUpgradeViewModel
)
{
    val context = LocalContext.current
    var bluetoothEnabled by remember { mutableStateOf(false) }
    val onBtStateChanged = { enabled: Int ->
        bluetoothEnabled = enabled == BluetoothAdapter.STATE_ON
    }
    val onStateChanged by rememberUpdatedState(onBtStateChanged)

    LaunchedEffect(Unit) {
        val initial = BluetoothAdapter.getDefaultAdapter()?.state ?: BluetoothAdapter.ERROR
        onStateChanged(initial)
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    onStateChanged(state)
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(receiver, filter)

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    Log.d("HardwareWallet", "Permissions to request: $permissionsToRequest")

    val permissionsState = rememberMultiplePermissionsState(permissionsToRequest)

    LaunchedEffect(Unit) {
        permissionsState.launchMultiplePermissionRequest()
    }

    val allGranted = permissionsState.allPermissionsGranted

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            bluetoothEnabled = true
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (permissionsState.allPermissionsGranted && bluetoothEnabled) {
                        viewModel.startScanning()
                    }
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    viewModel.stopScanning()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            viewModel.startScanning()
        } else {
            viewModel.stopScanning()
        }
    }

    LaunchedEffect(Unit) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothEnabled = bluetoothManager.adapter?.isEnabled == true
    }

    Column(modifier = Modifier
        .background(color = ComposeAppTheme.colors.tyler).fillMaxSize()) {
        AppBar(
            title = stringResource(R.string.HardwareWalletFirmwareUpgrade_Title),
            navigationIcon = {
                HsBackButton(onClick = { navController?.popBackStack() })
            },
        )

        when {
            permissionsState.allPermissionsGranted && bluetoothEnabled -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    HardwareWalletDeviceList(
                        viewModel,
                        onRetryScan = {
                            viewModel.refresh()
                        },
                        onItemClick = { discoveredDevice ->
                            viewModel.onBleManagerSet(
                                HitoBleManager(
                                    context,
                                    discoveredDevice.bluetoothDevice
                                )
                            )
                            navController?.slideFromRight(R.id.hardwareWalletFirmwareUpgradeFragment)
                        }
                    )
                }
            }
            permissionsState.permissions.any { it.status.shouldShowRationale } -> {
                val toGrant = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    "Bluetooth and Location"
                } else {
                    "Bluetooth"
                }
                val reason = "$toGrant access is required to scan for hardware wallet devices."
                PermissionDeniedScreen(
                    message = reason,
                    buttonText = "Grant $toGrant",
                    isLocationRequired = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                ) {
                    permissionsState.launchMultiplePermissionRequest()
                }
            }
            !bluetoothEnabled && permissionsState.allPermissionsGranted -> {
                BluetoothDisabledScreen {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBluetoothLauncher.launch(enableBtIntent)
                }
            }
            else -> {
                val toGrant = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    "Bluetooth and Location"
                } else {
                    "Bluetooth"
                }
                val reason = "Open Settings and grant $toGrant permissions to scan for hardware wallet devices."
                PermissionDeniedScreen(
                    message = reason,
                    buttonText = "Open Settings",
                    isLocationRequired = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                ) {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    context.startActivity(intent)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview_HardwareWalletSelectDeviceScreen() {
    ComposeAppTheme {
        SelectDeviceHeader(onRetryScan = {})
    }
}

@Composable
private fun SelectDeviceHeader(onRetryScan: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly) {
        Text(
            text = "Nearby devices:",
            style = ComposeAppTheme.typography.title3,
            color = ComposeAppTheme.colors.leah,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        ButtonPrimaryYellow(
            title = "Retry Scan",
            onClick = onRetryScan
        )
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun HardwareWalletDeviceList(
    viewModel: HardwareWalletFirmwareUpgradeViewModel,
    onRetryScan: () -> Unit,
    onItemClick: (HitoDevice) -> Unit
) {
    val devices by viewModel.discoveredDevices.collectAsStateWithLifecycle(emptyList())
    val scannerState by viewModel.scannerState.collectAsStateWithLifecycle()
    val refreshing = scannerState is ScanningState.Loading

    val pullRefreshState = rememberPullRefreshState(
        refreshing = refreshing,
        onRefresh = {
            viewModel.refresh()
        }
    )

    SelectDeviceHeader(onRetryScan)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullRefresh(pullRefreshState),
        contentAlignment = Alignment.Center
    ) {
        when (scannerState) {
            is ScanningState.Loading -> {
                ScannerLoading()
            }

            is ScanningState.DevicesDiscovered -> {
                DiscoveredDeviceList(onItemClick, devices)
            }

            is ScanningState.Error -> {
                ScannerError()
            }

            is ScanningState.TryAgain -> {
                RetryScanning()
            }
        }
        PullRefreshIndicator(
            refreshing = refreshing,
            state = pullRefreshState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp),
            backgroundColor = ComposeAppTheme.colors.blade,
            contentColor = ComposeAppTheme.colors.leah,
            scale = true
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview_RetryScanning() {
    ComposeAppTheme {
        RetryScanning()
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview_EmptyDeviceList() {
    ComposeAppTheme {
        EmptyDeviceList()
    }
}

@Composable
private fun DiscoveredDeviceList(
    onItemClick: (HitoDevice) -> Unit,
    devices: List<HitoDevice>
) {
    if (devices.isEmpty()) {
        EmptyDeviceList()
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
    ) {
        discoveredDeviceList(onItemClick, devices)
    }
}

@Composable
private fun RetryScanning() {
//    LazyColumn(
//        modifier = Modifier
//            .fillMaxSize(),
//        horizontalAlignment = Alignment.CenterHorizontally,
//        verticalArrangement = Arrangement.Center,
//    ) {
//        item {
//            EmptyDeviceList()
//        }
//    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        item {
            Icon(
                painter = painterResource(R.drawable.ic_attention_24),
                contentDescription = null,
                tint = ComposeAppTheme.colors.lucian,
                modifier = Modifier.padding(bottom = 16.dp).size(48.dp)
            )
            Text(
                text = "You're scanning too frequently",
                style = ComposeAppTheme.typography.headline2,
                color = ComposeAppTheme.colors.grey,
                modifier = Modifier.padding(horizontal = 16.dp),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Please try again in 30 seconds",
                style = ComposeAppTheme.typography.headline2,
                color = ComposeAppTheme.colors.grey,
                modifier = Modifier.padding(horizontal = 16.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun EmptyDeviceList() {
    Text(
        text = "No Hito device found",
        style = ComposeAppTheme.typography.headline1,
        color = ComposeAppTheme.colors.grey,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Preview(showBackground = true)
@Composable
private fun Preview_ScannerError() {
    ComposeAppTheme {
        ScannerError()
    }
}

@Composable
private fun ScannerError() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        item {
            Icon(
                painter = painterResource(R.drawable.ic_attention_24),
                contentDescription = null,
                tint = ComposeAppTheme.colors.lucian,
                modifier = Modifier.padding(bottom = 12.dp).size(48.dp)
            )
            Text(
                text = "Error occurred while scanning",
                style = ComposeAppTheme.typography.headline1,
                color = ComposeAppTheme.colors.grey,
                modifier = Modifier.padding(horizontal = 16.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ScannerLoading() {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Scanning for Hito devices...",
                style = ComposeAppTheme.typography.headline1,
                color = ComposeAppTheme.colors.grey,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
}
