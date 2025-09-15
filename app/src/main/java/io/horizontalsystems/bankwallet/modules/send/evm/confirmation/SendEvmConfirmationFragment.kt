package io.horizontalsystems.bankwallet.modules.send.evm.confirmation

import android.app.Activity
import android.os.Parcelable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.AppLogger
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.core.managers.toSignature
import io.horizontalsystems.bankwallet.core.shorten
import io.horizontalsystems.bankwallet.core.slideFromBottom
import io.horizontalsystems.bankwallet.core.stats.StatEvent
import io.horizontalsystems.bankwallet.core.stats.StatPage
import io.horizontalsystems.bankwallet.core.stats.stat
import io.horizontalsystems.bankwallet.core.toHexString
import io.horizontalsystems.bankwallet.core.utils.ModuleField
import io.horizontalsystems.bankwallet.entities.DataState
import io.horizontalsystems.bankwallet.modules.confirm.ConfirmTransactionScreen
import io.horizontalsystems.bankwallet.modules.hardwarewallet.AnimatedNFCBox
import io.horizontalsystems.bankwallet.modules.hardwarewallet.HardwareWalletNFCHandler
import io.horizontalsystems.bankwallet.modules.hardwarewallet.HardwareWalletScanButtons
import io.horizontalsystems.bankwallet.modules.hardwarewallet.HardwareWalletSignViewModel
import io.horizontalsystems.bankwallet.modules.hardwarewallet.LoadingScreen
import io.horizontalsystems.bankwallet.modules.hardwarewallet.NFCCallback
import io.horizontalsystems.bankwallet.modules.hardwarewallet.NFCCallbackType
import io.horizontalsystems.bankwallet.modules.hardwarewallet.StartNFCWriting
import io.horizontalsystems.bankwallet.modules.hardwarewallet.decodeRawTransactionSignature
import io.horizontalsystems.bankwallet.modules.hardwarewallet.toByteArray
import io.horizontalsystems.bankwallet.modules.hardwarewallet.toHex
import io.horizontalsystems.bankwallet.modules.qrscanner.QRScannerActivity
import io.horizontalsystems.bankwallet.modules.send.evm.SendEvmData
import io.horizontalsystems.bankwallet.modules.send.evm.SendEvmModule
import io.horizontalsystems.bankwallet.modules.sendevmtransaction.SendEvmTransactionView
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.core.SnackbarDuration
import io.horizontalsystems.core.helpers.HudHelper
import io.horizontalsystems.core.toHexString
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.TransactionData
import io.horizontalsystems.marketkit.models.BlockchainType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

class SendEvmConfirmationFragment : BaseComposeFragment() {

    @Composable
    override fun GetContent(navController: NavController) {
        withInput<Input>(navController) { input ->
            SendEvmConfirmationScreen(navController, input)
        }
    }

    @Parcelize
    data class Input(
        val transactionDataParcelable: SendEvmModule.TransactionDataParcelable,
        val additionalInfo: SendEvmData.AdditionalInfo?,
        val blockchainType: BlockchainType,
        val sendEntryPointDestId: Int,
        val isHardwareSigner: Boolean = false
    ) : Parcelable {
        val transactionData: TransactionData
            get() = TransactionData(
                Address(transactionDataParcelable.toAddress),
                transactionDataParcelable.value,
                transactionDataParcelable.input
            )

        constructor(
            sendData: SendEvmData,
            blockchainType: BlockchainType,
            sendEntryPointDestId: Int,
            isHardwareSigner: Boolean
        ) : this(
            SendEvmModule.TransactionDataParcelable(sendData.transactionData),
            sendData.additionalInfo,
            blockchainType,
            sendEntryPointDestId,
            isHardwareSigner
        )
    }
}

@Composable
private fun SendEvmConfirmationScreen(
    navController: NavController,
    input: SendEvmConfirmationFragment.Input
) {
    val logger = remember { AppLogger("send-evm") }

    val currentBackStackEntry = remember(navController.currentBackStackEntry) {
        navController.getBackStackEntry(R.id.sendEvmConfirmationFragment)
    }
    val viewModel = viewModel<SendEvmConfirmationViewModel>(
        viewModelStoreOwner = currentBackStackEntry,
        factory = SendEvmConfirmationViewModel.Factory(
            input.transactionData,
            input.additionalInfo,
            input.blockchainType,
            input.isHardwareSigner
        )
    )
    val uiState = viewModel.uiState
    var qrScannerFinished by remember { mutableStateOf(false) }
    val view = LocalView.current
    val context = LocalContext.current

    val vm: HardwareWalletSignViewModel = viewModel(
        factory = HardwareWalletSignViewModel.Factory(viewModel.sendTransactionService)
    )

    val state by vm.unsignedHex.collectAsState(initial = DataState.Loading)

    var isSending by remember { mutableStateOf(false) }
    var isTxLoaded by remember { mutableStateOf(false) }

    var scanToTransmit by remember { mutableStateOf(false) }
    var txData by remember { mutableStateOf<String?>(null) }
    var messageHex by remember { mutableStateOf<String?>(null) }

    var nfcWritingStarted by remember { mutableStateOf(false) }
    val nfcHandler = HardwareWalletNFCHandler(
        context,
        onSuccess = {
            scanToTransmit = true
            nfcWritingStarted = false
        },
        onError = {
            HudHelper.showErrorMessage(
                contenView = view,
                resId = R.string.HardwareWalletAuthentication_TagError,
                icon = R.drawable.icon_24_warning_2,
                iconTint = R.color.white
            )
        }
    )

    val coroutineScope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            qrScannerFinished = true
            val scannedQr = result.data?.getStringExtra(ModuleField.SCAN_ADDRESS)?: ""
            if (scannedQr.startsWith("https://app.hito.dev/eth/tx/#!")) {
                coroutineScope.launch {
                    try {
                        val txHex = scannedQr.removePrefix("https://app.hito.dev/eth/tx/#!")
                        val signature = decodeRawTransactionSignature(txHex)
                        val signatureHex = signature?.toHex()
                        isSending = true
                        viewModel.sendTransactionService.sendTransaction(signatureHex)
                        stat(page = StatPage.SendConfirmation, event = StatEvent.Send)
                        HudHelper.showSuccessMessage(view, R.string.Hud_Text_Done)
                        delay(1200)
                        navController.popBackStack(input.sendEntryPointDestId, true)
                    } catch (t: Throwable) {
                        logger.warning("failed", t)
                        if (t.message != null) {
                            HudHelper.showErrorMessage(view, t.message!!)
                        } else {
                            HudHelper.showErrorMessage(view, t.javaClass.simpleName)
                        }
                    }
                }
            } else if (scannedQr.startsWith("evm.sig:")) {
                val signatureHex = scannedQr.removePrefix("evm.sig:")
                isSending = true
                if (messageHex == null) {
                    coroutineScope.launch {
                        try {
                            viewModel.sendTransactionService.sendTransaction(signatureHex)
                            stat(page = StatPage.SendConfirmation, event = StatEvent.Send)
                            HudHelper.showSuccessMessage(view, R.string.Hud_Text_Done)
                            delay(1200)
                            navController.popBackStack(input.sendEntryPointDestId, true)
                        } catch (t: Throwable) {
                            logger.warning("failed", t)
                            if (t.message != null) {
                                HudHelper.showErrorMessage(view, t.message!!.shorten())
                            } else {
                                HudHelper.showErrorMessage(view, t.javaClass.simpleName)
                            }
                        }
                    }
                } else {
                    val signature = signatureHex.toSignature()
                    val shex = signature?.toByteArray().toHexString()
                    // TODO: handle case when signature is null
//                    signMessageViewModel?.acceptWithSignature(shex!!)
                }
            } else {
                HudHelper.showErrorMessage(view, R.string.Error)
                //TODO todo handle error
                //viewModel?.sendTransactionService?.(IOException("Signature Scan Error"))
//                signMessageViewModel?.showSignError = true
            }
        }
    }

    val ownAddress = viewModel.sendTransactionService.ownAddress()

    LaunchedEffect(Unit) { vm.loadUnsigned() }
    when (val s = state) {
        DataState.Loading -> LoadingScreen(loadingMessage = "Creating Transaction...")
        is DataState.Success -> {
            txData = s.data
            isTxLoaded = true
            viewModel.sendTransactionService.pauseSync()
            //feeModel?.pauseSync()
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

    ConfirmTransactionScreen(
        onClickBack = { navController.popBackStack() },
        onClickSettings = {
            navController.slideFromBottom(R.id.sendEvmSettingsFragment)
        },
        onClickClose = null,
        buttonsSlot = {

            var buttonEnabled by remember { mutableStateOf(true) }

            if (!viewModel.isHardwareSigner) {
                ButtonPrimaryYellow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp),
                    title = stringResource(R.string.Send_Confirmation_Send_Button),
                    onClick = {
                        logger.info("click send button")

                        coroutineScope.launch {
                            buttonEnabled = false
                            HudHelper.showInProcessMessage(view, R.string.Send_Sending, SnackbarDuration.INDEFINITE)

                            try {
                                logger.info("sending tx")
                                viewModel.send()
                                logger.info("success")
                                stat(page = StatPage.SendConfirmation, event = StatEvent.Send)

                                HudHelper.showSuccessMessage(view, R.string.Hud_Text_Done)
                                delay(1200)

                                navController.popBackStack(input.sendEntryPointDestId, true)
                            } catch (t: Throwable) {
                                logger.warning("failed", t)
                                HudHelper.showErrorMessage(view, t.javaClass.simpleName)
                            }

                            buttonEnabled = true
                        }
                    },
                    enabled = uiState.sendEnabled && buttonEnabled
                )
            } else {
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
                        StartNFCWriting(
                            nfcHandler,
                            nfcCallback,
                            onCancelClick =
                                { nfcWritingStarted = false }
                            ,
                            text =
                                "Confirm by tapping Hito Wallet"
                        )
                    } else {
                        ButtonPrimaryYellow(
                            modifier = Modifier.fillMaxWidth(),
                            title = "Send",
                            onClick = {
                                nfcWritingStarted = true // For production
                                //scanToTransmit = true // For alignment buttons
                            }
                        )
                    }
                } else {
                    HardwareWalletScanButtons(
                        onTryAgainClick = { scanToTransmit = false },
                        onContinueClick = {
                            val intent = QRScannerActivity.getScanQrIntent(context, showPasteButton = false)
                            launcher.launch(intent)
                        }
                    )
                }
            }
        }
    ) {
        SendEvmTransactionView(
            navController,
            uiState.sectionViewItems,
            uiState.cautions,
            uiState.transactionFields,
            uiState.networkFee,
            StatPage.SendConfirmation,
            input.isHardwareSigner,
            scanToTransmit,
        )
    }
}

