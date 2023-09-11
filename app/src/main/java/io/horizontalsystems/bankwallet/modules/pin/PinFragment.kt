package io.horizontalsystems.bankwallet.modules.pin

import androidx.compose.runtime.Composable
import androidx.core.os.bundleOf
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.modules.pin.ui.PinEdit
import io.horizontalsystems.bankwallet.modules.pin.ui.PinSet
import io.horizontalsystems.bankwallet.modules.pin.ui.PinUnlock
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.core.parcelable
import io.horizontalsystems.core.setNavigationResult

class PinFragment : BaseComposeFragment() {

    private val attachedToLockScreen: Boolean by lazy {
        arguments?.getBoolean(PinModule.keyAttachedToLockScreen) ?: false
    }

    private val interactionType: PinInteractionType by lazy {
        arguments?.parcelable(PinModule.keyInteractionType) ?: PinInteractionType.UNLOCK
    }

    private val showCancelButton: Boolean by lazy {
        arguments?.getBoolean(PinModule.keyShowCancel) ?: false
    }

    @Composable
    override fun Content(navController: NavController) {
        PinScreen(
            interactionType = interactionType,
            showCancelButton = showCancelButton,
            onBackPress = { navController.popBackStack() },
            dismissWithSuccess = { dismissWithSuccess(navController) },
            onCancelClick = { onCancelClick(navController) }
        )
    }

    private fun dismissWithSuccess(navController: NavController) {
        if (attachedToLockScreen && interactionType == PinInteractionType.UNLOCK) {
            activity?.setResult(PinModule.RESULT_OK)
            activity?.finish()
            return
        }

        val bundle = bundleOf(
            PinModule.requestType to interactionType,
            PinModule.requestResult to PinModule.RESULT_OK
        )
        setNavigationResult(PinModule.requestKey, bundle)
        navController.popBackStack()
    }

    private fun onCancelClick(navController: NavController) {
        if (attachedToLockScreen && interactionType == PinInteractionType.UNLOCK) {
            activity?.setResult(PinModule.RESULT_CANCELLED)
            activity?.finish()
            return
        }

        val bundle = bundleOf(
            PinModule.requestType to interactionType,
            PinModule.requestResult to PinModule.RESULT_CANCELLED
        )
        setNavigationResult(PinModule.requestKey, bundle)
        navController.popBackStack()
    }

}

@Composable
private fun PinScreen(
    interactionType: PinInteractionType,
    showCancelButton: Boolean,
    onBackPress: () -> Unit,
    dismissWithSuccess: () -> Unit,
    onCancelClick: () -> Unit
) {
    ComposeAppTheme {
        when (interactionType) {
            PinInteractionType.UNLOCK -> {
                PinUnlock(
                    showCancelButton = showCancelButton,
                    dismissWithSuccess = dismissWithSuccess,
                    onCancelClick = onCancelClick
                )
            }
            PinInteractionType.EDIT_PIN -> {
                PinEdit(
                    dismissWithSuccess = dismissWithSuccess,
                    onBackPress = onBackPress
                )
            }
            PinInteractionType.SET_PIN -> {
                PinSet(
                    dismissWithSuccess = dismissWithSuccess,
                    onBackPress = onBackPress
                )
            }
        }
    }
}
