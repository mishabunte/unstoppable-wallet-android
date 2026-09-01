package io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.BleException
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.BootloaderVersion
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.DeviceVersionInfo
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HardwareWalletFirmwareUpgradeViewModel(
    private val bleSession: HardwareWalletBleSession,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _installationFinished = MutableStateFlow(false)
    val installationFinished: StateFlow<Boolean> = _installationFinished.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val installationProgress: StateFlow<Double> = bleSession.transferProgress
        .onEach { progress ->
            if (progress >= 1.0) {
                _installationFinished.value = true
                _upgradeState.value = UpgradeState.Finished
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1.0)

    private val _upgradeState = MutableStateFlow<UpgradeState>(UpgradeState.Start)
    val upgradeState: StateFlow<UpgradeState> = _upgradeState.asStateFlow()

    private val _deviceVersionInfo = MutableStateFlow<DeviceVersionInfo?>(null)
    val deviceVersionInfo: StateFlow<DeviceVersionInfo?> = _deviceVersionInfo.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val firmwareDownloader = FirmwareDownloader()
    private val isBetaVerified = firmwareDownloader.isBetaVerified
    private val isGenesisVerified = firmwareDownloader.isGenesisVerified
    private val isHotVerified = firmwareDownloader.isHotVerified
    private val receivedBetaFirmware = firmwareDownloader.receivedBetaFirmware
    private val receivedGenesisFirmware = firmwareDownloader.receivedGenesisFirmware
    private val receivedHotFirmware = firmwareDownloader.receivedHotFirmware

    val betaVersion = firmwareDownloader.betaVersion
    val genesisVersion = firmwareDownloader.genesisVersion
    val hotVersion = firmwareDownloader.hotVersion

    private val _downloadedFirmwareVersion = MutableStateFlow<String?>(null)
    val downloadedFirmwareVersion: StateFlow<String?> = _downloadedFirmwareVersion.asStateFlow()

    private val _firmwareDownloadState =
        MutableStateFlow<FirmwareDownloadState>(FirmwareDownloadState.Success)
    val firmwareDownloadState = _firmwareDownloadState.asStateFlow()

    fun onHitoDeviceSelected() {
        if (bleSession.selectedDevice.value == null || _upgradeState.value != UpgradeState.Start) return
        _upgradeState.value = UpgradeState.Loading

        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { bleSession.connect() }
                val versionInfo = withContext(ioDispatcher) {
                    bleSession.requestDeviceVersion()
                }
                _deviceVersionInfo.value = versionInfo
                _deviceName.value = bleSession.deviceName()

                if (versionInfo == null) {
                    _upgradeState.value = UpgradeState.NotVerified
                    return@launch
                }

                Log.d(
                    "HitoFirmware",
                    "Device version: ${versionInfo.deviceVersion}, bootloader: ${versionInfo.bootloaderVersion}",
                )
                _upgradeState.value = UpgradeState.Connected
                downloadFirmwareForDevice(versionInfo.bootloaderVersion)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _upgradeState.value = UpgradeState.Error(error.message ?: "Connection error")
            }
        }
    }

    fun installFirmware() {
        if (_upgradeState.value == UpgradeState.LoadingFirmware) return
        viewModelScope.launch {
            _upgradeState.value = UpgradeState.LoadingFirmware
            try {
                val payload = when {
                    isBetaVerified.value && receivedBetaFirmware.value ->
                        firmwareDownloader.getBetaFirmwareData()
                    isGenesisVerified.value && receivedGenesisFirmware.value ->
                        firmwareDownloader.getGenesisFirmwareData()
                    isHotVerified.value && receivedHotFirmware.value ->
                        firmwareDownloader.getHotFirmwareData()
                    else -> null
                } ?: throw IllegalStateException("Verified firmware is not available")

                withContext(ioDispatcher) { bleSession.send(payload) }
                _upgradeState.value = UpgradeState.Finished
            } catch (error: CancellationException) {
                throw error
            } catch (error: BleException) {
                _upgradeState.value = UpgradeState.BluetoothError(error.message)
            } catch (error: Exception) {
                _upgradeState.value = UpgradeState.Error(error.message)
            }
        }
    }

    fun onLeavingScreen() {
        resetState()
        viewModelScope.launch(ioDispatcher) {
            bleSession.disconnect()
        }
    }

    private fun downloadFirmwareForDevice(bootloaderVersion: BootloaderVersion) {
        viewModelScope.launch {
            _firmwareDownloadState.value = FirmwareDownloadState.Loading
            try {
                _firmwareDownloadState.value = when (bootloaderVersion) {
                    BootloaderVersion.BETA -> firmwareDownloader.extractBetaFromUrl()
                    BootloaderVersion.GENESIS -> firmwareDownloader.extractGenesisFromUrl()
                    BootloaderVersion.GENESIS_HOT -> firmwareDownloader.extractGenesisHotFromUrl()
                    BootloaderVersion.NONE -> {
                        _upgradeState.value = UpgradeState.Error("Unknown bootloader version")
                        FirmwareDownloadState.Error("Unknown bootloader version")
                    }
                }

                _downloadedFirmwareVersion.value = when (bootloaderVersion) {
                    BootloaderVersion.BETA -> firmwareDownloader.betaVersion.value ?: "Unknown"
                    BootloaderVersion.GENESIS -> firmwareDownloader.genesisVersion.value ?: "Unknown"
                    BootloaderVersion.GENESIS_HOT -> firmwareDownloader.hotVersion.value ?: "Unknown"
                    BootloaderVersion.NONE -> "None"
                }

                if (isDownloadedFirmwareVersionOlderThanDeviceFirmwareVersion()) {
                    _firmwareDownloadState.value = FirmwareDownloadState.NewestVersion
                    _upgradeState.value = UpgradeState.Connected
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.d("hito-ble", "Firmware download failed: ${error.message}")
                _firmwareDownloadState.value = FirmwareDownloadState.Error(error.message)
                _upgradeState.value = UpgradeState.Error(error.message)
            }
        }
    }

    private fun isDownloadedFirmwareVersionOlderThanDeviceFirmwareVersion(): Boolean {
        val downloadedVersion = downloadedFirmwareVersion.value ?: return false
        val deviceVersion = deviceVersionInfo.value?.deviceVersion ?: return false
        return deviceVersion >= downloadedVersion
    }

    private fun resetState() {
        _deviceVersionInfo.value = null
        _deviceName.value = null
        _installationFinished.value = false
        _upgradeState.value = UpgradeState.Start
        _downloadedFirmwareVersion.value = null
        _firmwareDownloadState.value = FirmwareDownloadState.Success
        firmwareDownloader.release()
    }

    override fun onCleared() {
        firmwareDownloader.release()
    }

    class Factory(private val bleSession: HardwareWalletBleSession) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HardwareWalletFirmwareUpgradeViewModel(bleSession) as T
    }
}

sealed class FirmwareDownloadState {
    data object Success : FirmwareDownloadState()
    data object NewestVersion : FirmwareDownloadState()
    data class Error(val message: String?) : FirmwareDownloadState() {
        override fun toString(): String = message ?: "Unknown error"
    }
    data object Loading : FirmwareDownloadState()

    val loading: Boolean
        get() = this is Loading

    val errorOrNull: String?
        get() = (this as? Error)?.message
}

sealed class UpgradeState {
    data class BluetoothError(val message: String?) : UpgradeState()
    data object Start : UpgradeState()
    data object NotVerified : UpgradeState()
    data class TimedOut(val message: String?) : UpgradeState()
    data object Expert : UpgradeState()
    data object Loading : UpgradeState()
    data object LoadingFirmware : UpgradeState()
    data object Connected : UpgradeState()
    data class Error(val message: String?) : UpgradeState()
    data object Finished : UpgradeState()

    val errorOrNull: String?
        get() = when (this) {
            is Error -> message
            is TimedOut -> message
            is BluetoothError -> message
            else -> null
        }
}
