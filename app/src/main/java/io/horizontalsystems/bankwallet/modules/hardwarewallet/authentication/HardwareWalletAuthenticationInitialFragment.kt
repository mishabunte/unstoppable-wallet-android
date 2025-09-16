package io.horizontalsystems.bankwallet.modules.hardwarewallet.authentication

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
//noinspection UsingMaterialAndMaterial3Libraries
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.AppBar
import io.horizontalsystems.bankwallet.modules.hardwarewallet.HardwareWalletNFCHandler
import io.horizontalsystems.bankwallet.modules.hardwarewallet.StartNFCWriting
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryDefault
import io.horizontalsystems.bankwallet.ui.compose.components.HsBackButton
import io.horizontalsystems.core.helpers.HudHelper

class HardwareWalletAuthenticationInitialFragment : BaseComposeFragment() {
    @Composable
    override fun GetContent(navController: NavController) {
        ComposeAppTheme {
            val viewModel = viewModel<HardwareWalletAuthenticationViewModel>(
                viewModelStoreOwner = requireActivity()
            )
            HardwareWalletAuthenticationStartScreen(navController, viewModel)
        }
    }
}

@Composable
private fun HardwareWalletAuthenticationGIF(
    onGloballyPositioned: (IntSize) -> Unit = {}
) {
    var animationRes = R.raw.hw_wallet_auth_animation_light
    if (isSystemInDarkTheme()) {
        animationRes = R.raw.hw_wallet_auth_animation_dark
    }
    val context = LocalContext.current
    val uri = "android.resource://${context.packageName}/${animationRes}"
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(uri)
            .crossfade(false)
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
    viewModel: HardwareWalletAuthenticationViewModel?
) {
    val context = LocalContext.current
    val view = LocalView.current

    var gifSize by remember { mutableStateOf(IntSize.Zero) }
    val nfcWritingStatus by viewModel?.nfcWritingStatus!!.collectAsState()

    val nfcHandler = HardwareWalletNFCHandler(
        context,
        onSuccess = {
            viewModel?.resetNFCWritingStatus()
            navController?.slideFromRight(R.id.hardwareWalletAuthenticationScanFragment)
        },
        onError = {
            HudHelper.showErrorMessage(
                contenView = view,
                resId = R.string.HardwareWalletAuthentication_TagError,
                icon = R.drawable.icon_24_warning_2,
                iconTint = R.color.white
            )
        }
    )
    Column(Modifier.fillMaxSize().background(ComposeAppTheme.colors.tyler)) {
        AppBar(
            title = "Wallet Authentication",
            navigationIcon = {
                HsBackButton(onClick = {
                    navController?.popBackStack()
                })
            },
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    when (nfcWritingStatus) {
                        is HardwareWalletNFCStatus.Loading -> {
                            Spacer(Modifier.height(64.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(112.dp),
                                color = ComposeAppTheme.colors.grey,
                            )
                        }

                        is HardwareWalletNFCStatus.NotReady -> {
                            Spacer(Modifier.height(16.dp))
                            ButtonPrimaryDefault(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                title = stringResource(R.string.HardwareWalletAuthentication_StartWalletAuthentication),
                                onClick = {
                                    viewModel?.createNFCAuthenticationPayload()
                                }
                            )
                        }

                        is HardwareWalletNFCStatus.NetworkError -> {
                            navController?.slideFromRight(R.id.hardwareWalletAuthenticationNetworkErrorFragment)
                            viewModel?.resetNFCWritingStatus()
                        }

                        is HardwareWalletNFCStatus.WritingStarted -> {
                            val nfcCallback =
                                (nfcWritingStatus as HardwareWalletNFCStatus.WritingStarted).nfcCallbackOrNull()
                            StartNFCWriting(
                                nfcHandler, nfcCallback!!, onCancelClick = {
                                    viewModel?.resetNFCWritingStatus()
                                },
                                text = "Tap to scan the authentication token"
                            )
                        }
                    }
                }
            }
        }
    }
}