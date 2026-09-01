package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.core.getInput
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.AppBar
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.bankwallet.ui.compose.components.HsBackButton

class HardwareWalletSelectDeviceFragment : BaseComposeFragment() {
    @Composable
    override fun GetContent(navController: NavController) {
        val input = navController.getInput<HardwareWalletSelectDeviceInput>()
            ?: HardwareWalletSelectDeviceInput(HardwareWalletBleOperation.FirmwareUpgrade)
        val scanViewModel = viewModel<HardwareWalletBleScanViewModel>(
            factory = HardwareWalletBleScanViewModel.Factory(),
        )
        val session = HardwareWalletBleModule.session(requireContext())

        fun closeSelector() {
            scanViewModel.stopScanning()
            if (input.operation != HardwareWalletBleOperation.FirmwareUpgrade) {
                input.requestId?.let(HardwareWalletBleModule.signingRequestRepository::remove)
            }
            navController.popBackStack()
        }

        ComposeAppTheme {
            HardwareWalletSelectDeviceScreen(
                title = stringResource(input.operation.titleResId),
                viewModel = scanViewModel,
                bondedOnly = false,
                onBackClick = ::closeSelector,
                onDeviceSelected = { device ->
                    scanViewModel.selectDevice(device) { selectedDevice ->
                        session.selectDevice(selectedDevice)
                        when (input.operation.destination) {
                            HardwareWalletBleDestination.FirmwareUpgrade ->
                                navController.slideFromRight(R.id.hardwareWalletFirmwareUpgradeFragment)
                            HardwareWalletBleDestination.TransactionSigning ->
                                navController.slideFromRight(
                                    R.id.hardwareWalletTransactionSigningFragment,
                                    HardwareWalletTransactionInput(
                                        requestId = requireNotNull(input.requestId),
                                        operation = input.operation,
                                        returnDestinationId = input.returnDestinationId,
                                    ),
                                )
                            HardwareWalletBleDestination.Pairing ->
                                navController.slideFromRight(
                                    R.id.hardwareWalletTransactionSigningFragment,
                                    HardwareWalletTransactionInput(
                                        requestId = requireNotNull(input.requestId),
                                        operation = input.operation,
                                        returnDestinationId = input.returnDestinationId,
                                    ),
                                )
                            HardwareWalletBleDestination.ImportMnemonic ->
                                navController.slideFromRight(
                                    R.id.hardwareWalletMnemonicImportFragment,
                                )
                            }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HardwareWalletSelectDeviceScreen(
    title: String,
    viewModel: HardwareWalletBleScanViewModel,
    bondedOnly: Boolean = false,
    onBackClick: () -> Unit,
    onDeviceSelected: (HitoDevice) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleActive by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var bluetoothEnabled by remember { mutableStateOf(context.isBluetoothEnabled()) }

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val permissionsState = rememberMultiplePermissionsState(permissions)

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        bluetoothEnabled = result.resultCode == Activity.RESULT_OK || context.isBluetoothEnabled()
    }

    BackHandler(onBack = onBackClick)

    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    bluetoothEnabled = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR,
                    ) == BluetoothAdapter.STATE_ON
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    lifecycleActive = true
                    bluetoothEnabled = context.isBluetoothEnabled()
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> lifecycleActive = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopScanning()
        }
    }

    LaunchedEffect(
        permissionsState.allPermissionsGranted,
        bluetoothEnabled,
        lifecycleActive,
    ) {
        viewModel.updateScanConditions(
            HardwareWalletBleScanConditions(
                permissionsGranted = permissionsState.allPermissionsGranted,
                bluetoothEnabled = bluetoothEnabled,
                lifecycleActive = lifecycleActive,
            ),
        )
    }

    Column(
        modifier = Modifier
            .background(ComposeAppTheme.colors.tyler)
            .fillMaxSize(),
    ) {
        AppBar(
            title = title,
            navigationIcon = { HsBackButton(onClick = onBackClick) },
        )

        when {
            permissionsState.allPermissionsGranted && bluetoothEnabled ->
                HardwareWalletDeviceList(
                    viewModel = viewModel,
                    bondedOnly = bondedOnly,
                    onRetryScan = viewModel::refresh,
                    onItemClick = onDeviceSelected,
                )
            permissionsState.permissions.any { it.status.shouldShowRationale } -> {
                val locationRequired = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                PermissionDeniedScreen(
                    message = stringResource(
                        if (locationRequired) R.string.HardwareWalletBle_PermissionRationaleLocation
                        else R.string.HardwareWalletBle_PermissionRationale,
                    ),
                    buttonText = stringResource(R.string.HardwareWalletBle_GrantPermission),
                    isLocationRequired = locationRequired,
                    onRequestPermission = permissionsState::launchMultiplePermissionRequest,
                )
            }
            !bluetoothEnabled && permissionsState.allPermissionsGranted ->
                BluetoothDisabledScreen {
                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            else -> {
                val locationRequired = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                PermissionDeniedScreen(
                    message = stringResource(
                        if (locationRequired) R.string.HardwareWalletBle_PermissionSettingsLocation
                        else R.string.HardwareWalletBle_PermissionSettings,
                    ),
                    buttonText = stringResource(R.string.HardwareWalletBle_OpenSettings),
                    isLocationRequired = locationRequired,
                ) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                }
            }
        }
    }
}

private fun Context.isBluetoothEnabled(): Boolean =
    runCatching {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
    }.getOrDefault(false)

@Composable
private fun SelectDeviceHeader(onRetryScan: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.HardwareWalletBle_NearbyDevices),
            style = ComposeAppTheme.typography.title3,
            color = ComposeAppTheme.colors.leah,
        )
        ButtonPrimaryYellow(
            title = stringResource(R.string.HardwareWalletBle_RetryScan),
            onClick = onRetryScan,
        )
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun HardwareWalletDeviceList(
    viewModel: HardwareWalletBleScanViewModel,
    bondedOnly: Boolean,
    onRetryScan: () -> Unit,
    onItemClick: (HitoDevice) -> Unit,
) {
    val devices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val scannerState by viewModel.scannerState.collectAsStateWithLifecycle()
    val refreshing = scannerState is ScanningState.Loading
    val pullRefreshState = rememberPullRefreshState(refreshing, viewModel::refresh)

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        SelectDeviceHeader(onRetryScan)
        Box(
            modifier = Modifier.fillMaxSize().pullRefresh(pullRefreshState),
            contentAlignment = Alignment.Center,
        ) {
            HardwareWalletDeviceListContent(
                scannerState,
                if (bondedOnly) devices.filter(HitoDevice::isBonded) else devices,
                onItemClick,
            )
            PullRefreshIndicator(
                refreshing = refreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                backgroundColor = ComposeAppTheme.colors.blade,
                contentColor = ComposeAppTheme.colors.leah,
                scale = true,
            )
        }
    }
}

@Composable
internal fun HardwareWalletDeviceListContent(
    scannerState: ScanningState,
    devices: List<HitoDevice>,
    onItemClick: (HitoDevice) -> Unit,
) {
    when (scannerState) {
        is ScanningState.Loading -> ScannerMessage(R.string.HardwareWalletBle_Scanning)
        is ScanningState.Error -> ScannerError()
        is ScanningState.TryAgain -> ScannerMessage(R.string.HardwareWalletBle_TryAgainLater)
        is ScanningState.DevicesDiscovered,
        is ScanningState.Finished -> {
            if (devices.isEmpty()) {
                ScannerMessage(R.string.HardwareWalletBle_NoDevices)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    discoveredDeviceList(onItemClick, devices)
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ScannerError() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painter = painterResource(R.drawable.ic_attention_24),
            contentDescription = null,
            tint = ComposeAppTheme.colors.lucian,
            modifier = Modifier.padding(bottom = 12.dp).size(48.dp),
        )
        ScannerMessage(R.string.HardwareWalletBle_ScanError)
    }
}

@Composable
private fun ScannerMessage(textResId: Int) {
    Text(
        text = stringResource(textResId),
        style = ComposeAppTheme.typography.headline1,
        color = ComposeAppTheme.colors.grey,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
