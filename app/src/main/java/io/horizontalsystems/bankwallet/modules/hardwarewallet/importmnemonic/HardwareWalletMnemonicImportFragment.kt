package io.horizontalsystems.bankwallet.modules.hardwarewallet.importmnemonic

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleModule
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleOperation
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletSelectDeviceInput
import io.horizontalsystems.bankwallet.ui.compose.ColoredTextStyle
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.AppBar
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonSecondaryDefault
import io.horizontalsystems.bankwallet.ui.compose.components.HsBackButton
import io.horizontalsystems.bankwallet.ui.compose.components.HsCheckbox

class HardwareWalletMnemonicImportWarningFragment : BaseComposeFragment(screenshotEnabled = false) {
    @Composable
    override fun GetContent(navController: NavController) {
        var acknowledged by remember { mutableStateOf(false) }
        BackHandler { navController.popBackStack() }

        Column(
            modifier = Modifier.fillMaxSize().background(ComposeAppTheme.colors.tyler),
        ) {
            AppBar(
                title = stringResource(R.string.HardwareWalletImport_Title),
                navigationIcon = { HsBackButton { navController.popBackStack() } },
            )
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.HardwareWalletImport_Experimental),
                    style = ComposeAppTheme.typography.captionSB,
                    color = ComposeAppTheme.colors.jacob,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.HardwareWalletImport_Explanation),
                    style = ComposeAppTheme.typography.body,
                    color = ComposeAppTheme.colors.leah,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.HardwareWalletImport_Warning),
                    style = ComposeAppTheme.typography.body,
                    color = ComposeAppTheme.colors.lucian,
                )
                Spacer(Modifier.height(28.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        acknowledged = !acknowledged
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HsCheckbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.HardwareWalletImport_Acknowledge),
                        style = ComposeAppTheme.typography.body,
                        color = ComposeAppTheme.colors.leah,
                    )
                }
            }
            ButtonPrimaryYellow(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                title = stringResource(R.string.Button_Continue),
                enabled = acknowledged,
                onClick = {
                    navController.slideFromRight(
                        R.id.hardwareWalletSelectDeviceFragment,
                        HardwareWalletSelectDeviceInput(HardwareWalletBleOperation.ImportMnemonic),
                    )
                },
            )
        }
    }
}

class HardwareWalletMnemonicImportFragment : BaseComposeFragment(screenshotEnabled = false) {
    @Composable
    override fun GetContent(navController: NavController) {
        val session = HardwareWalletBleModule.session(requireContext())
        val viewModel = viewModel<HardwareWalletMnemonicImportViewModel>(
            factory = HardwareWalletMnemonicImportViewModel.Factory(
                session = session,
                releaseSession = HardwareWalletBleModule::releaseSession,
            ),
        )

        fun closeFlow() {
            viewModel.leave()
            navController.popBackStack(R.id.hardwareWalletFragment, false)
        }

        val view = LocalView.current
        DisposableEffect(viewModel, view) {
            val previousAutofill = view.importantForAutofill
            val previousSaveEnabled = view.isSaveEnabled
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            view.isSaveEnabled = false
            onDispose {
                viewModel.leave()
                view.importantForAutofill = previousAutofill
                view.isSaveEnabled = previousSaveEnabled
            }
        }
        LaunchedEffect(viewModel) { viewModel.start() }
        BackHandler(onBack = ::closeFlow)

        val state by viewModel.uiState.collectAsStateWithLifecycle()
        MnemonicImportScreen(
            state = state,
            deviceName = session.deviceName(),
            onPhraseChanged = viewModel::updatePhrase,
            onNext = viewModel::validateAndConfirm,
            onEdit = viewModel::editPhrase,
            onConfirm = viewModel::confirmImport,
            onClose = ::closeFlow,
            onReselect = {
                viewModel.leave()
                navController.popBackStack()
            },
        )
    }
}

@Composable
private fun MnemonicImportScreen(
    state: MnemonicImportUiState,
    deviceName: String?,
    onPhraseChanged: (String) -> Unit,
    onNext: () -> Unit,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    onReselect: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(ComposeAppTheme.colors.tyler)) {
        AppBar(
            title = stringResource(R.string.HardwareWalletImport_Title),
            navigationIcon = { HsBackButton(onClick = onClose) },
        )

        when (val phase = state.phase) {
            MnemonicImportPhase.EnterPhrase -> PhraseEntry(
                phrase = state.phrase,
                inputError = state.inputError,
                onPhraseChanged = onPhraseChanged,
                onNext = onNext,
            )
            is MnemonicImportPhase.Confirm -> Confirmation(
                wordCount = phase.wordCount,
                deviceName = deviceName,
                onEdit = onEdit,
                onConfirm = onConfirm,
            )
            MnemonicImportPhase.Connecting -> StatusContent(
                message = stringResource(R.string.HardwareWalletImport_Connecting),
            )
            is MnemonicImportPhase.Sending -> StatusContent(
                message = stringResource(
                    R.string.HardwareWalletImport_Sending,
                    (phase.progress * 100).toInt(),
                ),
            )
            MnemonicImportPhase.Processing -> StatusContent(
                message = stringResource(R.string.HardwareWalletImport_Processing),
            )
            MnemonicImportPhase.Success -> ResultContent(
                message = stringResource(R.string.HardwareWalletImport_Success),
                button = stringResource(R.string.Button_Done),
                onClick = onClose,
            )
            is MnemonicImportPhase.Error -> ResultContent(
                message = stringResource(phase.type.messageResId),
                button = stringResource(R.string.HardwareWalletImport_ChooseDevice),
                onClick = onReselect,
            )
        }
    }
}

@Composable
private fun PhraseEntry(
    phrase: String,
    inputError: MnemonicInputError?,
    onPhraseChanged: (String) -> Unit,
    onNext: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.HardwareWalletImport_InputHelp),
                style = ComposeAppTheme.typography.body,
                color = ComposeAppTheme.colors.grey,
            )
            Spacer(Modifier.height(20.dp))
            BasicTextField(
                value = phrase,
                onValueChange = onPhraseChanged,
                modifier = Modifier.fillMaxWidth().height(180.dp)
                    .border(
                        0.5.dp,
                        if (inputError == null) ComposeAppTheme.colors.blade
                        else ComposeAppTheme.colors.red50,
                        RoundedCornerShape(12.dp),
                    )
                    .background(ComposeAppTheme.colors.lawrence, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                textStyle = ColoredTextStyle(
                    textStyle = ComposeAppTheme.typography.body,
                    color = ComposeAppTheme.colors.leah,
                ),
                cursorBrush = SolidColor(ComposeAppTheme.colors.jacob),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                ),
                decorationBox = { inner ->
                    if (phrase.isEmpty()) {
                        Text(
                            text = stringResource(R.string.HardwareWalletImport_InputHint),
                            style = ComposeAppTheme.typography.body,
                            color = ComposeAppTheme.colors.andy,
                        )
                    }
                    inner()
                },
            )
            inputError?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(it.messageResId),
                    style = ComposeAppTheme.typography.caption,
                    color = ComposeAppTheme.colors.lucian,
                )
            }
        }
        ButtonPrimaryYellow(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            title = stringResource(R.string.Button_Next),
            onClick = onNext,
        )
    }
}

@Composable
private fun Confirmation(
    wordCount: Int,
    deviceName: String?,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.HardwareWalletImport_ConfirmTitle),
            style = ComposeAppTheme.typography.headline1,
            color = ComposeAppTheme.colors.leah,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(
                R.string.HardwareWalletImport_ConfirmDescription,
                wordCount,
                deviceName ?: stringResource(R.string.HardwareWalletImport_HitoDevice),
            ),
            style = ComposeAppTheme.typography.body,
            color = ComposeAppTheme.colors.grey,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        ButtonPrimaryYellow(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.HardwareWalletImport_ConfirmButton),
            onClick = onConfirm,
        )
        Spacer(Modifier.height(12.dp))
        ButtonSecondaryDefault(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.Button_Back),
            onClick = onEdit,
        )
    }
}

@Composable
private fun StatusContent(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = ComposeAppTheme.colors.grey)
        Spacer(Modifier.height(24.dp))
        Text(
            text = message,
            style = ComposeAppTheme.typography.headline1,
            color = ComposeAppTheme.colors.leah,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ResultContent(message: String, button: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = ComposeAppTheme.typography.headline1,
            color = ComposeAppTheme.colors.leah,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        ButtonPrimaryYellow(
            modifier = Modifier.fillMaxWidth(),
            title = button,
            onClick = onClick,
        )
    }
}

private val MnemonicInputError.messageResId: Int
    get() = when (this) {
        MnemonicInputError.Empty -> R.string.HardwareWalletImport_ErrorEmpty
        MnemonicInputError.UnsupportedWordCount -> R.string.HardwareWalletImport_ErrorWordCount
        MnemonicInputError.InvalidWord -> R.string.HardwareWalletImport_ErrorInvalidWord
        MnemonicInputError.InvalidChecksum -> R.string.HardwareWalletImport_ErrorChecksum
    }

private val MnemonicImportError.messageResId: Int
    get() = when (this) {
        MnemonicImportError.NotConnected -> R.string.HardwareWalletImport_ErrorNotConnected
        MnemonicImportError.Disconnected -> R.string.HardwareWalletImport_ErrorDisconnected
        MnemonicImportError.Timeout -> R.string.HardwareWalletImport_ErrorTimeout
        MnemonicImportError.FirmwareRejected -> R.string.HardwareWalletImport_ErrorRejected
        MnemonicImportError.MalformedResponse -> R.string.HardwareWalletImport_ErrorResponse
        MnemonicImportError.TransferFailed -> R.string.HardwareWalletImport_ErrorTransfer
    }
