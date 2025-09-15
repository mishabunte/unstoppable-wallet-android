package io.horizontalsystems.bankwallet.modules.hardwarewallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.entities.AccountType
import io.horizontalsystems.bankwallet.entities.Address
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryDefault
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryTransparent
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellowWithIcon
import io.horizontalsystems.hdwalletkit.HDExtendedKey

class HardwareWalletViewModel(
    private val hardwareWalletService: HardwareWalletService
) : ViewModel() {

    private var accountCreated = false
    private var submitButtonType: SubmitButtonType = SubmitButtonType.Next(false)
    private var type = Type.EvmAddressHardware
    private var address: Address? = null
    private var xPubKey: String? = null
    private var invalidXPubKey = false

    private var accountType: AccountType? = null
    private var accountNameEdited = false
    val defaultAccountName = hardwareWalletService.nextHardwareAccountName()
    var accountName: String = defaultAccountName
        get() = field.ifBlank { defaultAccountName }
        private set


    var uiState by mutableStateOf(
        HardwareWalletUiState(
            accountCreated = accountCreated,
            submitButtonType = submitButtonType,
            type = type,
            accountType = accountType,
            accountName = accountName,
            invalidXPubKey = invalidXPubKey
        )
    )
        private set

    private fun emitState() {
        uiState = HardwareWalletUiState(
            accountCreated = accountCreated,
            submitButtonType = submitButtonType,
            type = type,
            accountType = accountType,
            accountName = accountName,
            invalidXPubKey = invalidXPubKey
        )
    }

    fun onEnterAccountName(v: String) {
        accountNameEdited = v.isNotBlank()
        accountName = v
    }

    fun onEnterAddress(v: Address?) {
        address = v
        if (!accountNameEdited) {
            accountName = v?.domain ?: defaultAccountName
        }

        syncSubmitButtonType()
        emitState()
    }

    fun onEnterXPubKey(v: String) {
        xPubKey = try {
            val hdKey = HDExtendedKey(v)
            require(hdKey.isPublic) {
                throw HDExtendedKey.ParsingError.WrongVersion
            }
            invalidXPubKey = false
            v
        } catch (t: Throwable) {
            invalidXPubKey = v.isNotBlank()
            null
        }

        syncSubmitButtonType()
        emitState()
    }

    fun blockchainSelectionOpened() {
        accountType = null

        emitState()
    }

    fun onClickNext() {
        accountType = getAccountType()

        emitState()
    }

    fun onClickDone() {
        try {
            val accountType = getAccountType() ?: throw Exception()

            hardwareWalletService.hardwareAll(accountType, accountName)

            accountCreated = true
            emitState()
        } catch (_: Exception) {

        }
    }

    fun onSetType(type: Type) {
        this.type = type

        address = null
        xPubKey = null

        if (!accountNameEdited) {
            accountName = defaultAccountName
        }

        syncSubmitButtonType()
        emitState()
    }

    private fun syncSubmitButtonType() {
        submitButtonType = when (type) {
            Type.EvmAddressHardware    -> SubmitButtonType.Next(address != null)
            //Type.XPubKeyHardware       -> SubmitButtonType.Next(xPubKey != null)
            Type.SolanaAddressHardware -> SubmitButtonType.Next(address != null)
            //Type.TronAddressHardware   -> SubmitButtonType.Done(address != null)
        }
    }

    private fun getAccountType() = when (type) {
        Type.EvmAddressHardware    -> address?.let { AccountType.EvmAddressHardware(it.hex) }
        Type.SolanaAddressHardware -> address?.let { AccountType.SolanaAddressHardware(it.hex) }
        //Type.TronAddressHardware   -> address?.let { AccountType.TronAddressHardware(it.hex)}
        //Type.XPubKeyHardware       -> xPubKey?.let { AccountType.HdExtendedKeyHardware(it) }
    }

    enum class Type(val titleResId: Int, val subtitleResId: Int) {
        EvmAddressHardware(R.string.Hardware_LinkBy_TypeEvmAddress, R.string.Hardware_LinkBy_TypeEvmAddress_Subtitle),
        //TronAddressHardware(R.string.Watch_TypeTronAddress, R.string.Watch_TypeTronAddress_Subtitle),
        SolanaAddressHardware(R.string.Hardware_LinkBy_TypeSolanaAddress, R.string.Hardware_LinkBy_TypeSolanaAddress_Subtitle),
        //XPubKeyHardware(R.string.Watch_TypeXPubKey, R.string.Watch_TypeXPubKey_Subtitle),
    }
}

data class HardwareWalletUiState(
    val accountCreated: Boolean,
    val submitButtonType: SubmitButtonType,
    val type: HardwareWalletViewModel.Type,
    val accountType: AccountType?,
    val accountName: String?,
    val invalidXPubKey: Boolean
)

@Composable
fun NumeratedList(
    modifier: Modifier,
    items: List<String>
) {
    Column(
        modifier = modifier
    ) {
        for (i in items.indices) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    text = "${i + 1}.", // numeration
                    modifier = Modifier.padding(end = 8.dp),
                    style = ComposeAppTheme.typography.body,
                    color = ComposeAppTheme.colors.leah
                )
                Text(
                    text = items[i],
                    style = ComposeAppTheme.typography.body,
                    color = ComposeAppTheme.colors.leah
                )
            }
        }
    }
}

@Composable
fun DottedList(
    modifier: Modifier,
    items: List<String>
) {
    Column(
        modifier = modifier
    ) {
        items.forEach { item ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    text = "\u2022", // bullet symbol
                    modifier = Modifier.padding(end = 8.dp),
                    style = ComposeAppTheme.typography.body,
                    color = ComposeAppTheme.colors.grey
                )
                Text(
                    text = item,
                    style = ComposeAppTheme.typography.body,
                    color = ComposeAppTheme.colors.grey
                )
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
private fun Preview_ScanButtons() {
    ComposeAppTheme {
        HardwareWalletScanButtons(
//            modifier = Modifier
//                .padding(horizontal = 16.dp)
//                .padding(bottom = 16.dp)
        )
    }
}

@Composable
fun HardwareWalletScanButtons (
    onContinueClick: () -> Unit = {},
    onTryAgainClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Try again if you detected an error",
            style = ComposeAppTheme.typography.body,
            color = ComposeAppTheme.colors.grey,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            ) {
                ButtonPrimaryYellowWithIcon(
                    modifier = Modifier.weight(0.5f),
                    title = stringResource(R.string.Button_Continue),
                    icon = R.drawable.ic_qr_scan_20,
                    onClick = {
                        onContinueClick()
                    }
                )
                ButtonPrimaryTransparent(
                    modifier = Modifier.weight(0.5f),
                    title = stringResource(R.string.Button_TryAgain),
                    onClick = {
                        onTryAgainClick()
                    }
                )
            }
        }
    }
}

@Composable
fun LoadingScreen(
    navController: NavController? = null,
    loadingMessage: String = "Loading..."
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.tyler)
    ) {
        // Loading indicator centered in full screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 56.dp) // Adjust if AppBar height is different
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                color = ComposeAppTheme.colors.leah,
                strokeWidth = 6.dp
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = loadingMessage,
                style = ComposeAppTheme.typography.body,
                color = ComposeAppTheme.colors.leah,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

sealed class SubmitButtonType {
    data class Done(val enabled: Boolean) : SubmitButtonType()
    data class Next(val enabled: Boolean) : SubmitButtonType()
}
