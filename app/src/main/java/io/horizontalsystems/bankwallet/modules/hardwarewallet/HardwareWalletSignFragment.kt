package io.horizontalsystems.bankwallet.modules.hardwarewallet

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.AppLogger
import io.horizontalsystems.bankwallet.core.managers.toSignature
import io.horizontalsystems.bankwallet.core.toHexString
import io.horizontalsystems.bankwallet.core.utils.ModuleField
import io.horizontalsystems.bankwallet.entities.DataState
import io.horizontalsystems.bankwallet.modules.evmfee.eip1559.Eip1559FeeSettingsViewModel
import io.horizontalsystems.bankwallet.modules.send.evm.confirmation.SendEvmConfirmationViewModel
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.ethereumkit.core.hexStringToByteArray
import io.horizontalsystems.ethereumkit.models.Signature
import io.horizontalsystems.ethereumkit.spv.core.toBigInteger
import io.horizontalsystems.ethereumkit.spv.core.toInt
import io.horizontalsystems.ethereumkit.spv.rlp.RLP
import io.horizontalsystems.ethereumkit.spv.rlp.RLPList
import io.horizontalsystems.core.helpers.HudHelper

@Preview
@Composable
fun Preview_HardwareWalletSignScanFragment() {
    ComposeAppTheme {
        HardwareWalletSignScanFragment(Modifier
            .padding(horizontal = 16.dp))
    }
}

@Composable
fun HardwareWalletSendCautions(modifier: Modifier) {
    val fontSize = 16.sp
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Please check the transaction data on your device",
            style = ComposeAppTheme.typography.title1,
            color = ComposeAppTheme.colors.leah,
            fontSize = 1.2 * fontSize,
            textAlign = TextAlign.Center,
            modifier = modifier
        )
//        Spacer(Modifier.height(16.dp))
        Text(
            text = "After checking your transaction data:",
            style = ComposeAppTheme.typography.body,
            color = ComposeAppTheme.colors.leah,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        DottedList(
            modifier.padding(horizontal = 8.dp),
            listOf(
                "Press 'Sign' on your hardware device",
                "Press 'Continue' on your phone to scan the QR code appeared"
            )
        )
    }
}

@Composable
fun HardwareWalletSignScanFragment(
    modifier: Modifier = Modifier,
    onContinueClick: () -> Unit = {},
    onTryAgainClick: () -> Unit = {}
) {
    ComposeAppTheme {
        Column(
            modifier = modifier
                .fillMaxWidth()
//                .wrapContentHeight()
                .background(ComposeAppTheme.colors.tyler),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HardwareWalletSendCautions(modifier = modifier)
            HardwareWalletScanButtons(
                onContinueClick = onContinueClick,
                onTryAgainClick = onTryAgainClick
            )
        }
    }
}

@Composable
fun HardwareWalletSignFragment(
    modifier: Modifier,
    ownAddress: String,
    sendViewModel: SendEvmConfirmationViewModel? = null,
    feeModel: Eip1559FeeSettingsViewModel? = null,
) {

    val logger = AppLogger("sign-evm-hardware")
    var qrScannerFinished by remember { mutableStateOf(false) }

    val vm: HardwareWalletSignViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = HardwareWalletSignViewModel.Factory(sendViewModel?.sendTransactionService)
    )

    val state by vm.unsignedHex.collectAsState(initial = DataState.Loading)

    var isSending by remember { mutableStateOf(false) }
    var isTxLoaded by remember { mutableStateOf(false) }

    var scanToTransmit by remember { mutableStateOf(false) }
    var txData by remember { mutableStateOf<String?>(null) }
    var messageHex by remember { mutableStateOf<String?>(null) }

//    LaunchedEffect(signMessageViewModel) {
//        if (signMessageViewModel != null) {
//            messageHex = signMessageViewModel?.message?.data?.toByteArray().toHexString()
//        } else {
//            messageHex = null
//        }
//    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            qrScannerFinished = true
            val scannedQr = result.data?.getStringExtra(ModuleField.SCAN_ADDRESS)?: ""
            if (scannedQr.startsWith("https://app.hito.dev/eth/tx/#!")) {
                val txHex = scannedQr.removePrefix("https://app.hito.dev/eth/tx/#!")
                val signature = decodeRawTransactionSignature(txHex)
                val signatureHex = signature?.toHex()
                isSending = true
                sendViewModel?.sendAsync(signatureHex)
            } else if (scannedQr.startsWith("evm.sig:")) {
                val signatureHex = scannedQr.removePrefix("evm.sig:")
                isSending = true
                if (messageHex == null) {
                    sendViewModel?.sendAsync(signatureHex)
                } else {
                    val signature = signatureHex.toSignature()
                    val shex = signature?.toByteArray().toHexString()
                    // TODO: handle case when signature is null
//                    signMessageViewModel?.acceptWithSignature(shex!!)
                }
            } else {
                //TODO todo handle error
//                sendViewModel?.service?.setFailed(IOException("Signature Scan Error"))
//                signMessageViewModel?.showSignError = true
            }
        }
    }

    // context
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadUnsigned() }
    when (val s = state) {
        DataState.Loading -> LoadingScreen(loadingMessage = "Creating Transaction...")
        is DataState.Success -> {
            txData = s.data
            isTxLoaded = true
            sendViewModel?.sendTransactionService?.pauseSync()
            feeModel?.pauseSync()
        }
        is DataState.Error -> {
            isTxLoaded = false
            HudHelper.showErrorMessage(
                contenView = LocalView.current,
                resId = R.string.HardwareWallet_TransactionError,
                icon = R.drawable.icon_24_warning_2,
                iconTint = R.color.white
            )
        }
    }
    val view = LocalView.current
    var nfcWritingStarted by remember { mutableStateOf(false) }
    val nfcHandler = HardwareWalletNFCHandler(context, onSuccess = {
        scanToTransmit = true
        nfcWritingStarted = false
    }, onError = {        HudHelper.showErrorMessage(
        contenView = view,
        resId = R.string.HardwareWalletAuthentication_TagError,
        icon = R.drawable.icon_24_warning_2,
        iconTint = R.color.white
    )})
    if (isTxLoaded && !isSending) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))

            if (!scanToTransmit) {
                if (nfcWritingStarted) {
                    val messageText = if (messageHex != null) {
                        "evm.msg:$ownAddress:$messageHex"
                    } else {
                        "evm.sign:$ownAddress:$txData"
                    }
                    val nfcCallback = NFCCallback(
                        type = NFCCallbackType.ETH_SEND,
                        messageText = messageText
                    )
                    StartNFCWriting(nfcHandler, nfcCallback)

                    AnimatedNFCBox(
                        onCancelClick = { nfcWritingStarted = false },
                        text = "Confirm by tapping Hito Wallet"
                    )
                } else {
                    ButtonPrimaryYellow(
                        modifier = Modifier.fillMaxWidth(),
                        title = "Send",
                        onClick = { nfcWritingStarted = true }
                    )
                }
            } else {
            }
        }
    }
}

fun Signature.toHex(): String {
    return "0x" + r.toByteArrayToHex() + s.toByteArrayToHex() + v.toHex()
}

fun Signature.toByteArray(): ByteArray {
    return r + s + v.toByte()
}

fun ByteArray.toByteArrayToHex(): String {
    return joinToString(separator = "") { byte -> "%02x".format(byte) }
}

fun Int.toHex(): String {
    return this.toString(16)
}

// TODO: move to EvmKitManager
fun decodeRawTransactionSignature(txhex: String): Signature? {
    val tx = txhex.removePrefix("0x").hexStringToByteArray()
    val rlp =
        if (tx[0] == 0x02.toByte()) {
            RLP.decode2(tx.copyOfRange(1, tx.size)).get(0) as RLPList
        } else {
            RLP.decode2(tx).get(0) as RLPList
        }
    if (rlp.size > 3) {
        return Signature(
            rlp.get(rlp.size - 3).toInt(),
            rlp.get(rlp.size - 2).toBigInteger().toByteArray(),
            rlp.get(rlp.size - 1).toBigInteger().toByteArray()
        )
    } else {
        return null
    }
}




