package io.horizontalsystems.bankwallet.modules.hardwarewallet

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.BuildConfig
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.core.getInput
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.entities.DataState
import io.horizontalsystems.bankwallet.modules.address.HSAddressInput
import io.horizontalsystems.bankwallet.modules.hardwarewallet.selectblockchains.SelectHardwareBlockchainsFragment
import io.horizontalsystems.bankwallet.modules.manageaccounts.ManageAccountsModule
import io.horizontalsystems.bankwallet.modules.restoreaccount.restoremenu.ByMenu
import io.horizontalsystems.bankwallet.modules.hardwarewallet.selectblockchains.SelectHardwareBlockchainsModule
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.TranslatableString
import io.horizontalsystems.bankwallet.ui.compose.components.AppBar
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonSecondaryDefault
import io.horizontalsystems.bankwallet.ui.compose.components.FormsInput
import io.horizontalsystems.bankwallet.ui.compose.components.FormsInputMultiline
import io.horizontalsystems.bankwallet.ui.compose.components.HeaderText
import io.horizontalsystems.bankwallet.ui.compose.components.HsBackButton
import io.horizontalsystems.bankwallet.ui.compose.components.MenuItem
import io.horizontalsystems.bankwallet.ui.compose.components.SelectorItem
import io.horizontalsystems.bankwallet.ui.compose.components.TextPreprocessor
import io.horizontalsystems.bankwallet.ui.compose.components.TextPreprocessorImpl
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

    val viewModel = viewModel<HardwareWalletViewModel>(factory = HardwareWalletModule.Factory())
    val uiState = viewModel.uiState
    val accountCreated = uiState.accountCreated
    val submitType = uiState.submitButtonType
    val accountType = uiState.accountType
    val accountName = uiState.accountName
    val type = uiState.type

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

    if (accountType != null) {
        Log.d("HardwareWalletFragment", "accountType: $accountType, accountName: $accountName, popUpToInclusiveId: $popUpToInclusiveId, inclusive: $inclusive")
        viewModel.blockchainSelectionOpened()
        Log.d("HardwareWalletFragment", "Navigating to SelectHardwareBlockchainsFragment")

        navController.slideFromRight(
            R.id.selectHardwareBlockchainsFragment,
            SelectHardwareBlockchainsFragment.Input(
                popOffOnSuccess = popUpToInclusiveId,
                popOffInclusive = inclusive,
                accountType = accountType,
                accountName = accountName
            )
        )
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
                    selectorItems = HardwareWalletViewModel.Type.values().map {
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
        }
    }
}

object HardwareWalletAddressTextPreprocessor : TextPreprocessor {
    override fun process(text: String): String {
        return text.removePrefix("ethereum:").removePrefix("solana:").removePrefix("stellar:")
    }
}

