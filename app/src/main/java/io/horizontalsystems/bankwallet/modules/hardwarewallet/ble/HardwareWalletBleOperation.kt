package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import android.os.Parcelable
import androidx.annotation.StringRes
import io.horizontalsystems.bankwallet.R
import kotlinx.parcelize.Parcelize

enum class HardwareWalletBleDestination {
    FirmwareUpgrade,
    TransactionSigning,
    Pairing,
    ImportMnemonic,
}

enum class HardwareWalletBleOperation(
    @StringRes val titleResId: Int,
    val destination: HardwareWalletBleDestination,
) {
    FirmwareUpgrade(
        R.string.HardwareWalletBle_SelectDevice,
        HardwareWalletBleDestination.FirmwareUpgrade,
    ),
    SignTransaction(
        R.string.HardwareWalletBle_SelectDevice,
        HardwareWalletBleDestination.TransactionSigning,
    ),
    Pairing(
        R.string.HardwareWalletBle_SelectDevice,
        HardwareWalletBleDestination.Pairing,
    ),
    ImportMnemonic(
        R.string.HardwareWalletImport_SelectDevice,
        HardwareWalletBleDestination.ImportMnemonic,
    ),
}

@Parcelize
data class HardwareWalletSelectDeviceInput(
    val operation: HardwareWalletBleOperation,
    val requestId: String? = null,
    val returnDestinationId: Int? = null,
) : Parcelable {
    init {
        require(
            operation == HardwareWalletBleOperation.FirmwareUpgrade ||
                operation == HardwareWalletBleOperation.ImportMnemonic ||
                !requestId.isNullOrBlank(),
        ) {
            "A BLE request ID is required for ${operation.name}"
        }
    }
}

@Parcelize
data class HardwareWalletTransactionInput(
    val requestId: String,
    val operation: HardwareWalletBleOperation,
    val returnDestinationId: Int? = null,
) : Parcelable
