package io.horizontalsystems.bankwallet.modules.hardwarewallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleModule

object HardwareWalletModule {

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val service = HardwareWalletService(App.accountManager, App.walletActivator, App.accountFactory, App.marketKit, App.evmBlockchainManager)
            return HardwareWalletViewModel(
                hardwareWalletService = service,
                pairingRequestRepository = HardwareWalletBleModule.signingRequestRepository,
            ) as T
        }
    }
}
