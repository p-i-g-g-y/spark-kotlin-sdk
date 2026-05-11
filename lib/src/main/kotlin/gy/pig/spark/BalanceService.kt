package gy.pig.spark

import com.google.protobuf.ByteString
import spark.Spark

/**
 * Compute the wallet's full sats balance.
 *
 * The Spark coordinator's `query_balance` RPC returns a single rolled-up
 * number that conflates spendable, locked, and not-yet-claimed value. UI
 * code typically needs the breakdown — "spendable now", "owned including
 * in-flight outgoing", and "incoming credit not yet finalized". To match
 * the Swift SDK's behavior we therefore compute the buckets locally from
 * `query_nodes` (which returns every node with its status) and from
 * `queryPendingTransfers()`. Statuses are compared against the same string
 * constants the Swift SDK uses.
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
 * @return [WalletBalance] with both the breakdown and the spendable-leaf list.
 */
public suspend fun SparkWallet.getBalance(): WalletBalance {
    val stub = getCoordinatorStub()

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

    // Pending inbound transfers + on-chain deposits still in CREATING state.
    // Matches the Swift / TS SDKs.
    var incomingSats = 0L
    try {
        for (transfer in queryPendingTransfers()) {
            incomingSats += transfer.totalValue
        }
    } catch (_: Throwable) {
        // Best-effort: if the pending-transfer query is unavailable
        // (test fakes, transient gRPC error) we still want to return the
        // available/owned breakdown so the UI shows the spendable number.
    }
    for ((_, node) in nodesResponse.nodesMap) {
        if (node.status.toString() == "CREATING") {
            incomingSats += node.value
        }
    }

    return WalletBalance(
        satsBalance = SatsBalance(
            available = availableSats,
            owned = ownedSats,
            incoming = incomingSats,
        ),
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
