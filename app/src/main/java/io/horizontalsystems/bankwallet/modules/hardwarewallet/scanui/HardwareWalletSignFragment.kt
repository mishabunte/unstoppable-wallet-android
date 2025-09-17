package io.horizontalsystems.bankwallet.modules.hardwarewallet.scanui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.DottedList
import io.horizontalsystems.bankwallet.ui.compose.components.cell.SectionUniversalLawrence
import io.horizontalsystems.ethereumkit.core.hexStringToByteArray
import io.horizontalsystems.ethereumkit.models.Signature
import io.horizontalsystems.ethereumkit.spv.core.toBigInteger
import io.horizontalsystems.ethereumkit.spv.core.toInt
import io.horizontalsystems.ethereumkit.spv.rlp.RLP
import io.horizontalsystems.ethereumkit.spv.rlp.RLPList

@Preview(showBackground = true)
@Composable
fun Preview_HardwareWalletSignScanFragment() {
    ComposeAppTheme {
        HardwareWalletSignScanFragment()
    }
}

@Composable
fun HardwareWalletSendCautions() {
    SectionUniversalLawrence {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                modifier = Modifier.size(32.dp),
                painter = painterResource(R.drawable.ic_attention_24),
                contentDescription = null,
                tint = ComposeAppTheme.colors.jacob
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Check your transaction data before signing",
                style = ComposeAppTheme.typography.body,
                color = ComposeAppTheme.colors.leah,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Column(Modifier.padding(start = 24.dp, end=16.dp, bottom = 16.dp)) {
            DottedList(Modifier.padding(end = 16.dp),
                listOf(
                    "After checking transaction data on your hardware device:"
                ),
                textColor = ComposeAppTheme.colors.leah
            )
            DottedList(
                Modifier.padding(horizontal = 16.dp),
                listOf(
                    "Press 'Sign' on your hardware device",
                    "Press 'Continue' on your phone to scan the QR code appeared"
                ),
                textColor = ComposeAppTheme.colors.grey
            )
        }
    }
}

@Composable
fun HardwareWalletSignScanFragment(
    modifier: Modifier = Modifier,
    onContinueClick: () -> Unit = {},
    onTryAgainClick: () -> Unit = {}
) {
    ComposeAppTheme {
        Column(
            modifier = modifier
                .fillMaxWidth()
//                .wrapContentHeight()
                .background(ComposeAppTheme.colors.tyler),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HardwareWalletSendCautions()
            HardwareWalletScanButtons(
                onContinueClick = onContinueClick,
                onTryAgainClick = onTryAgainClick
            )
        }
    }
}

fun Signature.toHex(): String {
    return "0x" + r.toByteArrayToHex() + s.toByteArrayToHex() + v.toHex()
}

fun Signature.toByteArray(): ByteArray {
    return r + s + v.toByte()
}

fun ByteArray.toByteArrayToHex(): String {
    return joinToString(separator = "") { byte -> "%02x".format(byte) }
}

fun Int.toHex(): String {
    return this.toString(16)
}

// TODO: move to EvmKitManager
fun decodeRawTransactionSignature(txhex: String): Signature? {
    val tx = txhex.removePrefix("0x").hexStringToByteArray()
    val rlp =
        if (tx[0] == 0x02.toByte()) {
            RLP.decode2(tx.copyOfRange(1, tx.size)).get(0) as RLPList
        } else {
            RLP.decode2(tx).get(0) as RLPList
        }
    if (rlp.size > 3) {
        return Signature(
            rlp.get(rlp.size - 3).toInt(),
            rlp.get(rlp.size - 2).toBigInteger().toByteArray(),
            rlp.get(rlp.size - 1).toBigInteger().toByteArray()
        )
    } else {
        return null
    }
}




