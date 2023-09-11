package io.horizontalsystems.bankwallet.modules.walletconnect.request.signmessage.v1

import androidx.activity.addCallback
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.navigation.NavController
import androidx.navigation.navGraphViewModels
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.modules.walletconnect.WalletConnectViewModel
import io.horizontalsystems.bankwallet.modules.walletconnect.request.signmessage.WCSignMessageRequestModule
import io.horizontalsystems.bankwallet.modules.walletconnect.request.signmessage.WCSignMessageRequestViewModel
import io.horizontalsystems.bankwallet.modules.walletconnect.request.signmessage.ui.SignMessageRequestScreen

class WCSignMessageRequestFragment : BaseComposeFragment() {

    @Composable
    override fun Content(navController: NavController) {
        val baseViewModel = getBaseViewModel()

        if (baseViewModel == null) {
            navController.popBackStack()
            return
        }

        val vmFactory by lazy {
            WCSignMessageRequestModule.Factory(
                baseViewModel.sharedSignMessageRequest!!,
                baseViewModel.dAppName,
                baseViewModel.service
            )
        }
        val viewModel by viewModels<WCSignMessageRequestViewModel> { vmFactory }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            viewModel.reject()
        }

        viewModel.closeLiveEvent.observe(viewLifecycleOwner) {
            baseViewModel.sharedSignMessageRequest = null
            navController.popBackStack()
        }

        SignMessageRequestScreen(
            navController,
            viewModel
        )
    }

    private fun getBaseViewModel(): WalletConnectViewModel? {
        return try {
            val viewModel by navGraphViewModels<WalletConnectViewModel>(R.id.wcSessionFragment)
            viewModel
        } catch (e: RuntimeException) {
            null
        }
    }

}
