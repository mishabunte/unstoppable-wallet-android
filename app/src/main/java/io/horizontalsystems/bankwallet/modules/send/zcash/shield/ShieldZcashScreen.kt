package io.horizontalsystems.bankwallet.modules.send.zcash.shield

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.modules.amount.AmountInputType
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleOperation
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletSelectDeviceInput
import io.horizontalsystems.bankwallet.modules.send.SendConfirmationScreen
import io.horizontalsystems.bankwallet.modules.send.Transport

@Composable
fun ShieldZcashScreen(
    navController: NavController,
    viewModel: ShieldZcashViewModel,
    sendEntryPointDestId: Int
) {
    var confirmationData by remember { mutableStateOf(viewModel.getConfirmationData()) }
    var refresh by remember { mutableStateOf(false) }
    val unsignedTxState by viewModel.unsignedTxState.collectAsState()

    LifecycleResumeEffect(Unit) {
        viewModel.onBleFlowResumed()
        if (refresh) {
            confirmationData = viewModel.getConfirmationData()
        }

        onPauseOrDispose {
            refresh = true
        }
    }

    SendConfirmationScreen(
        navController = navController,
        coinMaxAllowedDecimals = viewModel.coinMaxAllowedDecimals,
        feeCoinMaxAllowedDecimals = viewModel.coinMaxAllowedDecimals,
        amountInputType = AmountInputType.COIN,
        rate = viewModel.coinRate,
        feeCoinRate = viewModel.coinRate,
        sendResult = viewModel.sendResult,
        blockchainType = viewModel.blockchainType,
        coin = confirmationData.coin,
        feeCoin = confirmationData.feeCoin,
        amount = confirmationData.amount,
        address = null,
        contact = confirmationData.contact,
        fee = viewModel.fee,
        lockTimeInterval = confirmationData.lockTimeInterval,
        memo = confirmationData.memo,
        rbfEnabled = confirmationData.rbfEnabled,
        onClickSend = viewModel::onClickSend,
        onScannedQR = viewModel::onScannedQR,
        unsignedTxState = unsignedTxState,
        onHardwareSignerSendClick = { transport ->
            when (transport) {
                Transport.NFC -> viewModel.getUnsignedTransaction()
                Transport.BLE -> viewModel.createBleSigningRequest { requestId ->
                    navController.slideFromRight(
                        R.id.hardwareWalletSelectDeviceFragment,
                        HardwareWalletSelectDeviceInput(
                            operation = HardwareWalletBleOperation.SignTransaction,
                            requestId = requestId,
                            returnDestinationId = R.id.shieldZcash,
                        ),
                    )
                }
            }
        },
        onHardwareSignerNFCSuccess = viewModel::onNFCWritingSuccess,
        onHardwareSignerCancel = viewModel::resetHardwareWalletState,
        sendEntryPointDestId = sendEntryPointDestId,
        title = stringResource(R.string.Balance_Zcash_UnshieldedBalance_Shield)
    )

}
