package gy.pig.spark

import com.google.protobuf.ByteString
import spark.Spark

/**
 * Compute the wallet's full sats balance.
 *
 * Direct port of the official Swift SDK's [`BalanceService.getBalance()`](
 * https://github.com/buildonspark/spark-swift-sdk). Buckets the wallet's
 * leaves locally from a single `query_nodes` round-trip, then adds pending
 * inbound transfers and `CREATING` deposits to the incoming bucket.
 *
 * - **available** = sum of `AVAILABLE` node values. Immediately spendable.
 * - **owned**     = available + sum of values whose status is in
 *   `{TRANSFER_LOCKED, SPLIT_LOCKED, AGGREGATE_LOCK, RENEW_LOCKED}`.
 *   These are leaves locked behind in-flight outgoing operations the
 *   wallet itself initiated; the user still owns them.
 * - **incoming**  = `queryPendingTransfers()` totals + sum of `CREATING`
 *   node values (on-chain deposits the coordinator hasn't finalized yet,
 *   matching the TS SDK).
 *
 * Failures in `queryPendingTransfers()` or `getTokenBalances()` propagate
 * to the caller — same contract as the Swift SDK. Callers that want
 * best-effort behavior should wrap this in their own `try/catch`.
 *
 * @return [WalletBalance] with the breakdown, token balances, and spendable leaves.
 */
public suspend fun SparkWallet.getBalance(): WalletBalance {
    val stub = getCoordinatorStub()

    // Query all nodes (no status filter) so we can compute owned + available
    // locally — mirrors Swift exactly.
    val nodesRequest = Spark.QueryNodesRequest.newBuilder()
        .setOwnerIdentityPubkey(ByteString.copyFrom(signer.identityPublicKey))
        .setNetwork(config.network.toProto())
        .build()

    val nodesResponse = stub.queryNodes(nodesRequest)

    var availableSats = 0L
    var ownedSats = 0L
    val leaves = mutableListOf<SparkLeaf>()

    for ((id, node) in nodesResponse.nodesMap) {
        val status = node.status.toString()
        when {
            status == "AVAILABLE" -> {
                availableSats += node.value
                ownedSats += node.value
                leaves.add(
                    SparkLeaf(
                        id = id,
                        treeID = node.treeId,
                        valueSats = node.value,
                        status = status,
                        node = node,
                    ),
                )
            }
            status in LOCKED_STATUSES -> {
                ownedSats += node.value
            }
        }
    }

    // Incoming: pending inbound transfers + deposits still being created.
    // Errors propagate — Swift parity.
    var incomingSats = 0L
    for (transfer in queryPendingTransfers()) {
        incomingSats += transfer.totalValue
    }
    for ((_, node) in nodesResponse.nodesMap) {
        if (node.status.toString() == "CREATING") {
            incomingSats += node.value
        }
    }

    val tokenBalances = getTokenBalances()

    return WalletBalance(
        satsBalance = SatsBalance(
            available = availableSats,
            owned = ownedSats,
            incoming = incomingSats,
        ),
        tokenBalances = tokenBalances,
        leaves = leaves,
    )
}

/**
 * List of spendable (status `AVAILABLE`) leaf nodes owned by this wallet.
 * Returned in coordinator-defined order. Used by the withdraw flow to
 * select inputs.
 */
public suspend fun SparkWallet.getLeaves(): List<SparkLeaf> {
    val stub = getCoordinatorStub()

    val request = Spark.QueryNodesRequest.newBuilder()
        .setOwnerIdentityPubkey(ByteString.copyFrom(signer.identityPublicKey))
        .setNetwork(config.network.toProto())
        .build()

    val response = stub.queryNodes(request)

    return response.nodesMap.mapNotNull { (id, node) ->
        if (node.status.toString() != "AVAILABLE") return@mapNotNull null
        SparkLeaf(
            id = id,
            treeID = node.treeId,
            valueSats = node.value,
            status = node.status.toString(),
            node = node,
        )
    }
}

private val LOCKED_STATUSES = setOf(
    "TRANSFER_LOCKED",
    "SPLIT_LOCKED",
    "AGGREGATE_LOCK",
    "RENEW_LOCKED",
)

internal fun SparkNetwork.toProto(): Spark.Network = when (this) {
    SparkNetwork.MAINNET -> Spark.Network.MAINNET
    SparkNetwork.REGTEST -> Spark.Network.REGTEST
}
