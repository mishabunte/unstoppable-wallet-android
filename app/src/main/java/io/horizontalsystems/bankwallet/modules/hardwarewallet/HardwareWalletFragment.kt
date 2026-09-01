package io.horizontalsystems.bankwallet.modules.hardwarewallet

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.BuildConfig
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.core.getInput
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.core.utils.ModuleField
import io.horizontalsystems.bankwallet.modules.address.HSAddressInput
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleOperation
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletSelectDeviceInput
import io.horizontalsystems.bankwallet.modules.hardwarewallet.scanui.HardwareWalletScanButtons
import io.horizontalsystems.bankwallet.modules.hardwarewallet.selectblockchains.SelectHardwareBlockchainsFragment
import io.horizontalsystems.bankwallet.modules.manageaccounts.ManageAccountsModule
import io.horizontalsystems.bankwallet.modules.qrscanner.QRScannerActivity
import io.horizontalsystems.bankwallet.modules.restoreaccount.restoremenu.ByMenu
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.ColoredTextStyle
import io.horizontalsystems.bankwallet.ui.compose.TranslatableString
import io.horizontalsystems.bankwallet.ui.compose.components.AppBar
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonSecondaryDefault
import io.horizontalsystems.bankwallet.ui.compose.components.FormsInput
import io.horizontalsystems.bankwallet.ui.compose.components.HeaderText
import io.horizontalsystems.bankwallet.ui.compose.components.HsBackButton
import io.horizontalsystems.bankwallet.ui.compose.components.MenuItem
import io.horizontalsystems.bankwallet.ui.compose.components.SelectorItem
import io.horizontalsystems.bankwallet.ui.compose.components.TextPreprocessor
import io.horizontalsystems.core.findNavController
import io.horizontalsystems.core.helpers.HudHelper
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.TokenQuery
import io.horizontalsystems.marketkit.models.TokenType
import kotlinx.coroutines.delay

class HardwareWalletFragment : BaseComposeFragment() {

    @Composable
    override fun GetContent(navController: NavController) {
        val input = navController.getInput<ManageAccountsModule.Input>()
        ComposeAppTheme {
            val popUpToInclusiveId =
                input?.popOffOnSuccess ?: R.id.hardwareWalletFragment
            val inclusive =
                input?.popOffInclusive ?: true
            HardwareWalletScreen(findNavController(), popUpToInclusiveId, inclusive)
        }
    }

}

@Composable
fun HardwareWalletScreen(navController: NavController, popUpToInclusiveId: Int, inclusive: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current

    val viewModel = viewModel<HardwareWalletViewModel>(factory = HardwareWalletModule.Factory())
    val uiState = viewModel.uiState
    val accountCreated = uiState.accountCreated
    val submitType = uiState.submitButtonType
    val accountType = uiState.accountType
    val accountName = uiState.accountName
    val type = uiState.type
    val zcashNetworkType = uiState.zcashNetworkType
    val qrScannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onPairingQrScanned(
                result.data?.getStringExtra(ModuleField.SCAN_ADDRESS),
            )
        }
    }

    LifecycleResumeEffect(viewModel) {
        viewModel.onBleFlowResumed()
        onPauseOrDispose {}
    }

    LaunchedEffect(accountCreated) {
        if (accountCreated) {
            HudHelper.showSuccessMessage(
                contenView = view,
                resId = R.string.Hud_Text_HardwareWalletLinked,
                icon = R.drawable.icon_hardware_wallet_24,
                iconTint = R.color.white
            )
            delay(300)
            navController.popBackStack(popUpToInclusiveId, inclusive)
        }
    }

    LaunchedEffect(uiState.zcashPairingRequestId) {
        uiState.zcashPairingRequestId?.let { requestId ->
            viewModel.onZcashPairingNavigationHandled()
            navController.slideFromRight(
                R.id.hardwareWalletSelectDeviceFragment,
                HardwareWalletSelectDeviceInput(
                    operation = HardwareWalletBleOperation.Pairing,
                    requestId = requestId,
                ),
            )
        }
    }

    LaunchedEffect(accountType) {
        accountType?.let {
            Log.d(
                "HardwareWalletFragment",
                "accountType: $it, accountName: $accountName, " +
                    "popUpToInclusiveId: $popUpToInclusiveId, inclusive: $inclusive",
            )
            viewModel.blockchainSelectionOpened()
            navController.slideFromRight(
                R.id.selectHardwareBlockchainsFragment,
                SelectHardwareBlockchainsFragment.Input(
                    popOffOnSuccess = popUpToInclusiveId,
                    popOffInclusive = inclusive,
                    accountType = it,
                    accountName = accountName,
                ),
            )
        }
    }

    ComposeAppTheme {
        Column(modifier = Modifier.background(color = ComposeAppTheme.colors.tyler)) {
            AppBar(
                title = stringResource(R.string.ManageAccounts_LinkHardwareWallet),
                navigationIcon = {
                    HsBackButton(onClick = { navController.popBackStack() })
                },
                menuItems = buildList {
                    when (submitType) {
                        is SubmitButtonType.Done -> {
                            add(
                                MenuItem(
                                    title = TranslatableString.ResString(R.string.Button_Done),
                                    onClick = viewModel::onClickDone,
                                    tint = ComposeAppTheme.colors.jacob,
                                    enabled = submitType.enabled
                                )
                            )
                        }
                        is SubmitButtonType.Next -> {
                            add(
                                MenuItem(
                                    title = TranslatableString.ResString(R.string.Button_Next),
                                    onClick = viewModel::onClickNext,
                                    tint = ComposeAppTheme.colors.jacob,
                                    enabled = submitType.enabled
                                )
                            )
                        }
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                HeaderText(stringResource(id = R.string.ManageAccount_Name))
                FormsInput(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    initial = viewModel.accountName,
                    pasteEnabled = false,
                    hint = viewModel.defaultAccountName,
                    onValueChange = viewModel::onEnterAccountName
                )
                Spacer(Modifier.height(32.dp))

                ByMenu(
                    menuTitle = stringResource(R.string.Restore_By),
                    menuValue = stringResource(type.titleResId),
                    selectorDialogTitle = stringResource(R.string.Hardware_LinkBy),
                    selectorItems = HardwareWalletViewModel.Type.entries.map {
                        SelectorItem(
                            title = stringResource(it.titleResId),
                            selected = it == type,
                            item = it,
                            subtitle = stringResource(it.subtitleResId)
                        )
                    },
                    onSelectItem = {
                        viewModel.onSetType(it)
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))
                when (type) {
                    HardwareWalletViewModel.Type.EvmAddressHardware -> {
                        HSAddressInput(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            tokenQuery = TokenQuery(BlockchainType.Ethereum, TokenType.Native),
                            coinCode = "ETH",
                            navController = navController,
                            textPreprocessor = HardwareWalletAddressTextPreprocessor,
                            onValueChange = viewModel::onEnterAddress
                        )
                    }
                    HardwareWalletViewModel.Type.SolanaAddressHardware -> {
                        HSAddressInput(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            tokenQuery = TokenQuery(BlockchainType.Solana, TokenType.Native),
                            coinCode = "SOL",
                            navController = navController,
                            onValueChange = viewModel::onEnterAddress
                        )
                    }
                    HardwareWalletViewModel.Type.StellarAddressHardware -> {
                        HSAddressInput(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            tokenQuery = TokenQuery(BlockchainType.Stellar, TokenType.Native),
                            coinCode = "XLM",
                            navController = navController,
                            onValueChange = viewModel::onEnterAddress
                        )
                    }
                    HardwareWalletViewModel.Type.ZcashKeyHardware -> {
                        ByMenu(
                            menuTitle = stringResource(R.string.HardwareWallet_Zcash_Network),
                            menuValue = stringResource(zcashNetworkType?.titleResId ?: R.string.Hardware_LinkBy_TypeZcash_Select),
                            selectorDialogTitle = stringResource(R.string.Hardware_LinkBy_TypeZcash_Select),
                            selectorItems = HardwareWalletViewModel.ZcashNetworkType.entries.map {
                                SelectorItem(
                                    title = stringResource(it.titleResId),
                                    selected = it == zcashNetworkType,
                                    item = it,
                                )
                            },
                            onSelectItem = {
                                viewModel.onSetZcashNetworkType(it)
                            }
                        )
                        Spacer(Modifier.height(24.dp))
                        ZcashAccountIndexInput(
                            value = uiState.zcashAccountIndex,
                            isError = uiState.invalidZcashAccountIndex,
                            onValueChange = viewModel::onEnterZcashAccountIndex,
                        )
                    }
                    /*
                    HardwareWalletViewModel.Type.TronAddressHardware -> {
                        HSAddressInput(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            tokenQuery = TokenQuery(BlockchainType.Tron, TokenType.Native),
                            coinCode = "TRX",
                            navController = navController,
                            onValueChange = viewModel::onEnterAddress
                        )
                    }
                    HardwareWalletViewModel.Type.XPubKeyHardware -> {
                        FormsInputMultiline(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            hint = stringResource(id = R.string.Watch_XPubKey_Hint),
                            qrScannerEnabled = true,
                            state = if (uiState.invalidXPubKey)
                                DataState.Error(Exception(stringResource(id = R.string.Watch_Error_InvalidXPubKey)))
                            else
                               null
                        ) {
                            viewModel.onEnterXPubKey(it)
                        }
                    }
                     */
                }

                if (BuildConfig.DEBUG) {
                    Spacer(Modifier.height(24.dp))
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = stringResource(R.string.HardwareWalletImport_Experimental),
                            style = ComposeAppTheme.typography.captionSB,
                            color = ComposeAppTheme.colors.jacob,
                        )
                        Spacer(Modifier.height(8.dp))
                        ButtonSecondaryDefault(
                            modifier = Modifier.fillMaxWidth(),
                            title = stringResource(R.string.HardwareWalletImport_Title),
                            onClick = {
                                navController.slideFromRight(
                                    R.id.hardwareWalletMnemonicImportWarningFragment,
                                )
                            },
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
            if (
                type == HardwareWalletViewModel.Type.ZcashKeyHardware &&
                uiState.zcashAwaitingQr
            ) {
                Column(modifier = Modifier.padding(bottom = 32.dp)) {
                    if (uiState.invalidZcashUfvk) {
                        Text(
                            text = stringResource(R.string.Hardware_Error_InvalidZcashUfvk),
                            style = ComposeAppTheme.typography.body,
                            color = ComposeAppTheme.colors.lucian,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 12.dp),
                        )
                    }
                    HardwareWalletScanButtons(
                        onContinueClick = {
                            qrScannerLauncher.launch(
                                QRScannerActivity.getScanQrIntent(
                                    context,
                                    showPasteButton = false,
                                ),
                            )
                        },
                        onTryAgainClick = viewModel::retryZcashPairing,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZcashAccountIndexInput(
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
) {
    HeaderText(stringResource(R.string.HardwareWallet_Zcash_AccountIndex))
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 0.5.dp,
                color = if (isError) {
                    ComposeAppTheme.colors.red50
                } else {
                    ComposeAppTheme.colors.blade
                },
                shape = RoundedCornerShape(12.dp),
            )
            .background(ComposeAppTheme.colors.lawrence)
            .height(44.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = onValueChange,
            textStyle = ColoredTextStyle(
                color = ComposeAppTheme.colors.leah,
                textStyle = ComposeAppTheme.typography.body,
            ),
            singleLine = true,
            cursorBrush = SolidColor(ComposeAppTheme.colors.jacob),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    if (isError) {
        Text(
            text = stringResource(R.string.HardwareWallet_Zcash_InvalidAccountIndex),
            style = ComposeAppTheme.typography.caption,
            color = ComposeAppTheme.colors.lucian,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )
    }
}

object HardwareWalletAddressTextPreprocessor : TextPreprocessor {
    override fun process(text: String): String {
        return text.removePrefix("ethereum:").removePrefix("solana:").removePrefix("stellar:")
    }
}
