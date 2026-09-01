package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import no.nordicsemi.android.support.v18.scanner.ScanResult

class HitoDevicesStore {
    private val devices = mutableListOf<HitoDevice>()
    val data = MutableStateFlow<List<HitoDevice>>(emptyList())
    private val comparator =
        compareByDescending<HitoDevice> { it.rssi }.thenBy { it.address }

    fun snapshot(): List<HitoDevice> = devices.toList()

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    fun addNewDevice(scanResult: ScanResult) {
        if (scanResult.device.name == null) return
        if (!scanResult.device.name.startsWith("Hito", true)) return
        val address = scanResult.device.address
        val updated = devices.indexOfFirst { it.address == address }
            .let { idx ->
                if (idx >= 0) {
                    val old = devices.removeAt(idx)
                    old.update(scanResult)
                } else {
                    scanResult.toHitoDevice()
                }
            }

        val insertAt = devices.binarySearch(updated, comparator)
            .let { if (it < 0) -it - 1 else it }
        devices.add(insertAt, updated)

        if (devices == data.value) return

        data.value = devices.toList()
    }

    fun clear() {
        devices.clear()
        data.value = listOf()
    }
}