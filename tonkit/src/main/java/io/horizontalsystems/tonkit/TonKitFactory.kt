package io.horizontalsystems.tonkit

import io.horizontalsystems.tonkit.api.TonApiAdnl

object TonKitFactory {
    fun create(
        words: List<String>,
        passphrase: String,
        seed: ByteArray,
        privKey: ByteArray
    ): TonKit {
        val api = TonApiAdnl(
            words,
            passphrase,
            seed,
            privKey
        )

        return TonKit(api)
    }
}
