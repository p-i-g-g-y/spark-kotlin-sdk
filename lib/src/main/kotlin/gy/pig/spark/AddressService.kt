package gy.pig.spark

/** Get the Spark address for this wallet (bech32m-encoded identity public key). */
fun SparkWallet.getSparkAddress(): String {
    val prefix = when (config.network) {
        SparkNetwork.MAINNET -> "spark"
        SparkNetwork.REGTEST -> "sparkrt"
    }

    // Protobuf wire encoding: field 1, wire type 2 (length-delimited)
    val pubkey = signer.identityPublicKey
    val payload = ByteArray(2 + pubkey.size)
    payload[0] = 10 // (1 << 3) | 2
    payload[1] = pubkey.size.toByte()
    pubkey.copyInto(payload, 2)

    val words = Bech32m.toWords(payload)
    return Bech32m.encode(prefix, words)
}
