package io.horizontalsystems.bankwallet.modules.send.zcash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.modules.amount.AmountInputModeViewModel
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleOperation
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletSelectDeviceInput
import io.horizontalsystems.bankwallet.modules.send.SendConfirmationScreen
import io.horizontalsystems.bankwallet.modules.send.Transport

@Composable
fun SendZCashConfirmationScreen(
    navController: NavController,
    sendViewModel: SendZCashViewModel,
    amountInputModeViewModel: AmountInputModeViewModel,
    sendEntryPointDestId: Int
) {
    var confirmationData by remember { mutableStateOf(sendViewModel.getConfirmationData()) }
    var refresh by remember { mutableStateOf(false) }

    val unsignedTxState by sendViewModel.unsignedTxState.collectAsState()

    LifecycleResumeEffect(Unit) {
        sendViewModel.onBleFlowResumed()
        if (refresh) {
            confirmationData = sendViewModel.getConfirmationData()
        }

        onPauseOrDispose {
            refresh = true
        }
    }

    SendConfirmationScreen(
        navController = navController,
        coinMaxAllowedDecimals = sendViewModel.coinMaxAllowedDecimals,
        feeCoinMaxAllowedDecimals = sendViewModel.coinMaxAllowedDecimals,
        amountInputType = amountInputModeViewModel.inputType,
        rate = sendViewModel.coinRate,
        feeCoinRate = sendViewModel.coinRate,
        sendResult = sendViewModel.sendResult,
        blockchainType = sendViewModel.blockchainType,
        coin = confirmationData.coin,
        feeCoin = confirmationData.feeCoin,
        amount = confirmationData.amount,
        address = confirmationData.address,
        contact = confirmationData.contact,
        fee = confirmationData.fee,
        lockTimeInterval = confirmationData.lockTimeInterval,
        memo = confirmationData.memo,
        rbfEnabled = confirmationData.rbfEnabled,
        onClickSend = sendViewModel::onClickSend,
        onScannedQR = sendViewModel::onScannedQR,
        unsignedTxState = unsignedTxState,
        onHardwareSignerSendClick = { transport ->
            when (transport) {
                Transport.NFC -> sendViewModel.getUnsignedTransaction(confirmationData.amount)
                Transport.BLE -> sendViewModel.createBleSigningRequest(
                    amount = confirmationData.amount,
                    onCreated = { requestId ->
                        navController.slideFromRight(
                            R.id.hardwareWalletSelectDeviceFragment,
                            HardwareWalletSelectDeviceInput(
                                HardwareWalletBleOperation.SignTransaction,
                                requestId,
                            ),
                        )
                    },
                )
            }
        },
        onHardwareSignerNFCSuccess = sendViewModel::onNFCWritingSuccess,
        onHardwareSignerCancel = sendViewModel::resetHardwareWalletState,
        sendEntryPointDestId = sendEntryPointDestId
    )
}
