package io.horizontalsystems.bankwallet.modules.hardwarewallet

import cash.z.ecc.android.sdk.model.ZcashNetwork
import io.horizontalsystems.bankwallet.entities.AccountType
import io.horizontalsystems.bankwallet.entities.Address
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.InMemoryHardwareWalletSigningRequestRepository
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.MainDispatcherRule
import io.horizontalsystems.marketkit.models.BlockchainType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class HardwareWalletViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val service = mock(HardwareWalletService::class.java).also {
        `when`(it.nextHardwareAccountName()).thenReturn("Hardware Wallet")
    }
    private val repository = InMemoryHardwareWalletSigningRequestRepository()

    @Test
    fun `zcash account index defaults to zero`() {
        val viewModel = viewModel()

        viewModel.onSetType(HardwareWalletViewModel.Type.ZcashKeyHardware)

        assertEquals("0", viewModel.uiState.zcashAccountIndex)
        assertFalse(viewModel.uiState.invalidZcashAccountIndex)
    }

    @Test
    fun `zcash next creates mainnet pairing request`() {
        val viewModel = viewModel()
        viewModel.onSetType(HardwareWalletViewModel.Type.ZcashKeyHardware)
        viewModel.onSetZcashNetworkType(HardwareWalletViewModel.ZcashNetworkType.Mainnet)

        viewModel.onClickNext()

        val requestId = viewModel.uiState.zcashPairingRequestId
        val request = repository.get(requestId)
        assertEquals(BlockchainType.Zcash, request?.blockchainType)
        assertEquals(133, request?.networkCoinType)
        assertEquals(0L, request?.accountIndex)
    }

    @Test
    fun `invalid zcash account index disables next`() {
        val viewModel = viewModel()
        viewModel.onSetType(HardwareWalletViewModel.Type.ZcashKeyHardware)
        viewModel.onSetZcashNetworkType(HardwareWalletViewModel.ZcashNetworkType.Testnet)

        viewModel.onEnterZcashAccountIndex("2147483648")

        assertTrue(viewModel.uiState.invalidZcashAccountIndex)
        assertEquals(SubmitButtonType.Next(false), viewModel.uiState.submitButtonType)
    }

    @Test
    fun `non zcash next keeps existing account flow`() {
        val viewModel = viewModel()
        viewModel.onEnterAddress(Address("0x1234"))

        viewModel.onClickNext()

        assertEquals(
            AccountType.EvmAddressHardware("0x1234"),
            viewModel.uiState.accountType,
        )
        assertNull(viewModel.uiState.zcashPairingRequestId)
    }

    @Test
    fun `completed pairing waits for qr and consumes request`() {
        val viewModel = viewModel()
        viewModel.onSetType(HardwareWalletViewModel.Type.ZcashKeyHardware)
        viewModel.onSetZcashNetworkType(HardwareWalletViewModel.ZcashNetworkType.Testnet)
        viewModel.onEnterZcashAccountIndex("5")
        viewModel.onClickNext()
        val requestId = checkNotNull(viewModel.uiState.zcashPairingRequestId)
        viewModel.onZcashPairingNavigationHandled()
        assertTrue(repository.acquire(requestId) != null)
        assertTrue(repository.complete(requestId, byteArrayOf(1)))

        viewModel.onBleFlowResumed()

        assertTrue(viewModel.uiState.zcashAwaitingQr)
        assertNull(repository.status(requestId))
    }

    @Test
    fun `successful pairing qr activates zcash account`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(
                deriver = ZcashAddressDeriver { _, network ->
                    assertEquals(ZcashNetwork.Mainnet, network)
                    "u1-test-address"
                },
            )
            viewModel.onSetType(HardwareWalletViewModel.Type.ZcashKeyHardware)
            viewModel.onSetZcashNetworkType(HardwareWalletViewModel.ZcashNetworkType.Mainnet)

            viewModel.onPairingQrScanned(
                "uview1-test|${"a".repeat(64)}|0",
            )
            runCurrent()

            val hardwareAllCall = mockingDetails(service).invocations.single {
                it.method.name == "hardwareAll"
            }
            val accountType = hardwareAllCall.arguments[0] as AccountType.ZcashHardware
            assertEquals("Hardware Wallet", hardwareAllCall.arguments[1])
            assertEquals(0L, accountType.accountIndex)
            assertFalse(accountType.isTestNet)
            assertTrue(viewModel.uiState.accountCreated)
        }

    private fun viewModel(
        deriver: ZcashAddressDeriver = ZcashAddressDeriver { _, _ -> "unused" },
    ) = HardwareWalletViewModel(
        hardwareWalletService = service,
        pairingRequestRepository = repository,
        zcashAddressDeriver = deriver,
    )
}
