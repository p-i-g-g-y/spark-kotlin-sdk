package gy.pig.spark

import com.google.protobuf.ByteString
import spark.Spark

suspend fun SparkWallet.getBalance(): WalletBalance {
    val stub = getCoordinatorStub()

    val request = Spark.QueryBalanceRequest.newBuilder()
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setNetwork(config.network.toProto())
        .build()

    val response = stub.queryBalance(request)

    val leaves = getLeaves()
    return WalletBalance(totalSats = response.balance, leaves = leaves)
}

suspend fun SparkWallet.getLeaves(): List<SparkLeaf> {
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

internal fun SparkNetwork.toProto(): Spark.Network = when (this) {
    SparkNetwork.MAINNET -> Spark.Network.MAINNET
    SparkNetwork.REGTEST -> Spark.Network.REGTEST
}
