package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class HardwareWalletBleScanViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `scan starts only when permission bluetooth and lifecycle are all active`() =
        runTest(mainDispatcherRule.dispatcher) {
        val scanner = FakeScanner()
        val viewModel = HardwareWalletBleScanViewModel(scanner, 60_000)

        viewModel.updateScanConditions(HardwareWalletBleScanConditions(true, false, true))
        runCurrent()
        assertEquals(0, scanner.starts)

        viewModel.updateScanConditions(HardwareWalletBleScanConditions(true, true, true))
        runCurrent()
        assertEquals(1, scanner.starts)
    }

    @Test
    fun `pause or stop cancels active scan`() = runTest(mainDispatcherRule.dispatcher) {
        val scanner = FakeScanner()
        val viewModel = HardwareWalletBleScanViewModel(scanner, 60_000)
        viewModel.updateScanConditions(HardwareWalletBleScanConditions(true, true, true))
        runCurrent()
        assertTrue(viewModel.scannerState.value is ScanningState.Loading)

        viewModel.updateScanConditions(HardwareWalletBleScanConditions(true, true, false))
        runCurrent()
        assertFalse(viewModel.scannerState.value.isRunning())
    }

    @Test
    fun `selecting device stops scan and forwards exact device`() =
        runTest(mainDispatcherRule.dispatcher) {
        val scanner = FakeScanner()
        val viewModel = HardwareWalletBleScanViewModel(scanner, 60_000)
        val device = HitoDevice(mock(BluetoothDevice::class.java))
        var selected: HitoDevice? = null
        viewModel.updateScanConditions(HardwareWalletBleScanConditions(true, true, true))
        runCurrent()

        viewModel.selectDevice(device) { selected = it }
        advanceUntilIdle()

        assertSame(device, selected)
        assertFalse(viewModel.scannerState.value.isRunning())
    }

    private class FakeScanner : HardwareWalletDeviceScanner {
        private val states = MutableSharedFlow<ScanningState>()
        var starts = 0
        override fun getScannerState(): Flow<ScanningState> = states.onStart {
            starts++
            emit(ScanningState.Loading)
        }
        override fun clear() = Unit
    }
}
