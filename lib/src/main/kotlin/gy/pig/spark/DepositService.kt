package gy.pig.spark

import com.google.protobuf.ByteString
import okhttp3.RequestBody.Companion.toRequestBody
import spark.Spark
import uniffi.spark_frost.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

suspend fun SparkWallet.getDepositAddress(): DepositAddress {
    val stub = getCoordinatorStub()

    val request = Spark.GenerateDepositAddressRequest.newBuilder()
        .setNetwork(config.network.toProto())
        .setSigningPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .build()

    val response = stub.generateDepositAddress(request)
    val addr = response.depositAddress

    return DepositAddress(
        address = addr.address,
        leafId = "",
        userPublicKey = signer.identityPublicKey,
        verifyingKey = addr.verifyingKey.toByteArray(),
    )
}

suspend fun SparkWallet.getStaticDepositAddress(): StaticDepositAddress {
    val stub = getCoordinatorStub()

    val request = Spark.GenerateStaticDepositAddressRequest.newBuilder()
        .setNetwork(config.network.toProto())
        .setSigningPublicKey(ByteString.copyFrom(signer.depositPublicKey))
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .build()

    val response = stub.generateStaticDepositAddress(request)

    return StaticDepositAddress(
        address = response.depositAddress.address,
        verifyingKey = response.depositAddress.verifyingKey.toByteArray(),
    )
}

suspend fun SparkWallet.queryUnusedDepositAddresses(limit: Int = 100, offset: Int = 0,): List<UnusedDepositAddress> {
    val stub = getCoordinatorStub()

    val request = Spark.QueryUnusedDepositAddressesRequest.newBuilder()
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setNetwork(config.network.toProto())
        .setLimit(limit.toLong())
        .setOffset(offset.toLong())
        .build()

    val response = stub.queryUnusedDepositAddresses(request)

    return response.depositAddressesList.map { deposit ->
        UnusedDepositAddress(
            address = deposit.depositAddress,
            leafId = deposit.leafId,
            userSigningPublicKey = deposit.userSigningPublicKey.toByteArray(),
            verifyingPublicKey = deposit.verifyingPublicKey.toByteArray(),
        )
    }
}

suspend fun SparkWallet.getDepositFeeEstimate(transactionId: String, outputIndex: UInt = 0u,): DepositFeeEstimate {
    val result = sspClient.executeRaw(
        query = GraphQLQueries.STATIC_DEPOSIT_QUOTE,
        variables = mapOf(
            "transaction_id" to transactionId,
            "output_index" to outputIndex.toInt(),
            "network" to config.network.networkGraphQL,
        ),
    )

    val quote = result.getJSONObject("static_deposit_quote")
    return DepositFeeEstimate(
        creditAmountSats = quote.getLong("credit_amount_sats"),
        quoteSignature = quote.getString("signature"),
    )
}

suspend fun SparkWallet.claimStaticDeposit(transactionId: String, outputIndex: UInt = 0u,): String {
    val feeEstimate = getDepositFeeEstimate(transactionId, outputIndex)

    // Build signing payload matching Swift SDK
    val staticSecretKey = signer.deriveStaticDepositKey(0)
    val depositSecretKeyHex = staticSecretKey.toHexString()

    val payload = java.io.ByteArrayOutputStream()
    payload.write("claim_static_deposit".toByteArray(Charsets.UTF_8))
    payload.write(config.network.networkGraphQL.lowercase().toByteArray(Charsets.UTF_8))
    payload.write(transactionId.toByteArray(Charsets.UTF_8))
    payload.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(outputIndex.toInt()).array())
    payload.write(0) // requestType = Fixed
    payload.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(feeEstimate.creditAmountSats).array())
    val sigBytes = try {
        feeEstimate.quoteSignature.hexToByteArray()
    } catch (_: Exception) {
        feeEstimate.quoteSignature.toByteArray(Charsets.UTF_8)
    }
    payload.write(sigBytes)

    val payloadHash = sha256(payload.toByteArray())
    val signature = signer.signWithIdentityKey(payloadHash)

    val result = sspClient.executeRaw(
        query = GraphQLMutations.CLAIM_STATIC_DEPOSIT,
        variables = mapOf(
            "transaction_id" to transactionId,
            "output_index" to outputIndex.toInt(),
            "network" to config.network.networkGraphQL,
            "request_type" to "FIXED_AMOUNT",
            "credit_amount_sats" to feeEstimate.creditAmountSats,
            "deposit_secret_key" to depositSecretKeyHex,
            "signature" to signature.toHexString(),
            "quote_signature" to feeEstimate.quoteSignature,
        ),
    )

    return result.getJSONObject("claim_static_deposit").getString("transfer_id")
}

suspend fun SparkWallet.queryStaticDepositAddresses(): List<StaticDepositAddress> {
    val stub = getCoordinatorStub()

    val request = Spark.QueryStaticDepositAddressesRequest.newBuilder()
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setNetwork(config.network.toProto())
        .setHashVariant(Spark.HashVariant.HASH_VARIANT_V2)
        .build()

    val response = stub.queryStaticDepositAddresses(request)

    return response.depositAddressesList.map { deposit ->
        StaticDepositAddress(
            address = deposit.depositAddress,
            verifyingKey = deposit.verifyingPublicKey.toByteArray(),
        )
    }
}

data class DepositUtxo(val txid: String, val vout: UInt,)

suspend fun SparkWallet.getUtxosForDepositAddress(address: String, excludeClaimed: Boolean = true,): List<DepositUtxo> {
    val stub = getCoordinatorStub()

    val request = Spark.GetUtxosForAddressRequest.newBuilder()
        .setAddress(address)
        .setNetwork(config.network.toProto())
        .setExcludeClaimed(excludeClaimed)
        .build()

    val response = stub.getUtxosForAddress(request)

    return response.utxosList.map { utxo ->
        DepositUtxo(
            txid = utxo.txid.toByteArray().toHexString(),
            vout = utxo.vout.toUInt(),
        )
    }
}

suspend fun SparkWallet.claimStaticDepositWithMaxFee(transactionId: String, maxFee: Long, outputIndex: UInt = 0u,): String? {
    val quote = getDepositFeeEstimate(transactionId, outputIndex)

    val rawTx = fetchRawTransaction(transactionId)
    val output = parseTxOutput(rawTx, outputIndex.toInt())
    val totalAmount = output.first
    val fee = totalAmount - quote.creditAmountSats

    if (fee > maxFee) return null

    return claimStaticDeposit(transactionId, outputIndex)
}

private const val INITIAL_ROOT_NODE_SEQUENCE: UInt = 0u
private const val INITIAL_REFUND_SEQUENCE: UInt = 2000u

suspend fun SparkWallet.claimDeposit(txID: String, vout: UInt = 0u) {
    val stub = getCoordinatorStub()
    val networkStr = config.network.networkString

    // Fetch raw tx from mempool API
    val rawTx = fetchRawTransaction(txID)

    // Query unused deposit addresses to find the matching one
    val unusedAddresses = queryUnusedDepositAddresses()
    val depositInfo = unusedAddresses.firstOrNull { it.leafId.isNotEmpty() }
        ?: throw SparkError.InvalidResponse("No unused deposit address found. Generate one first with getDepositAddress().")

    val leafId = depositInfo.leafId
    val verifyingKey = depositInfo.verifyingPublicKey

    val signingKey = signer.deriveLeafSigningKey(leafId)
    val signingPubKey = getPublicKeyBytes(signingKey, true)

    // Create root node transaction pair
    val rootNodeTx = constructNodeTxPair(
        parentTx = rawTx,
        vout = vout,
        address = depositInfo.address,
        sequence = INITIAL_ROOT_NODE_SEQUENCE,
        directSequence = SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )

    // Create initial timelock refund txs
    val refundTrio = constructRefundTxTrio(
        cpfpNodeTx = rootNodeTx.cpfp.tx,
        directNodeTx = null,
        vout = 0u,
        receivingPubkey = signingPubKey,
        network = networkStr,
        sequence = INITIAL_REFUND_SEQUENCE,
        directSequence = INITIAL_REFUND_SEQUENCE + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )

    // Get signing commitments (3: root, cpfpRefund, directFromCpfpRefund)
    val commitmentsReq = Spark.GetSigningCommitmentsRequest.newBuilder()
        .setCount(3)
        .setNodeIdCount(1)
        .build()
    val commitmentsResp = stub.getSigningCommitments(commitmentsReq)
    val allCommitments = commitmentsResp.signingCommitmentsList

    val rootJob = FrostSigningHelper.buildSigningJob(
        leafID = leafId,
        signingKey = signingKey,
        verifyingKey = verifyingKey,
        rawTx = rootNodeTx.cpfp.tx,
        sighash = rootNodeTx.cpfp.sighash,
        soCommitments = allCommitments[0].signingNonceCommitmentsMap,
    )
    val refundJob = FrostSigningHelper.buildSigningJob(
        leafID = leafId,
        signingKey = signingKey,
        verifyingKey = verifyingKey,
        rawTx = refundTrio.cpfpRefund.tx,
        sighash = refundTrio.cpfpRefund.sighash,
        soCommitments = allCommitments[1].signingNonceCommitmentsMap,
    )
    val directFromCpfpRefundJob = FrostSigningHelper.buildSigningJob(
        leafID = leafId,
        signingKey = signingKey,
        verifyingKey = verifyingKey,
        rawTx = refundTrio.directFromCpfpRefund.tx,
        sighash = refundTrio.directFromCpfpRefund.sighash,
        soCommitments = allCommitments[2].signingNonceCommitmentsMap,
    )

    // txid reversed for protobuf
    val txidBytes = txID.hexToByteArray().reversedArray()

    val utxo = Spark.UTXO.newBuilder()
        .setRawTx(ByteString.copyFrom(rawTx))
        .setVout(vout.toInt())
        .setNetwork(config.network.toProto())
        .setTxid(ByteString.copyFrom(txidBytes))
        .build()

    val finalizeReq = Spark.FinalizeDepositTreeCreationRequest.newBuilder()
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setOnChainUtxo(utxo)
        .setRootTxSigningJob(rootJob)
        .setRefundTxSigningJob(refundJob)
        .setDirectFromCpfpRefundTxSigningJob(directFromCpfpRefundJob)
        .build()

    stub.finalizeDepositTreeCreation(finalizeReq)
}

suspend fun SparkWallet.refundStaticDeposit(depositTransactionId: String, outputIndex: UInt = 0u, destinationAddress: String, satsPerVbyte: Long,): String {
    require(satsPerVbyte <= 150) { "satsPerVbyte must be <= 150" }

    val estimatedVbytes = 194L
    val fee = satsPerVbyte * estimatedVbytes
    require(fee >= 194) { "Fee must be at least 194 sats" }

    val stub = getCoordinatorStub()

    // Fetch deposit tx to know the output value
    val rawDepositTx = fetchRawTransaction(depositTransactionId)
    val depositOutput = parseTxOutput(rawDepositTx, outputIndex.toInt())
    val creditAmountSats = depositOutput.first - fee
    require(creditAmountSats > 0) { "Fee too large, credit amount must be > 0" }

    // Build spend tx
    val spendTx = constructSpendTx(
        depositTxId = depositTransactionId,
        outputIndex = outputIndex,
        destinationAddress = destinationAddress,
        amountSats = creditAmountSats.toULong(),
        network = config.network.networkString,
    )

    // Compute sighash
    val sighash = computeMultiInputSighashUniffi(
        tx = spendTx,
        inputIndex = 0u,
        prevOutScripts = listOf(depositOutput.second),
        prevOutValues = listOf(depositOutput.first.toULong()),
    )

    val staticKey = signer.deriveStaticDepositKey(0)
    val staticPubKey = getPublicKeyBytes(staticKey, true)
    val networkStr = config.network.networkGraphQL.lowercase()

    // Build signing payload
    val payload = java.io.ByteArrayOutputStream()
    payload.write("claim_static_deposit".toByteArray(Charsets.UTF_8))
    payload.write(networkStr.toByteArray(Charsets.UTF_8))
    payload.write(depositTransactionId.toByteArray(Charsets.UTF_8))
    payload.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(outputIndex.toInt()).array())
    payload.write(2) // requestType = Refund
    payload.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(creditAmountSats).array())
    payload.write(sighash.toHexString().toByteArray(Charsets.UTF_8))
    val payloadHash = sha256(payload.toByteArray())
    val userSignature = signer.signWithIdentityKey(payloadHash)

    // FROST nonce
    val keyPackage = KeyPackage(secretKey = staticKey, publicKey = staticPubKey, verifyingKey = staticPubKey)
    val nonceResult = frostNonce(keyPackage)

    val signingJob = FrostSigningHelper.buildUnsignedJob(
        signingPublicKey = staticPubKey,
        rawTx = spendTx,
        hidingNonce = nonceResult.commitment.hiding,
        bindingNonce = nonceResult.commitment.binding,
    )

    val txidBytes = depositTransactionId.hexToByteArray().reversedArray()
    val utxo = Spark.UTXO.newBuilder()
        .setTxid(ByteString.copyFrom(txidBytes))
        .setVout(outputIndex.toInt())
        .setNetwork(config.network.toProto())
        .build()

    val refundReq = Spark.InitiateStaticDepositUtxoRefundRequest.newBuilder()
        .setOnChainUtxo(utxo)
        .setRefundTxSigningJob(signingJob)
        .setUserSignature(ByteString.copyFrom(userSignature))
        .build()

    val refundResp = stub.initiateStaticDepositUtxoRefund(refundReq)

    val signingResult = refundResp.refundTxSigningResult
    val verifyingKey = refundResp.depositAddress.verifyingPublicKey.toByteArray()

    // Sign FROST and aggregate
    val realKeyPackage = KeyPackage(secretKey = staticKey, publicKey = staticPubKey, verifyingKey = verifyingKey)
    val nativeCommitments = signingResult.signingNonceCommitmentsMap.mapValues { (_, proto) ->
        SigningCommitment(hiding = proto.hiding.toByteArray(), binding = proto.binding.toByteArray())
    }

    val selfSignature = signFrost(
        msg = sighash,
        keyPackage = realKeyPackage,
        nonce = nonceResult.nonce,
        selfCommitment = nonceResult.commitment,
        statechainCommitments = nativeCommitments,
        adaptorPublicKey = null,
    )

    val soSignatures = signingResult.signatureSharesMap.mapValues { it.value.toByteArray() }
    val soPublicKeys = signingResult.publicKeysMap.mapValues { it.value.toByteArray() }

    val aggregatedSig = aggregateFrost(
        msg = sighash, statechainCommitments = nativeCommitments,
        selfCommitment = nonceResult.commitment,
        statechainSignatures = soSignatures, selfSignature = selfSignature,
        statechainPublicKeys = soPublicKeys, selfPublicKey = staticPubKey,
        verifyingKey = verifyingKey, adaptorPublicKey = null,
    )

    // Add witness to spend tx
    val signedTx = addWitnessToTx(spendTx, aggregatedSig)
    return signedTx.toHexString()
}

suspend fun SparkWallet.refundAndBroadcastStaticDeposit(
    depositTransactionId: String,
    outputIndex: UInt = 0u,
    destinationAddress: String,
    satsPerVbyte: Long,
): String {
    val txHex = refundStaticDeposit(
        depositTransactionId = depositTransactionId,
        outputIndex = outputIndex,
        destinationAddress = destinationAddress,
        satsPerVbyte = satsPerVbyte,
    )
    return broadcastTransaction(txHex)
}

suspend fun SparkWallet.broadcastTransaction(txHex: String): String {
    val baseURL = when (config.network) {
        SparkNetwork.MAINNET -> "https://mempool.space/api"
        SparkNetwork.REGTEST -> "http://localhost:3000"
    }

    val client = okhttp3.OkHttpClient()
    val request = okhttp3.Request.Builder()
        .url("$baseURL/tx")
        .post(txHex.toRequestBody(null))
        .build()

    val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        client.newCall(request).execute()
    }

    if (!response.isSuccessful) {
        val body = response.body?.string() ?: ""
        throw SparkError.InvalidResponse("Failed to broadcast tx: $body")
    }

    return response.body?.string()?.trim() ?: ""
}

// ── Internal helpers ──

internal suspend fun SparkWallet.fetchRawTransaction(txID: String): ByteArray {
    val baseURL = when (config.network) {
        SparkNetwork.MAINNET -> "https://mempool.space/api"
        SparkNetwork.REGTEST -> "http://localhost:3000"
    }

    val client = okhttp3.OkHttpClient()
    val request = okhttp3.Request.Builder().url("$baseURL/tx/$txID/hex").build()

    val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        client.newCall(request).execute()
    }

    if (!response.isSuccessful) {
        throw SparkError.InvalidResponse("Failed to fetch raw transaction $txID")
    }

    val hexString = response.body?.string()?.trim()
        ?: throw SparkError.InvalidResponse("Empty response for transaction $txID")
    return hexString.hexToByteArray()
}

/** Parse output value and scriptPubKey from a raw tx at given vout. */
internal fun parseTxOutput(rawTx: ByteArray, vout: Int): Pair<Long, ByteArray> {
    var offset = 4 // skip version
    if (rawTx.size > 5 && rawTx[offset] == 0x00.toByte() && rawTx[offset + 1] == 0x01.toByte()) {
        offset += 2 // skip witness marker+flag
    }

    // Skip inputs
    val (inputCount, inputCountLen) = readVarInt(rawTx, offset)
    offset += inputCountLen
    for (i in 0 until inputCount.toInt()) {
        offset += 36
        val (scriptLen, scriptLenLen) = readVarInt(rawTx, offset)
        offset += scriptLenLen + scriptLen.toInt() + 4
    }

    // Read outputs
    val (outputCount, outputCountLen) = readVarInt(rawTx, offset)
    offset += outputCountLen
    for (i in 0 until outputCount.toInt()) {
        // value (8 bytes LE)
        var value = 0L
        for (j in 0 until 8) value = value or ((rawTx[offset + j].toLong() and 0xFF) shl (j * 8))
        offset += 8

        val (scriptLen, scriptLenLen) = readVarInt(rawTx, offset)
        offset += scriptLenLen
        val script = rawTx.copyOfRange(offset, offset + scriptLen.toInt())
        offset += scriptLen.toInt()

        if (i == vout) return value to script
    }
    throw SparkError.InvalidResponse("Output index $vout not found in transaction")
}

/** Build a simple 1-input 1-output spend transaction. */
internal fun constructSpendTx(depositTxId: String, outputIndex: UInt, destinationAddress: String, amountSats: ULong, network: String,): ByteArray {
    val buf = java.io.ByteArrayOutputStream()

    // Version 3 (LE)
    buf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(3).array())
    // Segwit marker + flag
    buf.write(byteArrayOf(0x00, 0x01))
    // 1 input
    buf.write(1)
    // txid reversed + vout + empty scriptSig + sequence
    buf.write(depositTxId.hexToByteArray().reversedArray())
    buf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(outputIndex.toInt()).array())
    buf.write(0) // scriptSig length = 0
    buf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).array()) // 0xFFFFFFFF

    // 1 output
    buf.write(1)
    buf.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(amountSats.toLong()).array())

    val scriptPubKey = decodeAddressToScript(destinationAddress, network)
    buf.write(scriptPubKey.size)
    buf.write(scriptPubKey)

    // Witness placeholder
    buf.write(0)
    // Locktime
    buf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())

    return buf.toByteArray()
}

internal fun decodeAddressToScript(address: String, network: String): ByteArray {
    val (witnessVersion, programData) = Bech32m.decode(address)
    val script = java.io.ByteArrayOutputStream()
    if (witnessVersion == 0) {
        script.write(0x00) // OP_0
    } else {
        script.write(0x50 + witnessVersion) // OP_1..OP_16
    }
    script.write(programData.size)
    script.write(programData)
    return script.toByteArray()
}

internal fun addWitnessToTx(rawTx: ByteArray, witness: ByteArray): ByteArray {
    var offset = 4
    val hasWitness = rawTx.size > 5 && rawTx[offset] == 0x00.toByte() && rawTx[offset + 1] == 0x01.toByte()
    if (hasWitness) offset += 2

    // Skip inputs
    val (inputCount, inputCountLen) = readVarInt(rawTx, offset)
    offset += inputCountLen
    for (i in 0 until inputCount.toInt()) {
        offset += 36
        val (scriptLen, scriptLenLen) = readVarInt(rawTx, offset)
        offset += scriptLenLen + scriptLen.toInt() + 4
    }

    // Skip outputs
    val (outputCount, outputCountLen) = readVarInt(rawTx, offset)
    offset += outputCountLen
    for (i in 0 until outputCount.toInt()) {
        offset += 8
        val (scriptLen, scriptLenLen) = readVarInt(rawTx, offset)
        offset += scriptLenLen + scriptLen.toInt()
    }

    val result = java.io.ByteArrayOutputStream()
    // version + marker + flag
    result.write(rawTx, 0, 4)
    result.write(byteArrayOf(0x00, 0x01))

    // inputs + outputs
    val inputOutputStart = if (hasWitness) 6 else 4
    result.write(rawTx, inputOutputStart, offset - inputOutputStart)

    // Witness: 1 item (schnorr signature)
    result.write(1)
    result.write(witness.size)
    result.write(witness)

    // Locktime (last 4 bytes)
    result.write(rawTx, rawTx.size - 4, 4)

    return result.toByteArray()
}
