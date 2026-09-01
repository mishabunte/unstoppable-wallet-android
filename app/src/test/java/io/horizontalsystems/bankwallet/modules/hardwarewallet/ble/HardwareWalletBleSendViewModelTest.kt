package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import io.horizontalsystems.marketkit.models.BlockchainType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HardwareWalletBleSendViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `transaction cannot be sent twice while transfer is active`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = InMemoryHardwareWalletSigningRequestRepository()
            val requestId = repository.create(
                byteArrayOf(1),
                BlockchainType.Zcash,
                133,
                0,
            )
            val session = FakeSession().apply { blockSend = true }
            val viewModel = HardwareWalletBleSendViewModel(
                requestId,
                repository,
                session,
                mainDispatcherRule.dispatcher,
                HardwareWalletBleOperation.SignTransaction,
            )

            viewModel.start()
            runCurrent()
            viewModel.start()
            runCurrent()

            assertEquals(1, session.sendCalls)
            viewModel.cancel()
        }

    @Test
    fun `user rejection has explicit state and request can be retried`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = InMemoryHardwareWalletSigningRequestRepository()
            val requestId = repository.create(
                byteArrayOf(1),
                BlockchainType.Zcash,
                133,
                0,
            )
            val session = FakeSession().apply { response = "rejected".encodeToByteArray() }
            val viewModel = HardwareWalletBleSendViewModel(
                requestId,
                repository,
                session,
                mainDispatcherRule.dispatcher,
                HardwareWalletBleOperation.SignTransaction,
            )

            viewModel.start()
            runCurrent()

            val error = viewModel.state.value as HardwareWalletTransactionState.Error
            assertEquals(HardwareWalletTransactionError.Rejected, error.type)
            assertEquals(HardwareWalletSigningRequestStatus.Ready, repository.status(requestId))
        }

    @Test
    fun `unexpected disconnect cancels transfer with disconnect state`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = InMemoryHardwareWalletSigningRequestRepository()
            val requestId = repository.create(
                byteArrayOf(1),
                BlockchainType.Zcash,
                133,
                0,
            )
            val session = FakeSession().apply { blockReceive = true }
            val viewModel = HardwareWalletBleSendViewModel(
                requestId,
                repository,
                session,
                mainDispatcherRule.dispatcher,
                HardwareWalletBleOperation.SignTransaction,
            )

            viewModel.start()
            runCurrent()
            assertTrue(viewModel.state.value == HardwareWalletTransactionState.WaitingForDevice)
            session.connection.value = HardwareWalletConnectionState.Disconnected
            runCurrent()

            val error = viewModel.state.value as HardwareWalletTransactionState.Error
            assertEquals(HardwareWalletTransactionError.Disconnected, error.type)
        }

    @Test
    fun `zcash signing operation sends framed pczt payload`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = InMemoryHardwareWalletSigningRequestRepository()
            val requestId = repository.create(
                byteArrayOf(1, 2, 3),
                BlockchainType.Zcash,
                133,
                7,
            )
            val session = FakeSession()
            val viewModel = HardwareWalletBleSendViewModel(
                requestId,
                repository,
                session,
                mainDispatcherRule.dispatcher,
                HardwareWalletBleOperation.SignTransaction,
            )

            viewModel.start()
            runCurrent()

            assertEquals(
                listOf(
                    'p'.code.toByte(),
                    'c'.code.toByte(),
                    'z'.code.toByte(),
                    't'.code.toByte(),
                    0x1F.toByte(),
                    0x01.toByte(),
                    0,
                    3,
                    1,
                    2,
                    3,
                ),
                session.sentPayload?.toList(),
            )
        }

    @Test
    fun `zcash pairing operation sends pairing command`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = InMemoryHardwareWalletSigningRequestRepository()
            val requestId = repository.create(
                byteArrayOf(),
                BlockchainType.Zcash,
                133,
                7,
            )
            val session = FakeSession()
            val viewModel = HardwareWalletBleSendViewModel(
                requestId,
                repository,
                session,
                mainDispatcherRule.dispatcher,
                HardwareWalletBleOperation.Pairing,
            )

            viewModel.start()
            runCurrent()

            assertEquals("pair 133 7\n", session.sentPayload?.decodeToString())
            assertEquals(0, session.receiveCalls)
            assertEquals(
                HardwareWalletSigningRequestStatus.Completed,
                repository.status(requestId),
            )
        }

    @Test
    fun `unsupported blockchain operation sends request payload unchanged`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = InMemoryHardwareWalletSigningRequestRepository()
            val requestId = repository.create(
                byteArrayOf(4, 5, 6),
                BlockchainType.Ethereum,
                60,
                0,
            )
            val session = FakeSession()
            val viewModel = HardwareWalletBleSendViewModel(
                requestId,
                repository,
                session,
                mainDispatcherRule.dispatcher,
                HardwareWalletBleOperation.SignTransaction,
            )

            viewModel.start()
            runCurrent()

            assertEquals(listOf<Byte>(4, 5, 6), session.sentPayload?.toList())
        }

    private class FakeSession : HardwareWalletBleSession {
        val connection = MutableStateFlow<HardwareWalletConnectionState>(
            HardwareWalletConnectionState.Connected,
        )
        override val connectionState = connection
        override val selectedDevice = MutableStateFlow<HitoDevice?>(null)
        override val transferProgress = MutableStateFlow(-1.0)
        var response = byteArrayOf(7)
        var sendCalls = 0
        var receiveCalls = 0
        var sentPayload: ByteArray? = null
        var blockSend = false
        var blockReceive = false
        private val sendGate = CompletableDeferred<Unit>()
        private val receiveGate = CompletableDeferred<Unit>()

        override fun selectDevice(device: HitoDevice) {
            selectedDevice.value = device
        }

        override suspend fun connect() {
            connection.value = HardwareWalletConnectionState.Connected
        }

        override suspend fun send(payload: ByteArray) {
            sendCalls++
            sentPayload = payload.copyOf()
            if (blockSend) sendGate.await()
        }

        override suspend fun receive(timeoutMillis: Long): ByteArray {
            receiveCalls++
            if (blockReceive) receiveGate.await()
            return response.copyOf()
        }

        override suspend fun requestDeviceVersion(): DeviceVersionInfo? = null
        override fun deviceName(): String? = "hito"
        override suspend fun disconnect() {
            connection.value = HardwareWalletConnectionState.Disconnected
        }
        override fun close() = Unit
    }
}
