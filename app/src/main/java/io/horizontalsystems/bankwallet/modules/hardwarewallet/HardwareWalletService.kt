package io.horizontalsystems.bankwallet.modules.hardwarewallet

import android.util.Log
import io.horizontalsystems.bankwallet.core.IAccountFactory
import io.horizontalsystems.bankwallet.core.IAccountManager
import io.horizontalsystems.bankwallet.core.managers.EvmBlockchainManager
import io.horizontalsystems.bankwallet.core.managers.MarketKitWrapper
import io.horizontalsystems.bankwallet.core.managers.WalletActivator
import io.horizontalsystems.bankwallet.core.order
import io.horizontalsystems.bankwallet.core.supports
import io.horizontalsystems.bankwallet.entities.AccountType
import io.horizontalsystems.bankwallet.entities.tokenTypeDerivation
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenQuery
import io.horizontalsystems.marketkit.models.TokenType

class HardwareWalletService(
    private val accountManager: IAccountManager,
    private val walletActivator: WalletActivator,
    private val accountFactory: IAccountFactory,
    private val marketKit: MarketKitWrapper,
    private val evmBlockchainManager: EvmBlockchainManager,
) {

    fun nextHardwareAccountName() = accountFactory.getNextHardwareAccountName()

    fun tokens(accountType: AccountType): List<Token> {
        val tokenQueries = buildList {
            when (accountType) {
                is AccountType.Cex,
                is AccountType.Mnemonic,
                is AccountType.EvmPrivateKey -> Unit // N/A
                is AccountType.SolanaAddress, is AccountType.SolanaAddressHardware -> {
                    if (BlockchainType.Solana.supports(accountType)) {
                        add(TokenQuery(BlockchainType.Solana, TokenType.Native))
                    }
                }

                is AccountType.StellarAddress, is AccountType.StellarAddressHardware -> {
                    Log.d("HardwareWalletService", "tokens: Adding Stellar token for account type $accountType")
                    if (BlockchainType.Stellar.supports(accountType)) {
                        Log.d("HardwareWalletService", "tokens: Stellar supported for account type $accountType")
                        add(TokenQuery(BlockchainType.Stellar, TokenType.Native))
                    }
                }

                is AccountType.TronAddress, is AccountType.TronAddressHardware -> {
                    if (BlockchainType.Tron.supports(accountType)) {
                        add(TokenQuery(BlockchainType.Tron, TokenType.Native))
                    }
                }

                is AccountType.EvmAddress, is AccountType.EvmAddressHardware -> {
                    evmBlockchainManager.allBlockchains.forEach { blockchain ->
                        if (blockchain.type.supports(accountType)) {
                            add(TokenQuery(blockchain.type, TokenType.Native))
                        }
                    }
                }

                is AccountType.HdExtendedKey -> {
                    if (BlockchainType.Bitcoin.supports(accountType)) {
                        accountType.hdExtendedKey.purposes.forEach { purpose ->
                            add(TokenQuery(BlockchainType.Bitcoin, TokenType.Derived(purpose.tokenTypeDerivation)))
                        }
                    }

                    if (BlockchainType.Dash.supports(accountType)) {
                        add(TokenQuery(BlockchainType.Dash, TokenType.Native))
                    }

                    if (BlockchainType.BitcoinCash.supports(accountType)) {
                        TokenType.AddressType.values().map {
                            add(TokenQuery(BlockchainType.BitcoinCash, TokenType.AddressTyped(it)))
                        }
                    }

                    if (BlockchainType.Litecoin.supports(accountType)) {
                        accountType.hdExtendedKey.purposes.map { purpose ->
                            add(TokenQuery(BlockchainType.Litecoin, TokenType.Derived(purpose.tokenTypeDerivation)))
                        }
                    }

                    if (BlockchainType.ECash.supports(accountType)) {
                        add(TokenQuery(BlockchainType.ECash, TokenType.Native))
                    }
                }
                is AccountType.HdExtendedKeyHardware -> {
                    if (BlockchainType.Bitcoin.supports(accountType)) {
                        accountType.hdExtendedKey.purposes.forEach { purpose ->
                            add(TokenQuery(BlockchainType.Bitcoin, TokenType.Derived(purpose.tokenTypeDerivation)))
                        }
                    }

                    if (BlockchainType.Dash.supports(accountType)) {
                        add(TokenQuery(BlockchainType.Dash, TokenType.Native))
                    }

                    if (BlockchainType.BitcoinCash.supports(accountType)) {
                        TokenType.AddressType.values().map {
                            add(TokenQuery(BlockchainType.BitcoinCash, TokenType.AddressTyped(it)))
                        }
                    }

                    if (BlockchainType.Litecoin.supports(accountType)) {
                        accountType.hdExtendedKey.purposes.map { purpose ->
                            add(TokenQuery(BlockchainType.Litecoin, TokenType.Derived(purpose.tokenTypeDerivation)))
                        }
                    }

                    if (BlockchainType.ECash.supports(accountType)) {
                        add(TokenQuery(BlockchainType.ECash, TokenType.Native))
                    }
                }
                else -> {
                    Log.d("HardwareWalletService", "tokens: Unsupported account type $accountType")
                    // Unsupported account type
                }
            }
        }

        return marketKit.tokens(tokenQueries)
            .sortedBy { it.blockchainType.order }
    }

    fun hardwareAll(accountType: AccountType, name: String?) {
        hardwareTokens(accountType, tokens(accountType), name)
    }

    fun hardwareTokens(accountType: AccountType, tokens: List<Token>, name: String? = null) {
        val accountName = name ?: accountFactory.getNextHardwareAccountName()
        val account = accountFactory.hardwareAccount(accountName, accountType)

        accountManager.save(account)

        Log.d("HardwareWalletService", "tokens: Activating ${tokens.size} tokens for hardware wallet account. Token list: ${tokens}")

        try {
            walletActivator.activateTokens(account, tokens)
        } catch (e: Exception) {
            Log.e("HardwareWalletService", "hardwareTokens: Failed to activate tokens for hardware wallet account", e)
        }
    }
}
