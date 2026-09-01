package io.horizontalsystems.bankwallet.modules.hardwarewallet.importmnemonic

import android.bluetooth.BluetoothDevice
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.DeviceVersionInfo
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleSession
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletConnectionState
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HitoDevice
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class HardwareWalletMnemonicImportViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `duplicate confirmation does not start a second import`() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = connectedSession()
            val importer = BlockingImporter()
            val viewModel = viewModel(session, importer)
            viewModel.start()
            viewModel.updatePhrase(PUBLIC_VECTOR)
            viewModel.validateAndConfirm()

            viewModel.confirmImport()
            viewModel.confirmImport()
            runCurrent()

            assertEquals(1, importer.calls)
            assertEquals("", viewModel.uiState.value.phrase)
            viewModel.leave()
        }

    @Test
    fun `local validation error retains entered phrase`() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = connectedSession()
            val viewModel = HardwareWalletMnemonicImportViewModel(
                session = session,
                validator = HardwareWalletMnemonicValidator { MnemonicValidationResult.Empty },
                importer = HardwareWalletMnemonicImporter { ImportMnemonicResult.Success },
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
            viewModel.start()
            viewModel.updatePhrase("not yet valid")

            viewModel.validateAndConfirm()

            assertEquals("not yet valid", viewModel.uiState.value.phrase)
            assertEquals(MnemonicInputError.Empty, viewModel.uiState.value.inputError)
        }

    @Test
    fun `leaving clears reachable phrase state`() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = connectedSession()
            val viewModel = viewModel(session, BlockingImporter())
            viewModel.start()
            viewModel.updatePhrase(PUBLIC_VECTOR)

            viewModel.leave()
            runCurrent()

            assertEquals("", viewModel.uiState.value.phrase)
        }

    @Test
    fun `disconnect during transfer clears phrase and reports disconnect`() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = connectedSession()
            val viewModel = viewModel(session, BlockingImporter())
            viewModel.start()
            viewModel.updatePhrase(PUBLIC_VECTOR)
            viewModel.validateAndConfirm()
            viewModel.confirmImport()
            runCurrent()

            session.connectionState.value = HardwareWalletConnectionState.Disconnected
            runCurrent()

            assertEquals("", viewModel.uiState.value.phrase)
            val phase = viewModel.uiState.value.phase
            assertTrue(phase is MnemonicImportPhase.Error)
            assertEquals(
                MnemonicImportError.Disconnected,
                (phase as MnemonicImportPhase.Error).type,
            )
        }

    @Test
    fun `import timeout clears phrase and reports timeout`() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = connectedSession()
            val viewModel = viewModel(session, BlockingImporter())
            viewModel.start()
            viewModel.updatePhrase(PUBLIC_VECTOR)
            viewModel.validateAndConfirm()
            viewModel.confirmImport()
            runCurrent()

            advanceTimeBy(180_001L)
            runCurrent()

            assertEquals("", viewModel.uiState.value.phrase)
            val phase = viewModel.uiState.value.phase as MnemonicImportPhase.Error
            assertEquals(MnemonicImportError.Timeout, phase.type)
        }

    private fun viewModel(
        session: FakeSession,
        importer: HardwareWalletMnemonicImporter,
    ) = HardwareWalletMnemonicImportViewModel(
        session = session,
        validator = Bip39HardwareWalletMnemonicValidator(),
        importer = importer,
        ioDispatcher = mainDispatcherRule.dispatcher,
    )

    private fun connectedSession() = FakeSession().apply {
        selectedDevice.value = HitoDevice(mock(BluetoothDevice::class.java))
    }

    private class BlockingImporter : HardwareWalletMnemonicImporter {
        var calls = 0
        private val gate = CompletableDeferred<Unit>()

        override suspend fun importMnemonic(mnemonic: CharArray): ImportMnemonicResult {
            calls++
            gate.await()
            return ImportMnemonicResult.Success
        }
    }

    private class FakeSession : HardwareWalletBleSession {
        override val connectionState = MutableStateFlow<HardwareWalletConnectionState>(
            HardwareWalletConnectionState.Connected,
        )
        override val selectedDevice = MutableStateFlow<HitoDevice?>(null)
        override val transferProgress = MutableStateFlow(-1.0)

        override fun selectDevice(device: HitoDevice) {
            selectedDevice.value = device
        }
        override suspend fun connect() = Unit
        override suspend fun send(payload: ByteArray) = Unit
        override suspend fun receive(timeoutMillis: Long): ByteArray = "ok\n".encodeToByteArray()
        override suspend fun requestDeviceVersion(): DeviceVersionInfo? = null
        override fun deviceName(): String? = "hito"
        override suspend fun disconnect() {
            connectionState.value = HardwareWalletConnectionState.Disconnected
        }
        override fun close() = Unit
    }

    companion object {
        private const val PUBLIC_VECTOR =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
