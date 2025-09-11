package io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.BleException
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.BootloaderVersion
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.DeviceVersionInfo
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.HitoBleManager
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.HitoDevice
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.HitoDevicesScanner
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.HitoDevicesStore
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.ScanningState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

const val HARDWARE_WALLET_BLE_SCAN_TIME = 5_000L

class HardwareWalletFirmwareUpgradeViewModel(
) : ViewModel() {

    private val _scannerState = MutableStateFlow<ScanningState>(ScanningState.Loading)
    val scannerState: StateFlow<ScanningState> = _scannerState.asStateFlow()

    private val _installationFinished = MutableStateFlow(false)
    val installationFinished: StateFlow<Boolean> = _installationFinished.asStateFlow()

    private val _hitoBleManager = MutableStateFlow<HitoBleManager?>(null)
    val hitoBleManager: StateFlow<HitoBleManager?> = _hitoBleManager.asStateFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    val installationProgress: StateFlow<Double> =
        _hitoBleManager
            .flatMapLatest { mgr -> mgr?.installationProgress ?: flowOf(-1.0) }
            .onEach { p ->
                if (p >= 1.0) {
                    _installationFinished.value = true
                    _upgradeState.value = UpgradeState.Finished
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1.0)

    private val _upgradeState = MutableStateFlow<UpgradeState>(UpgradeState.Start)
    val upgradeState: StateFlow<UpgradeState> = _upgradeState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<HitoDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<HitoDevice>> = _discoveredDevices.asStateFlow()

    private val _deviceVersionInfo = MutableStateFlow<DeviceVersionInfo?>(null)
    val deviceVersionInfo: StateFlow<DeviceVersionInfo?> = _deviceVersionInfo.asStateFlow()

    private val hitoDeviceStore: HitoDevicesStore = HitoDevicesStore()
    private var scanJob: Job? = null

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val firmwareDownloader = FirmwareDownloader()

    // Expose firmware download states
    private val isBetaVerified = firmwareDownloader.isBetaVerified
    private val isGenesisVerified = firmwareDownloader.isGenesisVerified
    private val isHotVerified = firmwareDownloader.isHotVerified
    private val receivedBetaFirmware = firmwareDownloader.receivedBetaFirmware
    private val receivedGenesisFirmware = firmwareDownloader.receivedGenesisFirmware
    private val receivedHotFirmware = firmwareDownloader.receivedHotFirmware

    private var scanning = false

    val betaVersion = firmwareDownloader.betaVersion
    val genesisVersion = firmwareDownloader.genesisVersion
    val hotVersion = firmwareDownloader.hotVersion

    private val _downloadedFirmwareVersion = MutableStateFlow<String?>(null)
    val downloadedFirmwareVersion: StateFlow<String?> = _downloadedFirmwareVersion.asStateFlow()

    // Firmware download state
    private val _firmwareDownloadState = MutableStateFlow<FirmwareDownloadState>(FirmwareDownloadState.Success)
    val firmwareDownloadState = _firmwareDownloadState.asStateFlow()

    private val hitoDevicesScanner: HitoDevicesScanner = HitoDevicesScanner(hitoDeviceStore)

    private fun downloadFirmwareForDevice(bootloaderVersion: BootloaderVersion) {
        viewModelScope.launch {
            _firmwareDownloadState.value = FirmwareDownloadState.Loading
            //_upgradeState.value = UpgradeState.LOADING

            try {
                _firmwareDownloadState.value = when (bootloaderVersion) {
                    BootloaderVersion.BETA -> {
                        Log.d("hito-ble", "Downloading Beta firmware...")
                        firmwareDownloader.extractBetaFromUrl()
                    }
                    BootloaderVersion.GENESIS -> {
                        Log.d("hito-ble", "Downloading Genesis firmware...")
                        firmwareDownloader.extractGenesisFromUrl()
                    }
                    BootloaderVersion.GENESIS_HOT -> {
                        Log.d("hito-ble", "Downloading Genesis Hot firmware...")
                        firmwareDownloader.extractGenesisHotFromUrl()
                    }
                    BootloaderVersion.NONE -> {
                        _upgradeState.value = UpgradeState.Error("Unknown bootloader version, cannot download firmware")
                        FirmwareDownloadState.Error("Unknown bootloader version, cannot download firmware")
                    }
                }

                _downloadedFirmwareVersion.value = when (bootloaderVersion) {
                    BootloaderVersion.BETA -> firmwareDownloader.betaVersion.value ?: "Unknown"
                    BootloaderVersion.GENESIS -> firmwareDownloader.genesisVersion.value ?: "Unknown"
                    BootloaderVersion.GENESIS_HOT -> firmwareDownloader.hotVersion.value ?: "Unknown"
                    BootloaderVersion.NONE -> "None"
                }
                Log.d("hito-ble", "Genesis version: ${firmwareDownloader.genesisVersion.value}")
                Log.d("hito-ble", "Firmware version to be installed: ${_downloadedFirmwareVersion.value}")

                if (isDownloadedFirmwareVersionOlderThanDeviceFirmwareVersion()) {
                    _firmwareDownloadState.value = FirmwareDownloadState.NewestVersion
                    _upgradeState.value = UpgradeState.Connected
                    return@launch
                }

            } catch (e: Exception) {
                Log.d("hito-ble", "Firmware download failed: ${e.message}")
                _firmwareDownloadState.value = FirmwareDownloadState.Error("Unknown firmware download error")
                _upgradeState.value = UpgradeState.Error("Unknown firmware download error")
            }
        }
    }

    fun installFirmware() {
        viewModelScope.launch {
            _upgradeState.value = UpgradeState.LoadingFirmware
            try {
                if (isBetaVerified.value && receivedBetaFirmware.value) {
                    hitoBleManager.value?.sendPayload(firmwareDownloader.getBetaFirmwareData()!!)
                }
                if (isGenesisVerified.value && receivedGenesisFirmware.value) {
                    hitoBleManager.value?.sendPayload(firmwareDownloader.getGenesisFirmwareData()!!)
                }
                if (isHotVerified.value && receivedHotFirmware.value) {
                    hitoBleManager.value?.sendPayload(firmwareDownloader.getHotFirmwareData()!!)
                }
                _upgradeState.value = UpgradeState.Finished
            }
            catch (e: BleException) {
                _upgradeState.value = UpgradeState.BluetoothError(e.message)
            }
            catch (e: Exception) {
                _upgradeState.value = UpgradeState.Error(e.message)
            }
        }
    }

    private fun isDownloadedFirmwareVersionOlderThanDeviceFirmwareVersion(): Boolean {
        if (downloadedFirmwareVersion.value == null || deviceVersionInfo.value == null) {
            return false
        }
        val deviceVersion = deviceVersionInfo.value!!.deviceVersion
        val downloadedVersion = downloadedFirmwareVersion.value!!
        return deviceVersion >= downloadedVersion
    }

    fun startScanning() {
        if (scanning) return
        scanning = true
        scanJob?.cancel()
        _scannerState.value = ScanningState.Loading
        _discoveredDevices.value = emptyList()

        scanJob = viewModelScope.launch {
            val result = withTimeoutOrNull(HARDWARE_WALLET_BLE_SCAN_TIME) {
                hitoDevicesScanner.getScannerState()
                    .catch { _scannerState.value = ScanningState.Error(-1) }
                    .collect { state ->
                        _scannerState.value = state
                        if (state is ScanningState.DevicesDiscovered) {
                            Log.d("hito-ble", "Discovered ${state.size()} devices")
                            _discoveredDevices.value = state.devices.toList()
                        }
                    }
            }
            if (result == null && _discoveredDevices.value.isEmpty()) {
                Log.d("hito-ble", "Scan timed out and no devices found")
                _scannerState.value = ScanningState.DevicesDiscovered(_discoveredDevices.value)
            } else if (result == null && _discoveredDevices.value.isNotEmpty()) {
                _scannerState.value = ScanningState.TryAgain
                //_scannerState.value = ScanningState.DevicesDiscovered(_discoveredDevices.value)
            }
        }
    }

    fun onHitoDeviceSelected() {
        _upgradeState.value = UpgradeState.Loading

        viewModelScope.launch {
            try {
                _hitoBleManager.value!!.connect()
                val versionInfo = _hitoBleManager.value!!.requestDeviceVersion()
                _deviceVersionInfo.value = versionInfo

                if (versionInfo != null) {
                    Log.d("HitoFirmware", "Device version: ${versionInfo.deviceVersion}, " +
                            "Bootloader: ${versionInfo.bootloaderVersion}")
                    _upgradeState.value = UpgradeState.Connected
                    _deviceName.value = _hitoBleManager.value!!.getDeviceName()
                    downloadFirmwareForDevice(versionInfo.bootloaderVersion)
                } else {
                    _upgradeState.value = UpgradeState.NotVerified
                }
                _upgradeState.value = UpgradeState.Connected
                _deviceName.value = _hitoBleManager.value!!.getDeviceName()
            } catch (e: Exception) {
                _upgradeState.value = UpgradeState.Error("Connection error")
            }
        }
    }

    fun onBleManagerSet(hitoBleManager: HitoBleManager) {
        this._hitoBleManager.value = hitoBleManager
    }

    fun refresh() {
        stopScanning()
        scanning = false
        startScanning()
    }

    fun onLeavingScreen() {
        _hitoBleManager.value?.release()
        _hitoBleManager.value = null
        _deviceVersionInfo.value = null
        _deviceName.value = null
        _installationFinished.value = false
        _upgradeState.value = UpgradeState.Start
        _downloadedFirmwareVersion.value = null
        firmwareDownloader.release()
        hitoDevicesScanner.clear()
        scanJob?.cancel()
        scanJob = null
    }

    fun stopScanning() {
        hitoDevicesScanner.clear()
        scanJob?.cancel()
        scanJob = null
        scanning = false
    }

    override fun onCleared() {
        scanJob?.cancel()
        hitoDevicesScanner.clear()
        _hitoBleManager.value?.release()
        firmwareDownloader.release()
    }
}

sealed class FirmwareDownloadState {
    data object Success: FirmwareDownloadState()
    data object NewestVersion : FirmwareDownloadState()
    data class Error(val message: String?) : FirmwareDownloadState() {
        override fun toString(): String {
            return message ?: "Unknown error"
        }
    }
    data object Loading : FirmwareDownloadState()

    val loading: Boolean
        get() = this is Loading

    val errorOrNull: String?
        get() = (this as? Error)?.message
}

sealed class UpgradeState {
    data class BluetoothError(val message: String?): UpgradeState() {
        override fun toString(): String {
            return message ?: "Unknown Bluetooth error"
        }
    }
    data object Start : UpgradeState()
    data object NotVerified : UpgradeState()
    data class TimedOut(val message: String?): UpgradeState() {
        override fun toString(): String {
            return message ?: "Operation timed out"
        }
    }
    data object Expert : UpgradeState()
    data object Loading : UpgradeState()
    data object LoadingFirmware : UpgradeState()
    data object Connected : UpgradeState()
    data class Error(val message: String?): UpgradeState() {
        override fun toString(): String {
            return message ?: "Unknown error"
        }
    }

    val errorOrNull: String?
        get() = when (this) {
            is Error -> message
            is TimedOut -> message
            is BluetoothError -> message
            else -> null
        }

    data object Finished : UpgradeState()
}
