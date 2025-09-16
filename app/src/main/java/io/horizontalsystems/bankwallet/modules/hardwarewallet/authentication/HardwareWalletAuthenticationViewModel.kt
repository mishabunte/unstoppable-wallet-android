package io.horizontalsystems.bankwallet.modules.hardwarewallet.authentication

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bankwallet.entities.DataState
import io.horizontalsystems.bankwallet.modules.hardwarewallet.HardwareWalletURLRequestHandler
import io.horizontalsystems.bankwallet.modules.hardwarewallet.NFCCallback
import io.horizontalsystems.bankwallet.modules.hardwarewallet.NFCCallbackType
import io.horizontalsystems.bankwallet.modules.hardwarewallet.TokenCheckResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

const val WEB_REQUEST_TIMEOUT = 15_000L

class HardwareWalletAuthenticationViewModel : ViewModel() {

    private val _authenticationResult = MutableStateFlow<HardwareWalletAuthenticationStatus>(HardwareWalletAuthenticationStatus.TapToScan)
    val authenticationResult = _authenticationResult.asStateFlow()
    
    private val _nfcWritingStatus = MutableStateFlow<HardwareWalletNFCStatus>(HardwareWalletNFCStatus.NotReady)
    val nfcWritingStatus = _nfcWritingStatus.asStateFlow()

    fun resetNFCWritingStatus() {
        _nfcWritingStatus.value = HardwareWalletNFCStatus.NotReady
    }

    fun resetAuthenticationResult() {
        _authenticationResult.value = HardwareWalletAuthenticationStatus.TapToScan
    }

    fun createNFCAuthenticationPayload() {
        _nfcWritingStatus.value = HardwareWalletNFCStatus.Loading
        viewModelScope.launch {
            try {
                val payloadRes = withTimeoutOrNull(WEB_REQUEST_TIMEOUT) {
                    HardwareWalletURLRequestHandler().createAuthPayload()
                }
                if (payloadRes == null) {
                    _nfcWritingStatus.value = HardwareWalletNFCStatus.NetworkError
                } else {
                    _nfcWritingStatus.value = HardwareWalletNFCStatus.WritingStarted(
                        NFCCallback(
                            type = NFCCallbackType.AUTHENTICATION,
                            messageText = payloadRes
                        )
                    )
                }
            } catch (e: Exception) {
                _nfcWritingStatus.value = HardwareWalletNFCStatus.NotReady
            }
        }
    }

    fun onQRScanned(scannedQr: String) {
        _authenticationResult.value = HardwareWalletAuthenticationStatus.Loading
        try {
            if (scannedQr.startsWith("https://auth.hito.xyz/?t=")) {
                val authToken = scannedQr.substringAfter("t=")
                viewModelScope.launch {
                    val tokenRes = withTimeoutOrNull(WEB_REQUEST_TIMEOUT) {
                        HardwareWalletURLRequestHandler().authorizeToken(authToken)
                    }
                    if (tokenRes == null) {
                        _authenticationResult.value = HardwareWalletAuthenticationStatus.NetworkError
                        return@launch
                    }
                    if (tokenRes.status == "ok") {
                        _authenticationResult.value = HardwareWalletAuthenticationStatus.Success(tokenRes)
                    } else {
                        _authenticationResult.value = HardwareWalletAuthenticationStatus.NotVerified
                    }
                }
            } else {
                _authenticationResult.value = HardwareWalletAuthenticationStatus.InvalidQR
            }
        } catch (e: Exception) {
            _authenticationResult.value = HardwareWalletAuthenticationStatus.Error(e.message)
        }
    }
}

sealed class HardwareWalletAuthenticationStatus {
    data object TapToScan : HardwareWalletAuthenticationStatus()
    data object Loading : HardwareWalletAuthenticationStatus()
    data object NetworkError : HardwareWalletAuthenticationStatus()
    data object InvalidQR : HardwareWalletAuthenticationStatus()
    data object NotVerified: HardwareWalletAuthenticationStatus()
    data class Success(val tokenCheckResponse: TokenCheckResponse) : HardwareWalletAuthenticationStatus()
    data class Error(val errorMessage: String?) : HardwareWalletAuthenticationStatus()

    fun editionOrNull(): String? {
        return when (this) {
            is Success -> tokenCheckResponse.edition
            else -> null
        }
    }

    fun errorMessageOrNull(): String? {
        return when (this) {
            is Error -> errorMessage ?: "Unknown error"
            else -> null
        }
    }
}

sealed class HardwareWalletNFCStatus {
    data class WritingStarted(val nfcCallback: NFCCallback) : HardwareWalletNFCStatus()
    data object NetworkError : HardwareWalletNFCStatus()
    data object Loading : HardwareWalletNFCStatus()
    data object NotReady: HardwareWalletNFCStatus()

    fun nfcCallbackOrNull(): NFCCallback? {
        return when (this) {
            is WritingStarted -> nfcCallback
            else -> null
        }
    }
}