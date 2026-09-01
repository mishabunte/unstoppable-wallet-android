package io.horizontalsystems.bankwallet.modules.activatetoken

import android.app.Activity
import android.os.Parcelable
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.core.alternativeImageUrl
import io.horizontalsystems.bankwallet.core.badge
import io.horizontalsystems.bankwallet.core.iconPlaceholder
import io.horizontalsystems.bankwallet.core.imageUrl
import io.horizontalsystems.bankwallet.core.setNavigationResultX
import io.horizontalsystems.bankwallet.core.utils.ModuleField
import io.horizontalsystems.bankwallet.entities.Wallet
import io.horizontalsystems.bankwallet.modules.confirm.ConfirmTransactionScreen
import io.horizontalsystems.bankwallet.modules.hardwarewallet.HardwareWalletNFCHandler
import io.horizontalsystems.bankwallet.modules.hardwarewallet.NFCCallback
import io.horizontalsystems.bankwallet.modules.hardwarewallet.NFCCallbackType
import io.horizontalsystems.bankwallet.modules.hardwarewallet.SendTransactionHardwareState
import io.horizontalsystems.bankwallet.modules.hardwarewallet.StartNFCWriting
import io.horizontalsystems.bankwallet.modules.hardwarewallet.scanui.HardwareWalletScanButtons
import io.horizontalsystems.bankwallet.modules.hardwarewallet.scanui.HardwareWalletSendCautions
import io.horizontalsystems.bankwallet.modules.multiswap.ui.DataFieldFee
import io.horizontalsystems.bankwallet.modules.qrscanner.QRScannerActivity
import io.horizontalsystems.bankwallet.modules.receive.ActivateTokenError
import io.horizontalsystems.bankwallet.modules.receive.ActivateTokenViewModel
import io.horizontalsystems.bankwallet.modules.send.HardwareSendError
import io.horizontalsystems.bankwallet.modules.send.HardwareSendSuccess
import io.horizontalsystems.bankwallet.modules.send.SendButton
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryDefault
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.bankwallet.ui.compose.components.HFillSpacer
import io.horizontalsystems.bankwallet.ui.compose.components.HSpacer
import io.horizontalsystems.bankwallet.ui.compose.components.HsImageCircle
import io.horizontalsystems.bankwallet.ui.compose.components.TextImportantError
import io.horizontalsystems.bankwallet.ui.compose.components.VSpacer
import io.horizontalsystems.bankwallet.ui.compose.components.caption_grey
import io.horizontalsystems.bankwallet.ui.compose.components.cell.CellUniversal
import io.horizontalsystems.bankwallet.ui.compose.components.cell.SectionUniversalLawrence
import io.horizontalsystems.bankwallet.ui.compose.components.subhead1_leah
import io.horizontalsystems.bankwallet.ui.compose.components.subhead2_leah
import io.horizontalsystems.core.SnackbarDuration
import io.horizontalsystems.core.helpers.HudHelper
import io.horizontalsystems.marketkit.models.BlockchainType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

class ActivateTokenFragment : BaseComposeFragment() {
    @Composable
    override fun GetContent(navController: NavController) {
        withInput<Wallet>(navController) {
            ActivateTokenScreen(navController, it)
        }
    }

    @Parcelize
    data class Result(val activated: Boolean) : Parcelable
}


@Composable
fun ActivateTokenScreen(
    navController: NavController,
    wallet: Wallet,
) {
    val viewModel = viewModel<ActivateTokenViewModel>(factory = ActivateTokenViewModel.Factory(wallet))

    val unsignedTxState by viewModel.unsignedTxState.collectAsState()

    val uiState = viewModel.uiState
    val token = uiState.token

    val context = LocalContext.current
    val view = LocalView.current

    val nfcHandler = HardwareWalletNFCHandler(context,
        onSuccess = {
            viewModel.onNFCWritingSuccess()
        },
        onError = { _ ->
            HudHelper.showErrorMessage(
                contenView = view,
                resId = R.string.HardwareWalletAuthentication_TagError,
                icon = R.drawable.icon_24_warning_2,
                iconTint = R.color.white
            )
        }
    )

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedQr = result.data?.getStringExtra(ModuleField.SCAN_ADDRESS)?: ""
            viewModel.onScannedQR(scannedQr)
        }
    }

    LaunchedEffect(unsignedTxState) {
        if (unsignedTxState is SendTransactionHardwareState.Sent) {
            delay(1200)
            navController.popBackStack()
        }
    }

    ConfirmTransactionScreen(
        onClickBack = null,
        onClickClose = navController::popBackStack,
        onClickSettings = null,
        buttonsSlot = {
            val coroutineScope = rememberCoroutineScope()
            var buttonEnabled by remember { mutableStateOf(true) }

            when (unsignedTxState) {
                is SendTransactionHardwareState.ReadyToLoad -> {
                    ButtonPrimaryYellow(
                        modifier = Modifier.fillMaxWidth(),
                        title = stringResource(R.string.Button_Activate),
                        onClick = viewModel::getUnsignedTransaction,
                        enabled = uiState.activateEnabled && buttonEnabled
                    )
                    VSpacer(16.dp)
                    ButtonPrimaryDefault(
                        modifier = Modifier.fillMaxWidth(),
                        title = stringResource(R.string.Button_Cancel),
                        onClick = navController::popBackStack
                    )
                }

                is SendTransactionHardwareState.ScanToTransmit -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        HardwareWalletScanButtons(
                            onTryAgainClick = {
                                viewModel.resetHardwareWalletState()
                            },
                            onContinueClick = {
                                val intent = QRScannerActivity.getScanQrIntent(
                                    context,
                                    showPasteButton = false
                                )
                                launcher.launch(intent)
                            }
                        )
                    }
                }

                is SendTransactionHardwareState.Error -> {
                    ButtonPrimaryYellow(
                        modifier = Modifier
                            .fillMaxWidth(),
                        title = stringResource(R.string.Button_TryAgain),
                        onClick = viewModel::resetHardwareWalletState
                    )
                }

                null -> {
                    ButtonPrimaryYellow(
                        modifier = Modifier.fillMaxWidth(),
                        title = stringResource(R.string.Button_Activate),
                        onClick = {
                            coroutineScope.launch {
                                buttonEnabled = false
                                HudHelper.showInProcessMessage(view, R.string.Activate_Activating, SnackbarDuration.INDEFINITE)

                                val result = try {
                                    viewModel.activate()

                                    HudHelper.showSuccessMessage(view, R.string.Hud_Text_Done)
                                    ActivateTokenFragment.Result(true)
                                } catch (t: Throwable) {
                                    HudHelper.showErrorMessage(view, t.javaClass.simpleName)
                                    ActivateTokenFragment.Result(false)
                                }

                                buttonEnabled = true
                                navController.setNavigationResultX(result)
                                navController.popBackStack()
                            }
                        },
                        enabled = uiState.activateEnabled && buttonEnabled
                    )
                    VSpacer(16.dp)
                    ButtonPrimaryDefault(
                        modifier = Modifier.fillMaxWidth(),
                        title = stringResource(R.string.Button_Cancel),
                        onClick = navController::popBackStack
                    )
                }

                is SendTransactionHardwareState.NFCWritingStarted -> {
                    val payloadPrefix = "stellar.sign:"
                    val nfcCallback = NFCCallback(type= NFCCallbackType.SOLANA_SEND, messageText=payloadPrefix + (unsignedTxState as SendTransactionHardwareState.NFCWritingStarted).unsignedTxHex)
                    Log.d("AAA", "ActivateTokenScreen: StartNFCWriting, unsignedTxHex=${(unsignedTxState as SendTransactionHardwareState.NFCWritingStarted).unsignedTxHex}")
                    StartNFCWriting(nfcHandler,
                        nfcCallback,
                        onCancelClick = {
                            viewModel.onNFCWritingSuccess()
                        },
                        text = "Confirm by tapping Hito Wallet"
                    )
                }

                else -> {}
            }
        }
    ) {
        SectionUniversalLawrence {
            CellUniversal(borderTop = false) {
                HsImageCircle(
                    modifier = Modifier.size(32.dp),
                    url = token.coin.imageUrl,
                    alternativeUrl = token.coin.alternativeImageUrl,
                    placeholder = token.iconPlaceholder
                )
                HSpacer(width = 16.dp)
                Column {
                    subhead2_leah(text = stringResource(R.string.Activate_YouActivate))
                    VSpacer(height = 1.dp)
                    caption_grey(text = token.badge ?: stringResource(id = R.string.CoinPlatforms_Native))
                }
                HFillSpacer(minWidth = 16.dp)
                Column(horizontalAlignment = Alignment.End) {
                    subhead1_leah(
                        text = token.coin.code,
                    )
                }
            }
        }

        VSpacer(height = 16.dp)
        SectionUniversalLawrence {
            DataFieldFee(
                navController,
                uiState.feeCoinValue?.getFormattedFull() ?: "---",
                uiState.feeFiatValue?.getFormattedFull() ?: "---"
            )
        }
        VSpacer(height = 16.dp)
        when (unsignedTxState) {
            is SendTransactionHardwareState.Loading, SendTransactionHardwareState.Sending -> {
                VSpacer(height = 64.dp)
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(224.dp)
                        .align(Alignment.CenterHorizontally),
                    color = ComposeAppTheme.colors.grey
                )
            }
            is SendTransactionHardwareState.Sent -> {
                HardwareSendSuccess()
            }
            is SendTransactionHardwareState.Error -> {
                HardwareSendError((unsignedTxState as SendTransactionHardwareState.Error).caution)
            }
            is SendTransactionHardwareState.ScanToTransmit -> {
                HardwareWalletSendCautions()
            }

            else -> {}
        }

        uiState.error?.let { error ->
            VSpacer(16.dp)
            val modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)

            when (error) {
                is ActivateTokenError.AlreadyActive -> {
                    TextImportantError(
                        modifier = modifier,
                        text = stringResource(R.string.Activate_AlreadyActive_Description),
                        title = stringResource(R.string.Activate_AlreadyActive_Title),
                        icon = R.drawable.ic_attention_20
                    )
                }

                is ActivateTokenError.NullAdapter -> {
                    TextImportantError(
                        modifier = modifier,
                        text = stringResource(R.string.Error_ParameterNotSet),
                        title = null,
                        icon = null
                    )
                }

                is ActivateTokenError.InsufficientBalance -> {
                    TextImportantError(
                        modifier = modifier,
                        title = stringResource(R.string.Activate_InsufficientBalance_Title),
                        text = stringResource(R.string.Activate_InsufficientBalance_Description),
                        icon = R.drawable.ic_attention_20
                    )
                }
            }
        }
    }
}