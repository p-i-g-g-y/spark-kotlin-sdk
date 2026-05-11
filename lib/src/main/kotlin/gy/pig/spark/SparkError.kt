package gy.pig.spark

/**
 * Typed errors thrown by every public SDK method.
 *
 * `SparkError` is a `sealed class`, so an exhaustive `when` over its subtypes is
 * checked by the compiler. Use the structured payload (e.g. [InsufficientBalance.need]
 * / [InsufficientBalance.have]) to drive user-facing messages instead of parsing the
 * `message` string.
 *
 * ```kotlin
 * try {
 *     wallet.send(receiverIdentityPublicKey = pub, amountSats = 500)
 * } catch (e: SparkError) {
 *     when (e) {
 *         is SparkError.InsufficientBalance -> ui.showLowBalance(e.need, e.have)
 *         is SparkError.AuthenticationFailed -> auth.reLogin()
 *         is SparkError.GrpcError,
 *         is SparkError.GraphqlError,
 *         is SparkError.FrostSigningFailed -> ui.showTransientFailure()
 *         else -> log.warn("Unexpected SDK error", e)
 *     }
 * }
 * ```
 */
sealed class SparkError(override val message: String) : Exception(message) {
    /** BIP-39 / BIP-32 derivation failed (invalid mnemonic, invalid path, etc.). */
    data object KeyDerivationFailed : SparkError("Key derivation failed")

    /** The wallet doesn't have enough spendable sats to cover the requested transfer. */
    data class InsufficientBalance(val need: Long, val have: Long) : SparkError("Insufficient balance: need $need sats, have $have sats")

    /** SSP or Spark operator rejected the auth handshake. */
    data class AuthenticationFailed(val msg: String) : SparkError("Authentication failed: $msg")

    /** A gRPC call to a Spark operator failed (network, deadline, status code). */
    data class GrpcError(val msg: String) : SparkError("gRPC error: $msg")

    /** A GraphQL call to the SSP failed (HTTP, GraphQL `errors[]`, schema). */
    data class GraphqlError(val msg: String) : SparkError("GraphQL error: $msg")

    /** A response from the operator or SSP was structurally invalid or missing a field. */
    data class InvalidResponse(val msg: String) : SparkError("Invalid response: $msg")

    /** A FROST signing round failed (operator misbehaviour, key tweak mismatch). */
    data class FrostSigningFailed(val msg: String) : SparkError("FROST signing failed: $msg")

    /** The requested capability isn't implemented in this SDK version yet. */
    data class NotImplemented(val msg: String) : SparkError("Not implemented: $msg")

    /** A token transaction failed validation (bad metadata, invalid amount, etc.). */
    data class TokenValidationFailed(val msg: String) : SparkError("Token validation failed: $msg")

    /** The wallet doesn't have enough of the given token to cover the transfer. */
    data class InsufficientTokenBalance(val token: String, val need: String, val have: String) :
        SparkError("Insufficient token balance for $token: need $need, have $have")
}
