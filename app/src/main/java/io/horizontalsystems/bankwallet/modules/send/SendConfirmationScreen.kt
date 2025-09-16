package io.horizontalsystems.bankwallet.modules.send

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.HSCaution
import io.horizontalsystems.bankwallet.core.stats.StatEntity
import io.horizontalsystems.bankwallet.core.stats.StatEvent
import io.horizontalsystems.bankwallet.core.stats.StatPage
import io.horizontalsystems.bankwallet.core.stats.StatSection
import io.horizontalsystems.bankwallet.core.stats.stat
import io.horizontalsystems.bankwallet.core.utils.ModuleField
import io.horizontalsystems.bankwallet.entities.Address
import io.horizontalsystems.bankwallet.entities.CurrencyValue
import io.horizontalsystems.bankwallet.modules.amount.AmountInputType
import io.horizontalsystems.bankwallet.modules.contacts.model.Contact
import io.horizontalsystems.bankwallet.modules.fee.HSFeeRaw
import io.horizontalsystems.bankwallet.modules.hardwarewallet.HardwareWalletNFCHandler
import io.horizontalsystems.bankwallet.modules.hardwarewallet.HardwareWalletScanButtons
import io.horizontalsystems.bankwallet.modules.hardwarewallet.HardwareWalletSendCautions
import io.horizontalsystems.bankwallet.modules.hardwarewallet.NFCCallback
import io.horizontalsystems.bankwallet.modules.hardwarewallet.NFCCallbackType
import io.horizontalsystems.bankwallet.modules.hardwarewallet.StartNFCWriting
import io.horizontalsystems.bankwallet.modules.hodler.HSHodler
import io.horizontalsystems.bankwallet.modules.qrscanner.QRScannerActivity
import io.horizontalsystems.bankwallet.modules.send.solana.SendSolanaHardwareState
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.TranslatableString
import io.horizontalsystems.bankwallet.ui.compose.components.AppBar
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.bankwallet.ui.compose.components.CellUniversalLawrenceSection
import io.horizontalsystems.bankwallet.ui.compose.components.CoinImage
import io.horizontalsystems.bankwallet.ui.compose.components.HsBackButton
import io.horizontalsystems.bankwallet.ui.compose.components.RowUniversal
import io.horizontalsystems.bankwallet.ui.compose.components.SectionTitleCell
import io.horizontalsystems.bankwallet.ui.compose.components.TransactionInfoAddressCell
import io.horizontalsystems.bankwallet.ui.compose.components.TransactionInfoContactCell
import io.horizontalsystems.bankwallet.ui.compose.components.TransactionInfoRbfCell
import io.horizontalsystems.bankwallet.ui.compose.components.VSpacer
import io.horizontalsystems.bankwallet.ui.compose.components.cell.SectionUniversalLawrence
import io.horizontalsystems.bankwallet.ui.compose.components.subhead1Italic_leah
import io.horizontalsystems.bankwallet.ui.compose.components.subhead1_grey
import io.horizontalsystems.bankwallet.ui.compose.components.subhead2_grey
import io.horizontalsystems.bankwallet.ui.compose.components.subhead2_leah
import io.horizontalsystems.core.SnackbarDuration
import io.horizontalsystems.core.helpers.HudHelper
import io.horizontalsystems.hodler.LockTimeInterval
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Coin
import kotlinx.coroutines.delay
import java.math.BigDecimal

@Composable
fun SendConfirmationScreen(
    navController: NavController,
    coinMaxAllowedDecimals: Int,
    feeCoinMaxAllowedDecimals: Int,
    amountInputType: AmountInputType,
    rate: CurrencyValue?,
    feeCoinRate: CurrencyValue?,
    sendResult: SendResult?,
    blockchainType: BlockchainType,
    coin: Coin,
    feeCoin: Coin,
    amount: BigDecimal,
    address: Address?,
    contact: Contact?,
    fee: BigDecimal?,
    lockTimeInterval: LockTimeInterval?,
    memo: String?,
    rbfEnabled: Boolean?,
    onClickSend: () -> Unit,
    sendEntryPointDestId: Int,
    title: String? = null,
    onScannedQR: (String) -> Unit = { _ -> },
    unsignedTxState: SendSolanaHardwareState? = null,
    onHardwareSignerSendClick: () -> Unit = { },
    onHardwareSignerNFCSuccess: () -> Unit = { },
    onHardwareSignerCancel: () -> Unit = { }
) {
    val closeUntilDestId = if (sendEntryPointDestId == 0) {
        R.id.sendXFragment
    } else {
        sendEntryPointDestId
    }
    val view = LocalView.current
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedQr = result.data?.getStringExtra(ModuleField.SCAN_ADDRESS)?: ""
            if (scannedQr.startsWith("https://app.hito.dev/eth/tx/#!")) {
                val txHex = scannedQr.removePrefix("https://app.hito.dev/eth/tx/#!")
                Log.d("SendConfirmationScreen", "QR Code scanned: $scannedQr")
                onScannedQR(txHex)
                onClickSend()
            }
        }
    }
    val nfcHandler = HardwareWalletNFCHandler(context,
        onSuccess = {
            onHardwareSignerNFCSuccess()
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

    when (sendResult) {
        SendResult.Sending -> {
            HudHelper.showInProcessMessage(
                view,
                R.string.Send_Sending,
                SnackbarDuration.INDEFINITE
            )
        }

        is SendResult.Sent -> {
            HudHelper.showSuccessMessage(
                view,
                R.string.Send_Success,
                SnackbarDuration.LONG
            )
        }

        is SendResult.Failed -> {
            HudHelper.showErrorMessage(view, sendResult.caution.getDescription() ?: sendResult.caution.getString())
        }

        null -> Unit
    }

    LaunchedEffect(sendResult) {
        if (sendResult is SendResult.Sent) {
            delay(1200)
            navController.popBackStack(closeUntilDestId, true)
        }
    }

    LifecycleEventEffect(event = Lifecycle.Event.ON_RESUME) {
        if (sendResult is SendResult.Sent) {
            navController.popBackStack(closeUntilDestId, true)
        }
    }

    Column(Modifier.background(color = ComposeAppTheme.colors.tyler)) {
        AppBar(
            title = title ?: stringResource(R.string.Send_Confirmation_Title),
            navigationIcon = {
                HsBackButton(onClick = { navController.popBackStack() })
            },
            menuItems = listOf()
        )
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 106.dp)
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                val topSectionItems = buildList<@Composable () -> Unit> {
                    add {
                        SectionTitleCell(
                            stringResource(R.string.Send_Confirmation_YouSend),
                            coin.name,
                            R.drawable.ic_arrow_up_right_12
                        )
                    }
                    add {
                        val coinAmount = App.numberFormatter.formatCoinFull(
                            amount,
                            coin.code,
                            coinMaxAllowedDecimals
                        )

                        val currencyAmount = rate?.let { rate ->
                            rate.copy(value = amount.times(rate.value))
                                .getFormattedFull()
                        }

                        ConfirmAmountCell(currencyAmount, coinAmount, coin)
                    }
                    address?.let {
                        add {
                            TransactionInfoAddressCell(
                                title = stringResource(R.string.Send_Confirmation_To),
                                value = address.hex,
                                showAdd = contact == null,
                                blockchainType = blockchainType,
                                navController = navController,
                                onCopy = {
                                    stat(
                                        page = StatPage.SendConfirmation,
                                        section = StatSection.AddressTo,
                                        event = StatEvent.Copy(StatEntity.Address)
                                    )
                                },
                                onAddToExisting = {
                                    stat(
                                        page = StatPage.SendConfirmation,
                                        section = StatSection.AddressTo,
                                        event = StatEvent.Open(StatPage.ContactAddToExisting)
                                    )
                                },
                                onAddToNew = {
                                    stat(
                                        page = StatPage.SendConfirmation,
                                        section = StatSection.AddressTo,
                                        event = StatEvent.Open(StatPage.ContactNew)
                                    )
                                }
                            )
                        }
                    }
                    contact?.let {
                        add {
                            TransactionInfoContactCell(name = contact.name)
                        }
                    }
                    if (lockTimeInterval != null) {
                        add {
                            HSHodler(lockTimeInterval = lockTimeInterval)
                        }
                    }

                    if (rbfEnabled == false) {
                        add {
                            TransactionInfoRbfCell(rbfEnabled)
                        }
                    }
                }

                CellUniversalLawrenceSection(topSectionItems)

                Spacer(modifier = Modifier.height(28.dp))

                val bottomSectionItems = buildList<@Composable () -> Unit> {
                    add {
                        HSFeeRaw(
                            coinCode = feeCoin.code,
                            coinDecimal = feeCoinMaxAllowedDecimals,
                            fee = fee,
                            amountInputType = amountInputType,
                            rate = feeCoinRate,
                            navController = navController
                        )
                    }
                    if (!memo.isNullOrBlank()) {
                        add {
                            MemoCell(memo)
                        }
                    }
                }

                CellUniversalLawrenceSection(bottomSectionItems)

                VSpacer(height = 16.dp)

                // Hardware wallet signing section, skipped if not hardware account
                when (unsignedTxState) {
                    is SendSolanaHardwareState.Loading, SendSolanaHardwareState.Sending -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(112.dp)
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 64.dp, bottom = 8.dp),
                            color = ComposeAppTheme.colors.grey
                        )
                    }

                    is SendSolanaHardwareState.NFCWritingStarted -> {
                        val messageText = "solana.sign:0x${unsignedTxState.unsignedTxHex}"
                        val nfcCallback = NFCCallback(type= NFCCallbackType.SOLANA_SEND, messageText=messageText)
                        StartNFCWriting(nfcHandler,
                            nfcCallback,
                            onCancelClick = {
                                onHardwareSignerCancel()
                            },
                            text = "Confirm by tapping Hito Wallet"
                        )
                    }

                    is SendSolanaHardwareState.Error -> {
                        HardwareSendError(unsignedTxState.caution)
                    }

                    is SendSolanaHardwareState.Sent -> {
                        HardwareSendSuccess()
                    }

                    is SendSolanaHardwareState.ScanToTransmit -> {
                        HardwareWalletSendCautions(
                            Modifier
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        )
                    }
                    else -> {}
                }
            }

            // Hardware wallet signing section, skipped if not hardware account
            when (unsignedTxState) {
                is SendSolanaHardwareState.ReadyToLoad -> {
                    SendButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, bottom = 48.dp),
                        sendResult = sendResult,
                        onClickSend = onHardwareSignerSendClick
                    )
                }
                is SendSolanaHardwareState.ScanToTransmit -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, bottom = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        HardwareWalletScanButtons(
                            onTryAgainClick = {
                                onHardwareSignerCancel()
                            },
                            onContinueClick = {
                                val intent = QRScannerActivity.getScanQrIntent(context, showPasteButton = false)
                                launcher.launch(intent)
                            }
                        )
                    }
                }
                is SendSolanaHardwareState.Error -> {
                    ButtonPrimaryYellow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, bottom = 48.dp),
                        title = stringResource(R.string.Button_TryAgain),
                        onClick = onHardwareSignerCancel
                    )
                }
                null -> {
                    SendButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, bottom = 48.dp),
                        sendResult = sendResult,
                        onClickSend = onClickSend
                    )
                }
                else -> {}
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview_HardwareSendError() {
    ComposeAppTheme {
        val caution = HSCaution(
            s = TranslatableString.ResString(R.string.LocalBackup_DontForgetPasswordWarning),
            type = HSCaution.Type.Error,
        )
        HardwareSendError(caution)
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview_HardwareSendSuccess() {
    ComposeAppTheme {
        HardwareSendSuccess()
    }
}

@Composable
private fun HardwareSendSuccess() {
    SectionUniversalLawrence {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    modifier = Modifier
                        .size(64.dp)
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp),
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = ComposeAppTheme.colors.remus,
                )
                Text(
                    text = "Transaction sent",
                    style = ComposeAppTheme.typography.title3,
                    color = ComposeAppTheme.colors.leah,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun HardwareSendError(caution: HSCaution) {
    SectionUniversalLawrence {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    modifier = Modifier
                        .size(64.dp)
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp),
                    painter = androidx.compose.ui.res.painterResource(R.drawable.icon_24_warning_2),
                    contentDescription = null,
                    tint = ComposeAppTheme.colors.lucian,
                )
                Text(
                    text = "Error occurred:",
                    style = ComposeAppTheme.typography.headline1,
                    color = ComposeAppTheme.colors.leah,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp)
                )
                Text(
                    text = caution.getDescription() ?: caution.getString(),
                    style = ComposeAppTheme.typography.headline2,
                    color = ComposeAppTheme.colors.grey,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Justify,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
fun SendButton(modifier: Modifier, sendResult: SendResult?, onClickSend: () -> Unit) {
    when (sendResult) {
        SendResult.Sending -> {
            ButtonPrimaryYellow(
                modifier = modifier,
                title = stringResource(R.string.Send_Sending),
                onClick = { },
                enabled = false
            )
        }

        is SendResult.Sent -> {
            ButtonPrimaryYellow(
                modifier = modifier,
                title = stringResource(R.string.Send_Success),
                onClick = { },
                enabled = false
            )
        }

        else -> {
            ButtonPrimaryYellow(
                modifier = modifier,
                title = stringResource(R.string.Send_Confirmation_Send_Button),
                onClick = onClickSend,
                enabled = true
            )
        }
    }
}

@Composable
fun ConfirmAmountCell(fiatAmount: String?, coinAmount: String, coin: Coin) {
    RowUniversal(
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        CoinImage(
            coin = coin,
            modifier = Modifier.size(32.dp)
        )
        subhead2_leah(
            modifier = Modifier.padding(start = 16.dp),
            text = coinAmount,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.weight(1f))
        subhead1_grey(text = fiatAmount ?: "")
    }
}

@Composable
fun MemoCell(value: String) {
    RowUniversal(
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        subhead2_grey(
            modifier = Modifier.padding(end = 16.dp),
            text = stringResource(R.string.Send_Confirmation_HintMemo),
        )
        Spacer(Modifier.weight(1f))
        subhead1Italic_leah(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
