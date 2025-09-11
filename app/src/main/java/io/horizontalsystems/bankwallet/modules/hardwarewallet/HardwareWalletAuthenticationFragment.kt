package io.horizontalsystems.bankwallet.modules.hardwarewallet

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.AppBar
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.bankwallet.core.utils.ModuleField
import io.horizontalsystems.bankwallet.modules.qrscanner.QRScannerActivity
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryDefault
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryTransparent
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellowWithIcon
import io.horizontalsystems.bankwallet.ui.compose.components.HsBackButton
import io.horizontalsystems.core.findNavController
import io.horizontalsystems.core.helpers.HudHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HardwareWalletAuthenticationFragment : BaseComposeFragment() {
    @Composable
    override fun GetContent(navController: NavController) {
        ComposeAppTheme {
            HardwareWalletAuthenticationScreen(findNavController())
        }
    }
}

@Preview
@Composable
private fun AnimatedNfcBoxPreview() {
    ComposeAppTheme {
        Column(modifier = Modifier
            .background(color = ComposeAppTheme.colors.tyler)
            .fillMaxSize()) {
            AnimatedNFCBox({}, "Confirm the transfer by tapping the device")
        }
    }
}

@Composable
private fun HardwareWalletAuthenticationGIF(
    onGloballyPositioned: (IntSize) -> Unit = {}
) {
    var animationRes = R.raw.hw_wallet_auth_animation_light
    if (isSystemInDarkTheme()) {
        // Use a different animation for dark mode
        animationRes = R.raw.hw_wallet_auth_animation_dark
    }
    val context = LocalContext.current
    val uri = "android.resource://${context.packageName}/${animationRes}"
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(uri)
            .crossfade(true)
            .build(),
        contentDescription = null,
        modifier = Modifier.onGloballyPositioned {
            onGloballyPositioned(it.size) // Capture the size of the GIF
        }
    )
}

@Composable
private fun HardwareWalletAuthenticationStartScreen(
    navController: NavController? = null,
    onTagWritingSuccess: () -> Unit = {},
    onTagWritingError: () -> Unit = {},
    onLoadingComplete: () -> Unit = {}
) {
    val context = LocalContext.current

    var gifSize by remember { mutableStateOf(IntSize.Zero) }
    var nfcWritingStarted by remember { mutableStateOf(false) }

    val nfcHandler = HardwareWalletNFCHandler(
        context,
        onSuccess = {
            onTagWritingSuccess()
            nfcWritingStarted = false
        },
        onError = {
            onTagWritingError()
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            HardwareWalletAuthenticationGIF(onGloballyPositioned = { size ->
                gifSize = size
            })

            if (gifSize != IntSize.Zero) {
                onLoadingComplete()
                if (!nfcWritingStarted) {
                    Spacer(Modifier.height(16.dp))

                    ButtonPrimaryDefault(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        title = stringResource(R.string.HardwareWalletAuthentication_StartWalletAuthentication),
                        onClick = {
                            nfcWritingStarted = true
                        }
                    )
                } else {
                    var nfcCallback = NFCCallback(type=NFCCallbackType.AUTHENTICATION)
                    StartNFCWriting(nfcHandler, nfcCallback)
                    AnimatedNFCBox(onCancelClick = {
                        nfcWritingStarted = false
                    })
                }
            }
        }
    }
}


@Composable
private fun HardwareWalletAuthenticationScanScreenFragment(
    navController: NavController? = null,
    onContinueClick: () -> Unit = {},
    onTryAgainClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.tyler),
    ) {
        AppBar(
            title = stringResource(R.string.HardwareWalletAuthentication_Title),
            navigationIcon = {
                HsBackButton(onClick = { navController?.popBackStack() })
            },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 32.dp)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = "The device should've received the authorization token",
                style = ComposeAppTheme.typography.title3,
                color = ComposeAppTheme.colors.leah,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(32.dp))
            Text(
                text = "If you see a QR Image on the screen of your device, it means the device has read the token successfully",
                style = ComposeAppTheme.typography.body,
                color = ComposeAppTheme.colors.leah,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 36.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Please press continue to launch the QR code scanner and proceed with the process",
                style = ComposeAppTheme.typography.body,
                color = ComposeAppTheme.colors.leah,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 36.dp)
            )
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 32.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Try again if it has detected an error",
                style = ComposeAppTheme.typography.body,
                color = ComposeAppTheme.colors.grey,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                ButtonPrimaryYellowWithIcon(
                    modifier = Modifier
                        .padding(8.dp),
                    title = stringResource(R.string.Button_Continue),
                    icon = R.drawable.ic_qr_scan_20,
                    onClick = {
                        onContinueClick()
                    }
                )
//                Spacer(Modifier.width(4.dp))
                ButtonPrimaryTransparent(
                    modifier = Modifier
                        .padding(8.dp),
                    title = stringResource(R.string.Button_TryAgain),
                    onClick = {
                        onTryAgainClick()
                    }
                )
            }
        }
    }
}


@Preview
@Composable
private fun HardwareWalletAuthenticationScanScreenPreview() {
    ComposeAppTheme {
        HardwareWalletAuthenticationScanScreenFragment()
    }
}

@Composable
private fun HardwareWalletAuthenticationScanScreen(
    navController: NavController? = null,
    onContinueClick: () -> Unit = {},
    onTryAgainClick: () -> Unit = {}
) {
    HardwareWalletAuthenticationScanScreenFragment(navController, onContinueClick, onTryAgainClick)
}

@Preview
@Composable
private fun LoadingScreenPreview() {
    ComposeAppTheme {
        LoadingScreen()
    }
}

@Composable
private fun HardwareWalletAuthenticationResultScreen(navController: NavController?=null, tokenResponse: TokenCheckResponse?) {
    if (tokenResponse == null) {
        Log.e("HitoAuth", "Token response is null, showing error screen")
        LoadingScreen(navController)
    }
    if (tokenResponse?.status == "ok") {
        HardwareWalletAuthenticationSuccessScreen(navController, tokenResponse)
    } else {
        HardwareWalletAuthenticationErrorScreen(navController)
    }
}

private fun authorizeToken(token: String, onAuthorizationResult: (TokenCheckResponse) -> Unit = {}, onNetworkError: () -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val tokenRes = HardwareWalletURLRequestHandler().authorizeToken(token)
            onAuthorizationResult(tokenRes)
        } catch (e: Exception) {
            onNetworkError()
        }
    }
}

@Composable
fun HardwareWalletAuthenticationScreen(navController: NavController? = null) {
    val view = LocalView.current
    val context = LocalContext.current
    var authStarted by remember { mutableStateOf<Boolean>(false) }
    var isLoadingComplete by remember { mutableStateOf<Boolean>(false) }

    Column(modifier = Modifier.background(color = ComposeAppTheme.colors.tyler)) {
        AppBar(
            title = stringResource(R.string.HardwareWalletAuthentication_Title),
            navigationIcon = {
                HsBackButton(onClick = { navController?.popBackStack() })
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                //.weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            HardwareWalletAuthenticationStartScreen(
                onTagWritingSuccess = {
                    authStarted = true
                },
                onTagWritingError = {
                    HudHelper.showErrorMessage(
                        contenView = view,
                        resId = R.string.HardwareWalletAuthentication_TagError,
                        icon = R.drawable.icon_24_warning_2,
                        iconTint = R.color.white
                    )
                },
                onLoadingComplete = {
                    isLoadingComplete = false
                }
            )
        }
    }

    if (authStarted) {
        var qrScannerFinished by remember { mutableStateOf<Boolean>(false) }
        var tokenAuthenticationFinished by remember { mutableStateOf<Boolean>(false) }
        var authSuccess by remember { mutableStateOf<Boolean>(false) }
        var invalidQR by remember { mutableStateOf<Boolean>(false) }
        var tokenRes by remember { mutableStateOf<TokenCheckResponse?>(null) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                qrScannerFinished = true
                val scannedQr = result.data?.getStringExtra(ModuleField.SCAN_ADDRESS)?: ""
                if (scannedQr.startsWith("https://auth.hito.xyz/?t=")) {
                    val auth_token = scannedQr.substringAfter("t=")
                    Log.d("QR", "Extracted token: $auth_token")
                    authorizeToken(auth_token, onAuthorizationResult = { tokenResponse ->
                        tokenRes = tokenResponse
                        tokenAuthenticationFinished = true
                    },
                        onNetworkError = {
                            Log.e("QR", "Network error while authorizing token")
                            HudHelper.showErrorMessage(
                                contenView = view,
                                resId = R.string.HardwareWalletAuthentication_NetworkError,
                                icon = R.drawable.icon_24_warning_2,
                                iconTint = R.color.white
                            )
                        }
                    )
                    // You can now use this token in your state or pass it to a ViewModel
                } else {
                    Log.e("QR", "Invalid QR code format: $scannedQr")
                    invalidQR = true
                }
                // You can now use `scannedQr` in your state
            }
        }
        HardwareWalletAuthenticationScanScreen(
            navController = navController,
            onContinueClick = {
                val intent = QRScannerActivity.getScanQrIntent(context, showPasteButton = false)
                launcher.launch(intent)
            },
            onTryAgainClick = {
                authStarted = false
            }
        )
        if (tokenAuthenticationFinished) {
            HardwareWalletAuthenticationResultScreen(navController, tokenRes)
        } else if (qrScannerFinished) {
            LoadingScreen(navController, "Waiting for device authentication...")
        }
        if (invalidQR) {
            InvalidQRScreen(navController, onTryAgainClick = {
                invalidQR = false
                qrScannerFinished = false
            })
        }
    }
}

@Preview
@Composable
private fun HardwareWalletAuthenticationSuccessScreenPreview() {
    ComposeAppTheme {
        InvalidQRScreen(
        )
    }
}


@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
fun HardwareWalletAuthenticationSuccessScreen(
    navController: NavController? = null,
    tokenResponse: TokenCheckResponse?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.tyler),
    ) {
        Column(modifier = Modifier.background(color = ComposeAppTheme.colors.tyler)) {
            AppBar(
                title = stringResource(R.string.HardwareWalletAuthentication_Title),
                navigationIcon = {
                    HsBackButton(onClick = { navController?.popBackStack() })
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = "Your device is authentic!",
                    style = ComposeAppTheme.typography.title3,
                    color = ComposeAppTheme.colors.leah,
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(32.dp))
                if (tokenResponse != null) {
                    Text(
                        text = "Version: ${tokenResponse.edition}",
                        style = ComposeAppTheme.typography.body,
                        color = ComposeAppTheme.colors.leah,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(32.dp))
                Icon(
                    modifier = Modifier.size(112.dp),
                    painter = painterResource(R.drawable.icon_check_1_24),
                    contentDescription = null,
                    tint = ComposeAppTheme.colors.green50,
                )

            }
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 32.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ButtonPrimaryYellow(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                title = stringResource(R.string.Button_Done),
                onClick = {
                    navController?.popBackStack()
                }
            )
        }
    }
}

@Composable
fun HardwareWalletAuthenticationErrorScreen(
    navController: NavController? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.tyler),
    ) {
        Column(modifier = Modifier.background(color = ComposeAppTheme.colors.tyler)) {
            AppBar(
                title = stringResource(R.string.HardwareWalletAuthentication_Title),
                navigationIcon = {
                    HsBackButton(onClick = { navController?.popBackStack() })
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = "Your device is not authentic, or the token is invalid.",
                    style = ComposeAppTheme.typography.title3,
                    color = ComposeAppTheme.colors.leah,
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(32.dp))
                Text(
                    text = "Try again with a valid token or contact support.",
                    style = ComposeAppTheme.typography.body,
                    color = ComposeAppTheme.colors.leah,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(32.dp))
                Icon(
                    modifier = Modifier.size(112.dp),
                    painter = painterResource(R.drawable.icon_24_warning_2),
                    contentDescription = null,
                    tint = ComposeAppTheme.colors.red50,
                )
            }
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 32.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ButtonPrimaryYellow(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                title = stringResource(R.string.Button_TryAgain),
                onClick = {
                    navController?.popBackStack()
                }
            )
        }
    }
}

@Composable
fun InvalidQRScreen(
    navController: NavController? = null,
    onTryAgainClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.tyler)
            .padding(8.dp),
    ) {
        Column(modifier = Modifier.background(color = ComposeAppTheme.colors.tyler)) {
            AppBar(
                title = stringResource(R.string.HardwareWalletAuthentication_Title),
                navigationIcon = {
                    HsBackButton(onClick = { navController?.popBackStack() })
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = "Invalid QR code",
                    style = ComposeAppTheme.typography.title3,
                    color = ComposeAppTheme.colors.leah,
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(32.dp))
                Icon(
                    modifier = Modifier.size(112.dp),
                    painter = painterResource(R.drawable.icon_24_warning_2),
                    contentDescription = null,
                    tint = ComposeAppTheme.colors.red50,
                )
                Spacer(Modifier.height(32.dp))
                Text(
                    text = "You scanned a QR code that does not contain a valid authorization token. Please try again with a valid QR code.",
                    style = ComposeAppTheme.typography.body,
                    color = ComposeAppTheme.colors.leah,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 32.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ButtonPrimaryYellow(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                title = stringResource(R.string.Button_TryAgain),
                onClick = {
                    onTryAgainClick()
                }
            )
        }
    }
}