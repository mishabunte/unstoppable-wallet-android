package io.horizontalsystems.bankwallet.modules.hardwarewallet

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import androidx.core.util.Consumer
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import androidx.lifecycle.LifecycleEventObserver
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import com.google.gson.Gson
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryDefault
import io.horizontalsystems.core.helpers.HudHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.horizontalsystems.bankwallet.modules.hardwarewallet.HardwareWalletService
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import okio.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import java.security.SecureRandom

enum class NFCCallbackType {
    AUTHENTICATION,
    PAIRING,
    ETH_SEND,
    SOLANA_SEND,
    BITCOIN_SEND,
}

data class NFCCallback(
    val type: NFCCallbackType,
    var intent: Intent? = null,
    var messageText: String? = null
)

@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
fun AnimatedNFCBox(onCancelClick: () -> Unit, text: String = "Tap to scan the authentication token") {
    // Default text if none is provided
    val color = ComposeAppTheme.colors.leah
    var atEnd: Boolean by remember { mutableStateOf(false) }
    val painter = rememberAnimatedVectorPainter(
        AnimatedImageVector.animatedVectorResource(id = R.drawable.icon_nfc_animated),
        atEnd
    )

    LaunchedEffect(painter) {
        atEnd = !atEnd
        delay(1000L)
    }
    Dialog(onDismissRequest = onCancelClick) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(color = ComposeAppTheme.colors.lawrence)
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.HardwareWalletAuthentication_ReadyToScan),
                style = ComposeAppTheme.typography.title3,
                color = color,
                fontSize = 32.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(2.dp))

            Text(
                text = text,
                style = ComposeAppTheme.typography.body,
                color = color,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Icon(
                modifier = Modifier.size(112.dp),
                painter = painter,
                contentDescription = null,
                tint = color,
            )

            Spacer(Modifier.height(32.dp))

            ButtonPrimaryDefault(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                title = stringResource(R.string.Button_Cancel),
                onClick = {
                    onCancelClick()
                }
            )
        }
    }
}

@Composable
fun AnimatedNFCBoxYellow(onCancelClick: () -> Unit, text: String = "Tap to scan the authentication token") {
    // Default text if none is provided
    val color = ComposeAppTheme.colors.leah
    var atEnd: Boolean by remember { mutableStateOf(false) }
    val painter = rememberAnimatedVectorPainter(
        AnimatedImageVector.animatedVectorResource(id = R.drawable.icon_nfc_animated),
        atEnd
    )

    LaunchedEffect(painter) {
        atEnd = !atEnd
        delay(1000L)
    }
    Dialog(onDismissRequest = onCancelClick) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(color = ComposeAppTheme.colors.lawrence)
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.HardwareWalletAuthentication_ReadyToScan),
                style = ComposeAppTheme.typography.title3,
                color = color,
                fontSize = 32.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(2.dp))

            Text(
                text = text,
                style = ComposeAppTheme.typography.body,
                color = color,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Icon(
                modifier = Modifier.size(112.dp),
                painter = painter,
                contentDescription = null,
                tint = color,
            )

            Spacer(Modifier.height(32.dp))

            ButtonPrimaryYellow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                title = stringResource(R.string.Button_Cancel),
                onClick = {
                    onCancelClick()
                }
            )
        }
    }
}

@Composable
fun StartNFCWriting(nfcHandler: HardwareWalletNFCHandler, nfcCallback: NFCCallback) {
    val context = nfcHandler.context
    val activity = nfcHandler.activity
    DisposableEffect(context) {
        val intentListener = Consumer<Intent> { intent ->
                nfcCallback.intent = intent
                nfcHandler.handleHitoIntent(nfcCallback)
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> nfcHandler.enableForegroundDispatch()
                Lifecycle.Event.ON_PAUSE -> nfcHandler.disableForegroundDispatch()
                else -> {}
            }
        }

        activity.addOnNewIntentListener(intentListener)
        activity.lifecycle.addObserver(observer)

        onDispose {
            activity.removeOnNewIntentListener(intentListener)
            activity.lifecycle.removeObserver(observer)
        }
    }
}


class HardwareWalletNFCHandler(
    val context: android.content.Context,
    private val onSuccess: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    val activity = context as ComponentActivity
    private val onNetworkError: (Throwable) -> Unit = { e: Throwable ->
        HudHelper.showErrorMessage(
            contenView = (context as ComponentActivity).findViewById(android.R.id.content),
            resId = R.string.HardwareWalletAuthentication_NetworkError,
            icon = R.drawable.icon_24_warning_2,
            iconTint = R.color.white
        )
    }
    private val nfcCallbackMap: Map<NFCCallbackType, (nfcCallback: NFCCallback) -> Unit> = mapOf(
        NFCCallbackType.AUTHENTICATION to ::handleHitoAuthIntent,
        NFCCallbackType.PAIRING to ::handleHitoPairIntent,
        NFCCallbackType.ETH_SEND to ::handleHitoEthSendIntent,
        NFCCallbackType.SOLANA_SEND to ::handleHitoSolSendIntent,
    )

    private val nfcAdapter = NfcAdapter.getDefaultAdapter(activity)
    private val pendingIntent = PendingIntent.getActivity(
        activity, 0,
        Intent(activity, activity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_MUTABLE
    )

    fun enableForegroundDispatch() {
        nfcAdapter?.enableForegroundDispatch(activity, pendingIntent, null, null)
    }

    fun disableForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(activity)
    }

    private fun handleHitoEthSendIntent(nfcCallback: NFCCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("HitoAuth", "Generated message for Ethereum: ${nfcCallback.messageText}")

                // Switch to main thread to call handleIntent safely
                withContext(Dispatchers.Main) {
                    handleIntent(nfcCallback)
                }

            } catch (e: Exception) {
                onNetworkError(e)
            }
        }
    }

    private fun handleHitoSolSendIntent(nfcCallback: NFCCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("HitoAuth", "Generated message for Solana: ${nfcCallback.messageText}")

                // Switch to main thread to call handleIntent safely
                withContext(Dispatchers.Main) {
                    handleIntent(nfcCallback)
                }

            } catch (e: Exception) {
                onNetworkError(e)
            }
        }
    }

    private fun handleHitoAuthIntent(nfcCallback: NFCCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                nfcCallback.messageText = HardwareWalletURLRequestHandler().createAuthPayload()

                Log.d("HitoAuth", "Generated message: ${nfcCallback.messageText}")

                // Switch to main thread to call handleIntent safely
                withContext(Dispatchers.Main) {
                    handleIntent(nfcCallback)
                }

            } catch (e: Exception) {
                onNetworkError(e)
            }
        }
    }

    private fun handleHitoPairIntent(nfcCallback: NFCCallback) {
        handleIntent(nfcCallback)
    }

    fun handleHitoIntent(nfcCallback: NFCCallback) {
        nfcCallbackMap[nfcCallback.type]?.invoke(nfcCallback)
    }

    private fun handleIntent(nfcCallback: NFCCallback) {
        val intent = nfcCallback.intent
        if (intent?.action !in listOf(
                NfcAdapter.ACTION_TAG_DISCOVERED,
                NfcAdapter.ACTION_TECH_DISCOVERED,
                NfcAdapter.ACTION_NDEF_DISCOVERED
            )) return

        val rawMessages = intent?.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        val tag: Tag? = intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG)

        try {
            val record = (rawMessages?.getOrNull(0) as? NdefMessage)?.records?.getOrNull(0)
                ?: throw IllegalStateException("Invalid NDEF message")

            val uriBody = record.payload.drop(1).toByteArray().toString(Charsets.UTF_8)
//            if (!uriBody.startsWith("app.hito.dev/#eth/send")) throw IOException("Invalid NFC tag")

//            val messageText = messageHex?.let {
//                "evm.msg:$ownAddress:$it"
//            } ?: "evm.sign:$ownAddress:$txUnsignedHex"
            //val messageText = "evm.sign:0x1234567890abcdef1234567890abcdef12345678:0xabcdefabcdefabcdefabcdefabcdefabcdefabcdef"


            val success = tag?.let { writeTextToTag(it, nfcCallback.messageText!!) } == true
            if (success) onSuccess() else onError(IOException("Failed to write tag"))

        } catch (e: Exception) {
            onError(e)
        }
    }

    private fun writeTextToTag(tag: Tag, text: String): Boolean {
        val textRecord = createTextRecord(text, Locale.ENGLISH, true)
        val ndefMessage = NdefMessage(arrayOf(textRecord))
        return writeTag(tag, ndefMessage)
    }

    private fun writeTag(tag: Tag, ndefMessage: NdefMessage): Boolean {
        val ndef = Ndef.get(tag)
        return try {
            ndef?.run {
                connect()
                if (!isWritable || maxSize < ndefMessage.toByteArray().size) return false
                writeNdefMessage(ndefMessage)
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun createTextRecord(text: String, locale: Locale, encodeInUtf8: Boolean): NdefRecord {
        val langBytes = locale.language.toByteArray(Charsets.US_ASCII)
        val utfEncoding = if (encodeInUtf8) Charsets.UTF_8 else Charsets.UTF_16
        val textBytes = text.toByteArray(utfEncoding)
        val status = ((if (encodeInUtf8) 0 else 1) shl 7) or langBytes.size
        val data = byteArrayOf(status.toByte()) + langBytes + textBytes

        return NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, ByteArray(0), data)
    }
}





