package gy.pig.spark

import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import spark.SparkServiceGrpcKt
import spark_token.SparkTokenServiceGrpcKt

/**
 * Top-level entry point of the Spark Kotlin SDK.
 *
 * A `SparkWallet` owns the gRPC channels to Spark operators, the GraphQL client to the
 * SSP, and a [SparkSignerProtocol] implementation that controls the wallet's identity
 * and FROST signing material. Construct one via [fromMnemonic], [fromAccountKey], or
 * [fromSigner], and always call [close] when you are done — it cancels the internal
 * coroutine scope and drains the gRPC channels.
 *
 * Typical lifecycle:
 *
 * ```kotlin
 * val wallet = SparkWallet.fromMnemonic(
 *     config = SparkConfig(network = SparkNetwork.MAINNET),
 *     mnemonic = "...",
 * )
 * try {
 *     val invoice = wallet.createLightningInvoice(amountSats = 1_000)
 *     // ... pay the invoice from an external wallet ...
 *     wallet.claimAllPendingTransfers()
 *     wallet.send(receiverIdentityPublicKey = recipient, amountSats = 500)
 * } finally {
 *     wallet.close()
 * }
 * ```
 *
 * The class is **not** thread-safe for concurrent reads of mutable internal state, but
 * every public `suspend` method is safe to invoke from any coroutine context. Prefer
 * `Dispatchers.IO` and never call from `Dispatchers.Main`.
 *
 * @property config Network, operator list, and SSP endpoint.
 * @property signer Identity / FROST signer. Pluggable via [fromSigner] for hardware-backed
 *   or Android Keystore-backed implementations.
 * @see SparkConfig
 * @see SparkSignerProtocol
 * @see SparkError
 */
class SparkWallet private constructor(val config: SparkConfig, val signer: SparkSignerProtocol,) {
    internal val connectionManager = GrpcConnectionManager(config.signingOperatorAddresses)
    internal val authenticator = SparkAuthenticator()
    internal val sspClient: SspGraphQLClient

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    /**
     * The wallet's identity public key as a lowercase hex string (33-byte compressed
     * secp256k1). Share this value with anyone who wants to send you a Spark transfer.
     */
    val identityPublicKeyHex: String
        get() = signer.identityPublicKey.toHexString()

    /**
     * Sign a 32-byte message hash with the wallet's identity private key.
     *
     * The signature is produced over the identity secp256k1 key — distinct from the
     * per-leaf FROST signing keys used by transfers. Use this for proof-of-identity
     * flows (e.g. SSP authentication challenges).
     *
     * @param messageHash exactly 32 bytes (typically `sha256(message)`).
     * @return DER-encoded ECDSA signature.
     */
    fun signWithIdentityKey(messageHash: ByteArray): ByteArray = signer.signWithIdentityKey(messageHash)

    init {
        val httpClient = OkHttpClient()
        val sspAuthenticator = SspAuthenticator(httpClient, config.sspURL, signer)
        sspClient = SspGraphQLClient(httpClient, config.sspURL) {
            sspAuthenticator.getToken()
        }
    }

    companion object {
        /**
         * Create a wallet from a BIP-39 mnemonic.
         *
         * The mnemonic is converted to seed material via PBKDF2-HMAC-SHA512 and
         * deterministically derived into the wallet's identity and signing keys
         * via BIP-32.
         *
         * @param config Network and operator configuration. Defaults to mainnet.
         * @param mnemonic Space-separated BIP-39 word list (12 / 15 / 18 / 21 / 24 words).
         * @param account BIP-32 account index. When `null`, defaults to `1` on mainnet
         *   and `0` on regtest. Pass an explicit value to manage multiple accounts on
         *   the same mnemonic.
         * @return A ready-to-use wallet. Always pair with a `try { ... } finally { close() }`.
         */
        fun fromMnemonic(config: SparkConfig = SparkConfig(), mnemonic: String, account: Int? = null,): SparkWallet {
            val resolvedAccount = account ?: if (config.network == SparkNetwork.MAINNET) 1 else 0
            val signer = SparkSigner.fromMnemonic(mnemonic, resolvedAccount)
            return SparkWallet(config, signer)
        }

        /**
         * Create a wallet from pre-derived account key material.
         *
         * Use this when the host application owns its own BIP-32 derivation (e.g. a
         * shared backend key store) and wants to hand the SDK only the account-level
         * key — no mnemonic crosses the SDK boundary.
         *
         * @param config Network and operator configuration.
         * @param accountKey Exactly 64 bytes: the first 32 are the BIP-32 private key,
         *   the last 32 are the chain code. The caller should zero the array after the
         *   wallet is constructed.
         * @throws IllegalArgumentException if `accountKey.size != 64`.
         */
        fun fromAccountKey(config: SparkConfig = SparkConfig(), accountKey: ByteArray,): SparkWallet {
            require(accountKey.size == 64) { "Account key must be 64 bytes" }
            val key = accountKey.copyOfRange(0, 32)
            val chainCode = accountKey.copyOfRange(32, 64)
            val signer = SparkSigner.fromAccountKey(key, chainCode)
            return SparkWallet(config, signer)
        }

        /**
         * Create a wallet backed by a custom [SparkSignerProtocol] implementation —
         * for example, one that defers identity-key operations to the Android Keystore,
         * StrongBox, or a hardware token.
         *
         * @param config Network and operator configuration.
         * @param signer Implementation of [SparkSignerProtocol]. The SDK never inspects
         *   the underlying private material; it only invokes the protocol methods.
         */
        fun fromSigner(config: SparkConfig = SparkConfig(), signer: SparkSignerProtocol,): SparkWallet = SparkWallet(config, signer)
    }

    /**
     * Export the wallet's 64-byte account key (32-byte private key + 32-byte chain
     * code). Useful for backing up the account material when the wallet was created
     * from a mnemonic and you want to migrate to [fromAccountKey].
     *
     * **Security:** the returned `ByteArray` contains highly sensitive material. Zero
     * it (`buf.fill(0)`) as soon as you have persisted it to secure storage.
     *
     * @throws ClassCastException if the wallet was created via [fromSigner] with a
     *   non-[SparkSigner] implementation that cannot export raw key bytes.
     */
    fun exportAccountKey(): ByteArray = (signer as SparkSigner).exportAccountKey()

    /**
     * Shut down the wallet's gRPC channels and cancel the internal coroutine scope.
     *
     * Safe to call more than once. Always pair construction with a `try / finally`
     * (or a coroutine `use { }`-style helper) to avoid leaking gRPC connections.
     */
    suspend fun close() {
        connectionManager.close()
        scope.cancel()
    }

    internal suspend fun getAuthMetadata(soAddress: String): Metadata = authenticator.getAuthMetadata(connectionManager, soAddress, signer)

    internal suspend fun getCoordinatorStub(): SparkServiceGrpcKt.SparkServiceCoroutineStub {
        val channel = connectionManager.getChannel(config.coordinatorAddress)
        val metadata = getAuthMetadata(config.coordinatorAddress)
        return SparkServiceGrpcKt.SparkServiceCoroutineStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
    }

    internal suspend fun getTokenStub(): SparkTokenServiceGrpcKt.SparkTokenServiceCoroutineStub {
        val channel = connectionManager.getChannel(config.coordinatorAddress)
        val metadata = getAuthMetadata(config.coordinatorAddress)
        return SparkTokenServiceGrpcKt.SparkTokenServiceCoroutineStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
    }

    internal suspend fun getTokenStubWithIdempotency(idempotencyKey: String?,): SparkTokenServiceGrpcKt.SparkTokenServiceCoroutineStub {
        val channel = connectionManager.getChannel(config.coordinatorAddress)
        val metadata = getAuthMetadata(config.coordinatorAddress)
        if (idempotencyKey != null) {
            val key = Metadata.Key.of("x-idempotency-key", Metadata.ASCII_STRING_MARSHALLER)
            metadata.put(key, idempotencyKey)
        }
        return SparkTokenServiceGrpcKt.SparkTokenServiceCoroutineStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
    }
}
