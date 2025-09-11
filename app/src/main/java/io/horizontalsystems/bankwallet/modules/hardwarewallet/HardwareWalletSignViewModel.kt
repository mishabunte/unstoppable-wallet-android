package io.horizontalsystems.bankwallet.modules.hardwarewallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bankwallet.entities.DataState
import io.horizontalsystems.bankwallet.modules.multiswap.sendtransaction.SendTransactionServiceEvm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HardwareWalletSignViewModel(
    private val sendTransactionServiceEvm: SendTransactionServiceEvm
) : ViewModel() {

    private val _unsignedHex = MutableStateFlow<DataState<String>>(DataState.Loading)
    val unsignedHex = _unsignedHex.asStateFlow()

    fun loadUnsigned() = viewModelScope.launch {
        _unsignedHex.value = DataState.Loading
        runCatching { sendTransactionServiceEvm.getUnsignedTransactionHex() }
            .onSuccess { _unsignedHex.value = DataState.Success(it) }
            .onFailure { _unsignedHex.value = DataState.Error(it) }
    }

    class Factory(
        private val service: SendTransactionServiceEvm?
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HardwareWalletSignViewModel(service!!) as T
        }
    }
}
