package gy.pig.spark

import java.math.BigInteger
import java.util.Date

/**
 * Three-way breakdown of the wallet's sat balance.
 *
 * Mirrors `SparkSDK/Models.SatsBalance` from the official Swift SDK so the
 * Kotlin and Swift surfaces compose into the same numbers when the same
 * wallet is queried from both platforms.
 *
 * - [available]: immediately spendable. Sum of leaf nodes whose protocol
 *   status is `AVAILABLE`.
 * - [owned]: [available] plus value locked in outgoing transfers/swaps
 *   (`TRANSFER_LOCKED`, `SPLIT_LOCKED`, `AGGREGATE_LOCK`, `RENEW_LOCKED`).
 *   Use this for "how much do I own right now" displays where in-flight
 *   sends should not appear to vanish.
 * - [incoming]: pending inbound transfers that have not been claimed yet,
 *   plus on-chain deposits whose nodes are still in `CREATING` state.
 *   Add to [available] to mirror what most wallet UIs label "balance"
 *   while a payment is in flight.
 */
public data class SatsBalance(
    public val available: Long,
    public val owned: Long,
    public val incoming: Long,
)

public data class WalletBalance(
    public val satsBalance: SatsBalance,
    public val leaves: List<SparkLeaf>,
) {
    /**
     * Spendable balance only. Equivalent to [SatsBalance.available].
     *
     * Retained as a deprecated accessor so callers compiled against
     * v0.1.0 keep working; new callers should read [satsBalance.available]
     * or [satsBalance.available] + [satsBalance.incoming] depending on the
     * UI need.
     */
    @Deprecated(
        message = "Use satsBalance.available (spendable) or satsBalance.available + .incoming (with in-flight credits).",
        replaceWith = ReplaceWith("satsBalance.available"),
    )
    public val totalSats: Long get() = satsBalance.available
}

data class SparkLeaf(val id: String, val treeID: String, val valueSats: Long, val status: String, internal val node: spark.Spark.TreeNode? = null,)

data class SparkTransfer(
    val id: String,
    val senderIdentityPublicKey: String,
    val receiverIdentityPublicKey: String,
    val totalValueSats: Long,
    val status: String,
    val type: String,
    val createdAt: Date,
)

data class DepositAddress(val address: String, val leafId: String, val userPublicKey: ByteArray, val verifyingKey: ByteArray,) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DepositAddress) return false
        return address == other.address && leafId == other.leafId
    }
    override fun hashCode(): Int = address.hashCode() * 31 + leafId.hashCode()
}

data class StaticDepositAddress(val address: String, val verifyingKey: ByteArray,) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StaticDepositAddress) return false
        return address == other.address
    }
    override fun hashCode(): Int = address.hashCode()
}

data class LightningInvoice(val paymentRequest: String, val paymentHash: String, val amountSats: Long, val expiresAt: Date,)

data class FeeQuote(val feeSats: Long, val feeRateSatsPerVbyte: Long,)

data class UnusedDepositAddress(val address: String, val leafId: String, val userSigningPublicKey: ByteArray, val verifyingPublicKey: ByteArray,) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnusedDepositAddress) return false
        return address == other.address && leafId == other.leafId
    }
    override fun hashCode(): Int = address.hashCode() * 31 + leafId.hashCode()
}

data class DepositFeeEstimate(val creditAmountSats: Long, val quoteSignature: String,)

data class WalletSettings(val privateEnabled: Boolean, val ownerIdentityPublicKey: String,)

sealed class SparkEvent {
    data object Connected : SparkEvent()
    data class TransferReceived(val transfer: SparkTransfer) : SparkEvent()
    data class TransferSent(val transfer: SparkTransfer) : SparkEvent()
    data class DepositConfirmed(val treeID: String) : SparkEvent()
}

enum class TransferDirection {
    SENT,
    RECEIVED,
    BOTH,
}

// MARK: - Token Types

data class TokenMetadataInfo(
    val tokenIdentifier: String,
    val rawTokenIdentifier: ByteArray,
    val issuerPublicKey: ByteArray,
    val tokenName: String,
    val tokenTicker: String,
    val decimals: UInt,
    val maxSupply: ByteArray, // 16-byte uint128 big-endian
    val isFreezable: Boolean,
    val extraMetadata: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TokenMetadataInfo) return false
        return tokenIdentifier == other.tokenIdentifier
    }
    override fun hashCode(): Int = tokenIdentifier.hashCode()
}

data class TokenBalance(val tokenMetadata: TokenMetadataInfo, val ownedBalance: BigInteger, val availableToSendBalance: BigInteger,)

data class TokenOutputInfo(
    val id: String?,
    val ownerPublicKey: ByteArray,
    val tokenIdentifier: ByteArray,
    val tokenAmount: BigInteger,
    val previousTransactionHash: ByteArray,
    val previousTransactionVout: UInt,
    val status: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TokenOutputInfo) return false
        return id == other.id && previousTransactionVout == other.previousTransactionVout
    }
    override fun hashCode(): Int = (id?.hashCode() ?: 0) * 31 + previousTransactionVout.hashCode()
}

data class TokenCreationResult(val transactionHash: String, val tokenIdentifier: String?,)

enum class TokenOutputSelectionStrategy {
    SMALL_FIRST,
    LARGE_FIRST,
}

// MARK: - UInt128 helpers
//
// The Spark protocol represents token amounts and max-supply as 16-byte big-endian
// unsigned 128-bit integers. Kotlin/JVM has no native UInt128, so we use BigInteger
// and validate the range explicitly at the protocol boundary.

/**
 * Decode a 16-byte big-endian unsigned 128-bit integer.
 * Throws [IllegalArgumentException] if [data] is not exactly 16 bytes.
 */
fun decodeUInt128(data: com.google.protobuf.ByteString): BigInteger {
    require(data.size() == 16) { "UInt128 must be exactly 16 bytes, got ${data.size()}" }
    // signum=1 forces unsigned interpretation of the magnitude bytes.
    return BigInteger(1, data.toByteArray())
}

/**
 * Encode an unsigned 128-bit integer as a 16-byte big-endian byte array.
 * Throws [IllegalArgumentException] if [value] is negative or exceeds 2^128 − 1.
 */
fun encodeUInt128(value: BigInteger): ByteArray {
    require(value.signum() >= 0) { "UInt128 must be non-negative, got $value" }
    require(value.bitLength() <= 128) { "UInt128 must fit in 128 bits, got $value" }
    val raw = value.toByteArray() // signed two's-complement, big-endian; may have leading 0x00
    val out = ByteArray(16)
    // Right-align into 16 bytes; if BigInteger added a sign byte that pushed us to 17, skip it.
    val srcOffset = if (raw.size > 16) raw.size - 16 else 0
    val length = minOf(raw.size, 16)
    System.arraycopy(raw, srcOffset, out, 16 - length, length)
    return out
}

// MARK: - SSP Transfer Types

data class TransferWithUserRequest(val sparkId: String, val totalAmountSats: Long?, val userRequest: UserRequest?,)

sealed class UserRequest {
    data class LightningReceive(val info: LightningReceiveInfo) : UserRequest()
    data class LightningSend(val info: LightningSendInfo) : UserRequest()
    data class CoopExit(val info: CoopExitInfo) : UserRequest()
    data class LeavesSwap(val info: LeavesSwapInfo) : UserRequest()
    data class ClaimStaticDeposit(val info: ClaimStaticDepositInfo) : UserRequest()
    data class Unknown(val typeName: String) : UserRequest()
}

data class LightningReceiveInfo(
    val id: String,
    val status: String,
    val encodedInvoice: String?,
    val paymentHash: String?,
    val amountSats: Long?,
    val memo: String?,
    val paymentPreimage: String?,
)

data class LightningSendInfo(
    val id: String,
    val status: String,
    val encodedInvoice: String?,
    val feeSats: Long?,
    val idempotencyKey: String?,
    val paymentPreimage: String?,
)

data class CoopExitInfo(val id: String, val status: String, val coopExitTxid: String?,)

data class LeavesSwapInfo(val id: String, val status: String,)

data class ClaimStaticDepositInfo(val id: String, val status: String, val transactionId: String?, val outputIndex: Int?,)

// MARK: - Invoice Query Types

data class SparkInvoiceStatus(val invoice: String, val status: String, val satsTransferId: String?, val tokenTransactionHash: String?,)
