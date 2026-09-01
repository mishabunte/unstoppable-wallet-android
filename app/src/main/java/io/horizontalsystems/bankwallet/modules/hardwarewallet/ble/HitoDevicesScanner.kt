package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import android.Manifest
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings

class HitoDevicesScanner(
    private val hitoDevicesStore: HitoDevicesStore,
) : HardwareWalletDeviceScanner {
    override fun getScannerState(): Flow<ScanningState> =
        callbackFlow {
            val scanCallback: ScanCallback = object : ScanCallback() {
                @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (result.isConnectable && result.device.name?.startsWith("hito", true) == true && result.device.name != null) {
                        hitoDevicesStore.addNewDevice(result)
                        trySend(ScanningState.Loading)
                        //trySend(ScanningState.DevicesDiscovered(hitoDevicesStore.snapshot()))
                    }
                }
                @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                override fun onBatchScanResults(results: List<ScanResult>) {
                    val newResults = results.filter { it.isConnectable && it.device.name?.startsWith("hito", true) == true && it.device.name != null}
                    newResults.forEach {
                        hitoDevicesStore.addNewDevice(it)
                    }
                    if (newResults.isNotEmpty()) {
                        trySend(ScanningState.DevicesDiscovered(hitoDevicesStore.snapshot()))
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    trySend(ScanningState.Error(errorCode))
                }
            }

            trySend(ScanningState.Loading)

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setLegacy(false)
                .setReportDelay(500)
                .setUseHardwareBatchingIfSupported(false)
                .build()
            val scanner = BluetoothLeScannerCompat.getScanner()
            scanner.startScan(null, settings, scanCallback)

            awaitClose {
                scanner.stopScan(scanCallback)
            }
        }

    override fun clear() {
        hitoDevicesStore.clear()
    }
}
