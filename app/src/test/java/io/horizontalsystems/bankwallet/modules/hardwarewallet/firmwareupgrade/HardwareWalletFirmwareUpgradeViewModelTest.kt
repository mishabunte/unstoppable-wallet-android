package io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade

import android.bluetooth.BluetoothDevice
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.DeviceVersionInfo
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleSession
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletConnectionState
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HitoDevice
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class HardwareWalletFirmwareUpgradeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `firmware flow connects through selected shared session`() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeSession().apply {
                selectedDevice.value = HitoDevice(mock(BluetoothDevice::class.java))
            }
            val viewModel = HardwareWalletFirmwareUpgradeViewModel(
                session,
                mainDispatcherRule.dispatcher,
            )

            viewModel.onHitoDeviceSelected()
            runCurrent()

            assertTrue(session.connected)
            assertEquals(UpgradeState.NotVerified, viewModel.upgradeState.value)
        }

    private class FakeSession : HardwareWalletBleSession {
        override val connectionState = MutableStateFlow<HardwareWalletConnectionState>(
            HardwareWalletConnectionState.Disconnected,
        )
        override val selectedDevice = MutableStateFlow<HitoDevice?>(null)
        override val transferProgress = MutableStateFlow(-1.0)
        var connected = false

        override fun selectDevice(device: HitoDevice) {
            selectedDevice.value = device
        }
        override suspend fun connect() {
            connected = true
            connectionState.value = HardwareWalletConnectionState.Connected
        }
        override suspend fun send(payload: ByteArray) = Unit
        override suspend fun receive(timeoutMillis: Long): ByteArray = byteArrayOf()
        override suspend fun requestDeviceVersion(): DeviceVersionInfo? = null
        override fun deviceName(): String? = "hito"
        override suspend fun disconnect() = Unit
        override fun close() = Unit
    }
}
