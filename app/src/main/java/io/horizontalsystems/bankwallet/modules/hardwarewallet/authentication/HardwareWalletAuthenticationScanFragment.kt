package io.horizontalsystems.bankwallet.modules.hardwarewallet.authentication

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.core.slideFromBottom
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.core.utils.ModuleField
import io.horizontalsystems.bankwallet.modules.hardwarewallet.DottedList
import io.horizontalsystems.bankwallet.modules.hardwarewallet.HardwareWalletScanButtons
import io.horizontalsystems.bankwallet.modules.hardwarewallet.TokenCheckResponse
import io.horizontalsystems.bankwallet.modules.qrscanner.QRScannerActivity
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.AppBar
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryTransparent
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellowWithIcon
import io.horizontalsystems.bankwallet.ui.compose.components.HsBackButton

class HardwareWalletAuthenticationScanFragment : BaseComposeFragment() {
    @Composable
    override fun GetContent(navController: NavController) {
        ComposeAppTheme {
            val viewModel = viewModel<HardwareWalletAuthenticationViewModel>(
                viewModelStoreOwner = requireActivity()
            )
            HardwareWalletAuthenticationScanScreen(navController, viewModel)
        }
    }
}

@Composable
private fun HardwareWalletAuthenticationScanScreen(
    navController: NavController? = null,
    viewModel: HardwareWalletAuthenticationViewModel
) {
    val context = LocalContext.current
    val authenticationStatus by viewModel.authenticationResult.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedQr = result.data?.getStringExtra(ModuleField.SCAN_ADDRESS)?: ""
            viewModel.onQRScanned(scannedQr)
        }
    }
    HardwareWalletAuthenticationStateScreen(
        navController,
        authenticationStatus,
        onScanClick = {
            val intent = QRScannerActivity.getScanQrIntent(context, showPasteButton = false)
            launcher.launch(intent)
        },
        onFinishClick = {
            viewModel.resetAuthenticationResult()
            navController?.popBackStack(R.id.mainFragment, false)
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun Preview_HardwareWalletAuthenticationStateScreen() {
    val testTokenCheckResponse = TokenCheckResponse(
        "Hito Founders Edition",
        "ok"
    )
    HardwareWalletAuthenticationStateScreen(
        null,
        HardwareWalletAuthenticationStatus.TapToScan,
        {},{})
}


@Composable
private fun HardwareWalletAuthenticationStateScreen(
    navController: NavController?,
    authenticationStatus: HardwareWalletAuthenticationStatus,
    onScanClick: () -> Unit,
    onFinishClick: () -> Unit
) {
    ComposeAppTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ComposeAppTheme.colors.tyler)
        ) {
            AppBar(
                title = stringResource(R.string.HardwareWalletAuthentication_Title),
                navigationIcon = {
                    HsBackButton(onClick = { navController?.popBackStack() })
                },
            )
            when (authenticationStatus) {
                is HardwareWalletAuthenticationStatus.TapToScan -> {
                    HardwareWalletTapToScanScreen(navController) {
                        onScanClick()
                    }
                }
                is HardwareWalletAuthenticationStatus.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ComposeAppTheme.colors.tyler)
                    ) {
                        // Loading indicator centered in full screen
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = ComposeAppTheme.colors.grey,
                                modifier = Modifier.size(112.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Waiting for device authentication...",
                                style = ComposeAppTheme.typography.headline2,
                                color = ComposeAppTheme.colors.grey,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                is HardwareWalletAuthenticationStatus.InvalidQR -> {
                    HardwareWalletInvalidQRScreen(navController) {
                        onFinishClick()
                    }
                }
                is HardwareWalletAuthenticationStatus.NetworkError -> {
                    navController?.slideFromRight(R.id.hardwareWalletAuthenticationNetworkErrorFragment)
                }
                is HardwareWalletAuthenticationStatus.Success -> {
                    HardwareWalletAuthenticationSuccessScreen(navController, authenticationStatus.editionOrNull()) {
                        onFinishClick()
                    }
                }
                is HardwareWalletAuthenticationStatus.NotVerified -> {
                    HardwareWalletAuthenticationNotVerifiedScreen(navController) {
                        onFinishClick()
                    }
                }
                is HardwareWalletAuthenticationStatus.Error -> {
                    HardwareWalletAuthenticationErrorScreen(navController) {
                        onFinishClick()
                    }
                }
            }
        }
    }
}

@Composable
private fun HardwareWalletTapToScanScreen(navController: NavController?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.tyler),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .padding(start = 32.dp, end = 32.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.QrCode2,
                contentDescription = null,
                tint = ComposeAppTheme.colors.grey,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "The device should've received the authorization token",
                style = ComposeAppTheme.typography.headline1,
                color = ComposeAppTheme.colors.leah,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            DottedList(Modifier.padding(horizontal = 32.dp, vertical = 16.dp), listOf(
                "If you see a QR Image on the screen of your device, it means the device has read the token successfully",
                "Please press continue to launch the QR code scanner and proceed with the process"
            ))
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 48.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HardwareWalletScanButtons(
                onContinueClick = onClick,
                onTryAgainClick = {navController?.popBackStack()}
            )
        }
    }
}