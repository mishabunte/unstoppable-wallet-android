package io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Parcelable
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import no.nordicsemi.android.support.v18.scanner.ScanResult
import java.lang.Integer.max

@Immutable
@Parcelize
data class HitoDevice(
    val bluetoothDevice: BluetoothDevice,
    val scanResult: ScanResult? = null,
    val name: String? = null,
    val hadName: Boolean = name != null,
    val lastScanResult: ScanResult? = null,
    val rssi: Int = 0,
    val previousRssi: Int = 0,
    val highestRssi: Int = max(rssi, previousRssi),
) : Parcelable {

    fun hasRssiLevelChanged(): Boolean {
        val newLevel =
            if (rssi <= 10) 0 else if (rssi <= 28) 1 else if (rssi <= 45) 2 else if (rssi <= 65) 3 else 4
        val oldLevel =
            if (previousRssi <= 10) 0 else if (previousRssi <= 28) 1 else if (previousRssi <= 45) 2 else if (previousRssi <= 65) 3 else 4
        return newLevel != oldLevel
    }

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    fun update(scanResult: ScanResult): HitoDevice = copy(
        bluetoothDevice = scanResult.device,
        name = scanResult.scanRecord?.deviceName ?: scanResult.device.name,
        previousRssi = rssi,
        rssi = scanResult.rssi,
        highestRssi = if (highestRssi > rssi) highestRssi else rssi
    )

    fun matches(scanResult: ScanResult) = bluetoothDevice.address == scanResult.device.address

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    fun createBond() {
        bluetoothDevice.createBond()
    }

    val displayName: String?
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        get() = when {
            name?.isNotEmpty() == true -> name
            bluetoothDevice.name?.isNotEmpty() == true -> bluetoothDevice.name
            else -> null
        }

    val address: String
        get() = bluetoothDevice.address

    val displayNameOrAddress: String
        get() = displayName ?: address

    val bondingState: Int
        @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
        get() = bluetoothDevice.bondState

    val isBonded: Boolean
        get() = bondingState == BluetoothDevice.BOND_BONDED

    override fun hashCode() = bluetoothDevice.hashCode()

    override fun equals(other: Any?): Boolean {
        if (other is HitoDevice) {
            return bluetoothDevice == other.bluetoothDevice
        }
        return super.equals(other)
    }
}

sealed class ScanningState {
    data object Loading : ScanningState()

    data object TryAgain: ScanningState()

    data class Error(val errorCode: Int) : ScanningState()

    data class DevicesDiscovered(val devices: List<HitoDevice>) : ScanningState() {
        val bonded: List<HitoDevice> = devices.filter { it.isBonded }

        val notBonded: List<HitoDevice> = devices.filter { !it.isBonded }

        fun size(): Int = bonded.size + notBonded.size

        fun isEmpty(): Boolean = devices.isEmpty()
    }

    fun isRunning(): Boolean {
        return this is Loading || this is DevicesDiscovered
    }
}

fun ScanResult.toHitoDevice() = HitoDevice(
    bluetoothDevice = device,
    scanResult = this,
    name = scanRecord?.deviceName,
    previousRssi = rssi,
    rssi = rssi
)