package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.core.requireInput
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.AppBar
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.bankwallet.ui.compose.components.HsBackButton
import kotlinx.coroutines.delay

class HardwareWalletBleFragment : BaseComposeFragment(screenshotEnabled = false) {
    @Composable
    override fun GetContent(navController: NavController) {
        val input = navController.requireInput<HardwareWalletTransactionInput>()
        val returnDestination = input.returnDestinationId ?: when (input.operation) {
            HardwareWalletBleOperation.Pairing -> R.id.hardwareWalletFragment
            else -> R.id.sendConfirmation
        }
        val viewModel = viewModel<HardwareWalletBleSendViewModel>(
            factory = HardwareWalletBleSendViewModel.Factory(
                requestId = input.requestId,
                repository = HardwareWalletBleModule.signingRequestRepository,
                session = HardwareWalletBleModule.session(requireContext()),
                operation = input.operation,
            ),
        )

        HardwareWalletBleSendScreen(
            viewModel = viewModel,
            onClose = {
                viewModel.cancel()
                navController.popBackStack(returnDestination, false)
            },
            onCompleted = {
                navController.popBackStack(returnDestination, false)
            },
        )
    }
}

@Composable
private fun HardwareWalletBleSendScreen(
    viewModel: HardwareWalletBleSendViewModel,
    onClose: () -> Unit,
    onCompleted: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    LaunchedEffect(viewModel) { viewModel.start() }
    LaunchedEffect(state) {
        if (state == HardwareWalletTransactionState.Completed) {
            delay(500)
            onCompleted()
        }
    }

    ComposeAppTheme {
        Column(
            modifier = Modifier.fillMaxSize().background(ComposeAppTheme.colors.tyler),
        ) {
            AppBar(
                title = stringResource(R.string.HardwareWalletTransaction_Title),
                navigationIcon = { HsBackButton(onClick = onClose) },
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val message = when (val currentState = state) {
                    HardwareWalletTransactionState.Idle,
                    HardwareWalletTransactionState.Connecting ->
                        R.string.HardwareWalletTransaction_Connecting
                    is HardwareWalletTransactionState.Sending ->
                        R.string.HardwareWalletTransaction_Sending
                    HardwareWalletTransactionState.WaitingForDevice ->
                        R.string.HardwareWalletTransaction_Waiting
                    HardwareWalletTransactionState.Receiving ->
                        R.string.HardwareWalletTransaction_Receiving
                    HardwareWalletTransactionState.Completed ->
                        R.string.HardwareWalletTransaction_Completed
                    is HardwareWalletTransactionState.Error -> currentState.type.messageResId
                }

                if (state !is HardwareWalletTransactionState.Error &&
                    state != HardwareWalletTransactionState.Completed
                ) {
                    CircularProgressIndicator(color = ComposeAppTheme.colors.grey)
                    Spacer(Modifier.height(24.dp))
                }
                Text(
                    text = stringResource(message),
                    style = ComposeAppTheme.typography.headline1,
                    color = ComposeAppTheme.colors.leah,
                    textAlign = TextAlign.Center,
                )
                if (state is HardwareWalletTransactionState.Error) {
                    Spacer(Modifier.height(32.dp))
                    ButtonPrimaryYellow(
                        modifier = Modifier.fillMaxWidth(),
                        title = stringResource(R.string.Button_TryAgain),
                        onClick = viewModel::start,
                    )
                }
            }
        }
    }
}

private val HardwareWalletTransactionError.messageResId: Int
    get() = when (this) {
        HardwareWalletTransactionError.InvalidRequest ->
            R.string.HardwareWalletTransaction_InvalidRequest
        HardwareWalletTransactionError.Disconnected ->
            R.string.HardwareWalletTransaction_Disconnected
        HardwareWalletTransactionError.Rejected ->
            R.string.HardwareWalletTransaction_Rejected
        HardwareWalletTransactionError.Timeout ->
            R.string.HardwareWalletTransaction_Timeout
        HardwareWalletTransactionError.InvalidResponse ->
            R.string.HardwareWalletTransaction_InvalidResponse
        HardwareWalletTransactionError.TransferFailed ->
            R.string.HardwareWalletTransaction_TransferFailed
    }
