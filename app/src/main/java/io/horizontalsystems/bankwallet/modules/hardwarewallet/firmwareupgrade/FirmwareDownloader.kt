package io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade

import android.util.Log
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.BootloaderVersion
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.security.MessageDigest

class FirmwareDownloader {
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val packageVerifier = FirmwarePackageVerifier()

    // Download states
    private val _isBetaVerified = MutableStateFlow(false)
    val isBetaVerified: StateFlow<Boolean> = _isBetaVerified.asStateFlow()

    private val _isGenesisVerified = MutableStateFlow(false)
    val isGenesisVerified: StateFlow<Boolean> = _isGenesisVerified.asStateFlow()

    private val _isHotVerified = MutableStateFlow(false)
    val isHotVerified: StateFlow<Boolean> = _isHotVerified.asStateFlow()

    private val _receivedBetaFirmware = MutableStateFlow(false)
    val receivedBetaFirmware: StateFlow<Boolean> = _receivedBetaFirmware.asStateFlow()

    private val _receivedGenesisFirmware = MutableStateFlow(false)
    val receivedGenesisFirmware: StateFlow<Boolean> = _receivedGenesisFirmware.asStateFlow()

    private val _receivedHotFirmware = MutableStateFlow(false)
    val receivedHotFirmware: StateFlow<Boolean> = _receivedHotFirmware.asStateFlow()

    // Firmware version states
    private val _betaVersion = MutableStateFlow<String?>(null)
    val betaVersion: StateFlow<String?> = _betaVersion.asStateFlow()

    private val _genesisVersion = MutableStateFlow<String?>(null)
    val genesisVersion: StateFlow<String?> = _genesisVersion.asStateFlow()

    private val _hotVersion = MutableStateFlow<String?>(null)
    val hotVersion: StateFlow<String?> = _hotVersion.asStateFlow()

    // Firmware data
    private var betaFirmwareData: ByteArray? = null
    private var genesisFirmwareData: ByteArray? = null
    private var hotFirmwareData: ByteArray? = null

    suspend fun extractBetaFromUrl(): FirmwareDownloadState {
        val timestamp = System.currentTimeMillis() / 1000
        val fotaUrlStringBeta = "https://fota.hito.xyz/fw/hito-firmware-latest-beta.bin?$timestamp"

        return try {
            val request = Request.Builder()
                .url(fotaUrlStringBeta)
                .build()

            withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()

                response.use {
                    when (response.code) {
                        200 -> {
                            Log.d("hito-ble", "Successfully connected to the server for Beta firmware.")
                            Log.d("hito-ble", "Response code: ${response.code}, Content-Length: ${response.body?.contentLength()}")

                            val urlData = response.body?.bytes() // This is now on IO thread
                            if (urlData != null) {
                                Log.d("hito-ble", "Downloaded Beta firmware, size: ${urlData.size} bytes")
                                if (checkPackage(urlData, BootloaderVersion.BETA)) {
                                    _isBetaVerified.value = true
                                }
                                betaFirmwareData = urlData
                                Log.d("hito-ble", "Beta firmware verified: ${_isBetaVerified.value}")
                                _receivedBetaFirmware.value = true
                                FirmwareDownloadState.Success
                            } else {
                                Log.e("hito-ble", "Beta firmware data is null")
                                _receivedBetaFirmware.value = true
                                FirmwareDownloadState.Error("Beta firmware data is null")
                            }
                        }
                        else -> {
                            Log.e("hito-ble", "Error connecting to the server. Status code: ${response.code}")
                            _receivedBetaFirmware.value = true
                            FirmwareDownloadState.Error("Error connecting to the server to download Beta firmware. Try checking your internet connection.")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("hito-ble", "Error downloading beta firmware from fota.hito.xyz: ${e.message}", e)
            _receivedBetaFirmware.value = true
            FirmwareDownloadState.Error("Unknown error downloading Beta firmware. Try checking your internet connection.")
        }
    }

    suspend fun extractGenesisFromUrl(): FirmwareDownloadState {
        val timestamp = System.currentTimeMillis() / 1000
        val fotaUrlStringGenesis = "https://fota.hito.xyz/fw/hito-firmware-latest-genesis.bin?$timestamp"

        return try {
            val request = Request.Builder()
                .url(fotaUrlStringGenesis)
                .build()

            withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()

                response.use {
                    when (response.code) {
                        200 -> {
                            Log.d("hito-ble", "Successfully connected to the server for Genesis firmware.")
                            Log.d("hito-ble", "Response code: ${response.code}, Content-Length: ${response.body?.contentLength()}")

                            val urlData = response.body?.bytes() // This is now on IO thread
                            if (urlData != null) {
                                Log.d("hito-ble", "Downloaded Genesis firmware, size: ${urlData.size} bytes")
                                if (checkPackage(urlData, BootloaderVersion.GENESIS)) {
                                    _isGenesisVerified.value = true
                                }
                                genesisFirmwareData = urlData
                                Log.d("hito-ble", "Genesis firmware verified: ${_isGenesisVerified.value}")
                                _receivedGenesisFirmware.value = true
                                FirmwareDownloadState.Success
                            } else {
                                Log.e("hito-ble", "Genesis firmware data is null")
                                _receivedGenesisFirmware.value = true
                                FirmwareDownloadState.Error("Genesis firmware data is null")
                            }
                        }
                        else -> {
                            Log.e("hito-ble", "Error connecting to the server. Status code: ${response.code}")
                            _receivedGenesisFirmware.value = true
                            FirmwareDownloadState.Error("Error connecting to the server to download Genesis firmware. Try checking your internet connection.")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("hito-ble", "Error downloading genesis firmware from fota.hito.xyz: ${e.message}, $e", e)
            _receivedGenesisFirmware.value = true
            FirmwareDownloadState.Error("Unknown error downloading Genesis firmware. Try checking your internet connection")
        }
    }

    suspend fun extractGenesisHotFromUrl(): FirmwareDownloadState {
        val timestamp = System.currentTimeMillis() / 1000
        val fotaUrlStringGenesisHot = "https://fota.hito.xyz/fw/hot-firmware-latest-genesis.bin?$timestamp"

        return try {
            val request = Request.Builder()
                .url(fotaUrlStringGenesisHot)
                .build()

            withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()

                response.use {
                    when (response.code) {
                        200 -> {
                            Log.d("hito-ble", "Successfully connected to the server for Hot firmware.")
                            Log.d("hito-ble", "Response code: ${response.code}, Content-Length: ${response.body?.contentLength()}")

                            val urlData = response.body?.bytes() // This is now on IO thread
                            if (urlData != null) {
                                Log.d("hito-ble", "Downloaded Hot firmware, size: ${urlData.size} bytes")
                                if (checkPackage(urlData, BootloaderVersion.GENESIS_HOT)) {
                                    _isHotVerified.value = true
                                }
                                hotFirmwareData = urlData
                                Log.d("hito-ble", "Hot firmware verified: ${_isHotVerified.value}")
                                _receivedHotFirmware.value = true
                                FirmwareDownloadState.Success
                            } else {
                                Log.e("hito-ble", "Hot firmware data is null")
                                _receivedHotFirmware.value = true
                                FirmwareDownloadState.Error("Hot firmware data is null")
                            }
                        }
                        else -> {
                            Log.e("hito-ble", "Error connecting to the server. Status code: ${response.code}")
                            _receivedHotFirmware.value = true
                            FirmwareDownloadState.Error("Error connecting to the server to download Hot firmware. Try checking your internet connection")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("hito-ble", "Error downloading genesis firmware from fota.hito.xyz: ${e.message}", e)
            _receivedHotFirmware.value = true
            FirmwareDownloadState.Error("Unknown error downloading Hot firmware. Try checking your internet connection.")
        }
    }

    private fun checkPackage(buffer: ByteArray, bootVersion: BootloaderVersion): Boolean {
        val (isValid, version) = packageVerifier.checkPackage(buffer, bootVersion)

        if (isValid && version != null) {
            when (bootVersion) {
                BootloaderVersion.BETA -> _betaVersion.value = version
                BootloaderVersion.GENESIS -> _genesisVersion.value = version
                BootloaderVersion.GENESIS_HOT -> _hotVersion.value = version
                BootloaderVersion.NONE -> { /* No version update for NONE */ }
            }
        }

        return isValid
    }

    // Get firmware data
    fun getBetaFirmwareData(): ByteArray? = betaFirmwareData
    fun getGenesisFirmwareData(): ByteArray? = genesisFirmwareData
    fun getHotFirmwareData(): ByteArray? = hotFirmwareData

    // Resource cleanup
    fun release() {
        scope.cancel()
        client.dispatcher.executorService.shutdown()

        // Clear data
        betaFirmwareData = null
        genesisFirmwareData = null
        hotFirmwareData = null

        // Reset states
        _isBetaVerified.value = false
        _isGenesisVerified.value = false
        _isHotVerified.value = false
        _receivedBetaFirmware.value = false
        _receivedGenesisFirmware.value = false
        _receivedHotFirmware.value = false
    }
}