package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import android.content.Context

object HardwareWalletBleModule {
    val signingRequestRepository: HardwareWalletSigningRequestRepository =
        InMemoryHardwareWalletSigningRequestRepository()

    @Volatile
    private var sessionInstance: HardwareWalletBleSession? = null

    fun session(context: Context): HardwareWalletBleSession =
        sessionInstance ?: synchronized(this) {
            sessionInstance ?: DefaultHardwareWalletBleSession(context.applicationContext).also {
                sessionInstance = it
            }
        }

    fun releaseSession(session: HardwareWalletBleSession) = synchronized(this) {
        if (sessionInstance === session) {
            session.close()
            sessionInstance = null
        }
    }
}
