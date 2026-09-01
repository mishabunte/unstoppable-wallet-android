package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareWalletBleOperationTest {
    @Test
    fun `firmware operation resolves firmware destination`() {
        assertEquals(
            HardwareWalletBleDestination.FirmwareUpgrade,
            HardwareWalletBleOperation.FirmwareUpgrade.destination,
        )
    }

    @Test
    fun `signing operation resolves signing destination`() {
        assertEquals(
            HardwareWalletBleDestination.TransactionSigning,
            HardwareWalletBleOperation.SignTransaction.destination,
        )
    }

    @Test
    fun `pairing operation resolves pairing destination`() {
        assertEquals(
            HardwareWalletBleDestination.Pairing,
            HardwareWalletBleOperation.Pairing.destination,
        )
    }

    @Test
    fun `mnemonic import resolves import destination`() {
        assertEquals(
            HardwareWalletBleDestination.ImportMnemonic,
            HardwareWalletBleOperation.ImportMnemonic.destination,
        )
    }
}
