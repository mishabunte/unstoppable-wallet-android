package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

const val HARDWARE_WALLET_BLE_SCAN_TIME = 30_000L

interface HardwareWalletDeviceScanner {
    fun getScannerState(): kotlinx.coroutines.flow.Flow<ScanningState>
    fun clear()
}

data class HardwareWalletBleScanConditions(
    val permissionsGranted: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val lifecycleActive: Boolean = false,
) {
    val canScan: Boolean
        get() = permissionsGranted && bluetoothEnabled && lifecycleActive
}

class HardwareWalletBleScanViewModel(
    private val scanner: HardwareWalletDeviceScanner,
    private val scanTimeMillis: Long = HARDWARE_WALLET_BLE_SCAN_TIME,
) : ViewModel() {

    private val _discoveredDevices = MutableStateFlow<List<HitoDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<HitoDevice>> = _discoveredDevices.asStateFlow()

    private val _scannerState = MutableStateFlow<ScanningState>(ScanningState.Finished)
    val scannerState: StateFlow<ScanningState> = _scannerState.asStateFlow()

    private var conditions = HardwareWalletBleScanConditions()
    private var scanJob: Job? = null

    fun updateScanConditions(newConditions: HardwareWalletBleScanConditions) {
        if (conditions == newConditions) return
        conditions = newConditions
        if (newConditions.canScan) {
            startScanning()
        } else {
            stopScanning()
        }
    }

    fun startScanning() {
        if (!conditions.canScan || scanJob?.isActive == true) return

        scanner.clear()
        _discoveredDevices.value = emptyList()
        _scannerState.value = ScanningState.Loading

        scanJob = viewModelScope.launch {
            try {
                val completed = withTimeoutOrNull(scanTimeMillis.milliseconds) {
                    scanner.getScannerState()
                        .catch { error ->
                            if (error is CancellationException) throw error
                            _scannerState.value = ScanningState.Error(-1)
                        }
                        .collect { state ->
                            if (state is ScanningState.DevicesDiscovered) {
                                _discoveredDevices.value = state.devices
                            }
                            _scannerState.value = state
                        }
                    true
                }

                if (completed == null && _scannerState.value !is ScanningState.Error) {
                    _scannerState.value = ScanningState.Finished
                }
            } finally {
                if (scanJob === currentCoroutineContext()[Job]) {
                    scanJob = null
                }
            }
        }
    }

    fun refresh() {
        if (!conditions.canScan) return
        stopScanning()
        startScanning()
    }

    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
        if (_scannerState.value is ScanningState.Loading ||
            _scannerState.value is ScanningState.DevicesDiscovered
        ) {
            _scannerState.value = ScanningState.Finished
        }
    }

    fun selectDevice(device: HitoDevice, onSelected: (HitoDevice) -> Unit) {
        stopScanning()
        onSelected(device)
    }

    override fun onCleared() {
        stopScanning()
        scanner.clear()
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val store = HitoDevicesStore()
            return HardwareWalletBleScanViewModel(HitoDevicesScanner(store)) as T
        }
    }
}
