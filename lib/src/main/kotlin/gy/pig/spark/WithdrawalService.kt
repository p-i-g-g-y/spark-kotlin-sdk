package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.protobuf.Timestamp
import spark.Spark
import uniffi.spark_frost.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

suspend fun SparkWallet.getWithdrawalFeeEstimate(onChainAddress: String, leafIds: List<String>,): FeeQuote {
    val result = sspClient.executeRaw(
        query = GraphQLMutations.GET_FEE_ESTIMATE,
        variables = mapOf(
            "leaf_external_ids" to leafIds,
            "withdrawal_address" to onChainAddress,
        ),
    )

    val speedFast = result.getJSONObject("coop_exit_fee_estimates").getJSONObject("speed_fast")
    val userFee = speedFast.getJSONObject("user_fee").getLong("original_value")
    val l1Fee = speedFast.getJSONObject("l1_broadcast_fee").getLong("original_value")

    return FeeQuote(
        feeSats = userFee + l1Fee,
        feeRateSatsPerVbyte = 0,
    )
}

private data class WithdrawLeafSigningData(
    val leafId: String,
    val signingKey: ByteArray,
    val verifyingKey: ByteArray,
    val cpfpRefundTx: ByteArray,
    val directRefundTx: ByteArray?,
    val directFromCpfpRefundTx: ByteArray,
    val cpfpNonce: NonceResult,
    val directNonce: NonceResult,
    val directFromCpfpNonce: NonceResult,
    val cpfpNodeTx: ByteArray,
    val directNodeTx: ByteArray?,
    val connectorOutputIndex: Int,
)

/**
 * Withdraw funds to an on-chain Bitcoin address via cooperative exit.
 * Fee is deducted from the withdrawal amount.
 *
 * @param onChainAddress Bitcoin address to withdraw to
 * @param amountSats Amount in sats to withdraw (fee will be deducted from this)
 * @return The L1 transaction ID
 */
suspend fun SparkWallet.withdraw(onChainAddress: String, amountSats: Long,): String {
    val stub = getCoordinatorStub()
    val networkStr = config.network.networkString

    // Step 0: Select leaves (with swap if needed — never overspend)
    val selectedLeaves = selectLeavesWithSwap(amountSats)
    val leafIds = selectedLeaves.map { it.id }

    // Step 1: Request coop exit from SSP — get connector tx
    val transferID = UUID.randomUUID().toString().lowercase()

    val sspResponse = sspClient.executeRaw(
        query = GraphQLMutations.REQUEST_COOP_EXIT,
        variables = mapOf(
            "leaf_external_ids" to leafIds,
            "withdrawal_address" to onChainAddress,
            "exit_speed" to "FAST",
            "withdraw_all" to true,
            "user_outbound_transfer_external_id" to transferID,
        ),
    )

    val request = sspResponse.optJSONObject("request_coop_exit")?.optJSONObject("request")
        ?: throw SparkError.InvalidResponse("Invalid coop exit response")
    val connectorTxHex = request.optString("raw_connector_transaction", "")
    val coopExitTxid = request.optString("coop_exit_txid", "")
    if (connectorTxHex.isEmpty() || coopExitTxid.isEmpty()) {
        throw SparkError.InvalidResponse("Missing connector tx or coop exit txid")
    }

    val connectorTxBytes = connectorTxHex.hexToByteArray()
    val connectorTxId = computeTxId(connectorTxBytes)

    // Step 2: Build LeafRefundTxSigningJobs with connector inputs
    val receiverPubKey = config.sspIdentityPublicKey
    val expiryTime = Timestamp.newBuilder()
        .setSeconds((System.currentTimeMillis() / 1000) + 7 * 24 * 60 * 60 + 300)
        .build()

    val signingJobs = mutableListOf<Spark.LeafRefundTxSigningJob>()
    val leafDataList = mutableListOf<WithdrawLeafSigningData>()

    for (i in selectedLeaves.indices) {
        val leaf = selectedLeaves[i]
        val node = leaf.node ?: throw SparkError.InvalidResponse("Leaf ${leaf.id} missing node data")
        val signingKey = signer.deriveLeafSigningKey(leaf.id)
        val signingPubKey = getPublicKeyBytes(signingKey, true)
        val verifyingKey = node.verifyingPublicKey.toByteArray()

        val (cpfpSequence, directSequence) = computeNextSequences(node.refundTx.toByteArray())

        val cpfpNodeTx = node.nodeTx.toByteArray()
        val directNodeTx = if (node.directTx.isEmpty) null else node.directTx.toByteArray()

        val isZeroNode = isZeroTimelockNode(cpfpNodeTx)

        val refundTrio = constructRefundTxTrio(
            cpfpNodeTx = cpfpNodeTx,
            directNodeTx = directNodeTx,
            vout = 0u,
            receivingPubkey = receiverPubKey,
            network = networkStr,
            sequence = cpfpSequence,
            directSequence = directSequence,
            feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
        )

        // Add connector input to each refund tx
        val connectorInput = makeConnectorInputBytes(connectorTxId, i.toUInt())
        val cpfpRefundWithConnector = addInputToRawTx(refundTrio.cpfpRefund.tx, connectorInput)

        var directRefundWithConnector: ByteArray? = null
        if (refundTrio.directRefund != null && !isZeroNode) {
            directRefundWithConnector = addInputToRawTx(refundTrio.directRefund!!.tx, connectorInput)
        }

        val directFromCpfpRefundWithConnector = addInputToRawTx(refundTrio.directFromCpfpRefund.tx, connectorInput)

        // Generate FROST nonce commitments
        val keyPackage = KeyPackage(secretKey = signingKey, publicKey = signingPubKey, verifyingKey = verifyingKey)
        val cpfpNonce = frostNonce(keyPackage)
        val directNonce = frostNonce(keyPackage)
        val directFromCpfpNonce = frostNonce(keyPackage)

        // Build SigningJob for each refund tx
        val cpfpSigningJob = Spark.SigningJob.newBuilder()
            .setSigningPublicKey(ByteString.copyFrom(signingPubKey))
            .setRawTx(ByteString.copyFrom(cpfpRefundWithConnector))
            .setSigningNonceCommitment(
                common.Common.SigningCommitment.newBuilder()
                    .setHiding(ByteString.copyFrom(cpfpNonce.commitment.hiding))
                    .setBinding(ByteString.copyFrom(cpfpNonce.commitment.binding))
                    .build()
            )
            .build()

        val directFromCpfpSigningJob = Spark.SigningJob.newBuilder()
            .setSigningPublicKey(ByteString.copyFrom(signingPubKey))
            .setRawTx(ByteString.copyFrom(directFromCpfpRefundWithConnector))
            .setSigningNonceCommitment(
                common.Common.SigningCommitment.newBuilder()
                    .setHiding(ByteString.copyFrom(directFromCpfpNonce.commitment.hiding))
                    .setBinding(ByteString.copyFrom(directFromCpfpNonce.commitment.binding))
                    .build()
            )
            .build()

        val leafJobBuilder = Spark.LeafRefundTxSigningJob.newBuilder()
            .setLeafId(leaf.id)
            .setRefundTxSigningJob(cpfpSigningJob)
            .setDirectFromCpfpRefundTxSigningJob(directFromCpfpSigningJob)

        if (directRefundWithConnector != null) {
            val directSigningJob = Spark.SigningJob.newBuilder()
                .setSigningPublicKey(ByteString.copyFrom(signingPubKey))
                .setRawTx(ByteString.copyFrom(directRefundWithConnector))
                .setSigningNonceCommitment(
                    common.Common.SigningCommitment.newBuilder()
                        .setHiding(ByteString.copyFrom(directNonce.commitment.hiding))
                        .setBinding(ByteString.copyFrom(directNonce.commitment.binding))
                        .build()
                )
                .build()
            leafJobBuilder.setDirectRefundTxSigningJob(directSigningJob)
        }

        signingJobs.add(leafJobBuilder.build())

        leafDataList.add(
            WithdrawLeafSigningData(
                leafId = leaf.id,
                signingKey = signingKey,
                verifyingKey = verifyingKey,
                cpfpRefundTx = cpfpRefundWithConnector,
                directRefundTx = directRefundWithConnector,
                directFromCpfpRefundTx = directFromCpfpRefundWithConnector,
                cpfpNonce = cpfpNonce,
                directNonce = directNonce,
                directFromCpfpNonce = directFromCpfpNonce,
                cpfpNodeTx = cpfpNodeTx,
                directNodeTx = directNodeTx,
                connectorOutputIndex = i,
            )
        )
    }

    // Step 3: Call cooperative_exit_v2 with unsigned refund txs
    val coopExitTxidBytes = coopExitTxid.hexToByteArray().reversedArray()

    val transferRequest = Spark.StartTransferRequest.newBuilder()
        .setTransferId(transferID)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setReceiverIdentityPublicKey(ByteString.copyFrom(receiverPubKey))
        .setExpiryTime(expiryTime)
        .addAllLeavesToSend(signingJobs)
        .build()

    val exitReq = Spark.CooperativeExitRequest.newBuilder()
        .setTransfer(transferRequest)
        .setExitId(UUID.randomUUID().toString().lowercase())
        .setExitTxid(ByteString.copyFrom(coopExitTxidBytes))
        .setConnectorTx(ByteString.copyFrom(connectorTxBytes))
        .build()

    val exitResponse = stub.cooperativeExitV2(exitReq)

    // Step 4: Sign FROST with SO signing results and aggregate
    val cpfpSignatures = mutableListOf<Spark.UserSignedTxSigningJob>()
    val directSignatures = mutableListOf<Spark.UserSignedTxSigningJob>()
    val directFromCpfpSignatures = mutableListOf<Spark.UserSignedTxSigningJob>()

    for (result in exitResponse.signingResultsList) {
        val leafData = leafDataList.firstOrNull { it.leafId == result.leafId }
            ?: throw SparkError.InvalidResponse("Signing result for unknown leaf ${result.leafId}")

        val connectorPrevOut = parseTxOutput(connectorTxBytes, leafData.connectorOutputIndex)

        // Sign CPFP refund
        val cpfpNodeOutput = parseTxOutput(leafData.cpfpNodeTx, 0)
        val cpfpSighash = computeMultiInputSighashUniffi(
            tx = leafData.cpfpRefundTx,
            inputIndex = 0u,
            prevOutScripts = listOf(cpfpNodeOutput.second, connectorPrevOut.second),
            prevOutValues = listOf(cpfpNodeOutput.first.toULong(), connectorPrevOut.first.toULong()),
        )

        val cpfpAgg = signAndAggregateFrost(
            sighash = cpfpSighash,
            signingKey = leafData.signingKey,
            verifyingKey = leafData.verifyingKey,
            nonce = leafData.cpfpNonce,
            signingResult = result.refundTxSigningResult,
        )

        cpfpSignatures.add(
            Spark.UserSignedTxSigningJob.newBuilder()
                .setLeafId(result.leafId)
                .setSigningPublicKey(ByteString.copyFrom(getPublicKeyBytes(leafData.signingKey, true)))
                .setRawTx(ByteString.copyFrom(leafData.cpfpRefundTx))
                .setUserSignature(ByteString.copyFrom(cpfpAgg))
                .build()
        )

        // Sign direct refund (if exists)
        if (leafData.directRefundTx != null && leafData.directNodeTx != null && result.hasDirectRefundTxSigningResult()) {
            val directNodeOutput = parseTxOutput(leafData.directNodeTx, 0)
            val directSighash = computeMultiInputSighashUniffi(
                tx = leafData.directRefundTx,
                inputIndex = 0u,
                prevOutScripts = listOf(directNodeOutput.second, connectorPrevOut.second),
                prevOutValues = listOf(directNodeOutput.first.toULong(), connectorPrevOut.first.toULong()),
            )

            val directAgg = signAndAggregateFrost(
                sighash = directSighash,
                signingKey = leafData.signingKey,
                verifyingKey = leafData.verifyingKey,
                nonce = leafData.directNonce,
                signingResult = result.directRefundTxSigningResult,
            )

            directSignatures.add(
                Spark.UserSignedTxSigningJob.newBuilder()
                    .setLeafId(result.leafId)
                    .setSigningPublicKey(ByteString.copyFrom(getPublicKeyBytes(leafData.signingKey, true)))
                    .setRawTx(ByteString.copyFrom(leafData.directRefundTx))
                    .setUserSignature(ByteString.copyFrom(directAgg))
                    .build()
            )
        }

        // Sign directFromCpfp refund
        val dcfpSighash = computeMultiInputSighashUniffi(
            tx = leafData.directFromCpfpRefundTx,
            inputIndex = 0u,
            prevOutScripts = listOf(cpfpNodeOutput.second, connectorPrevOut.second),
            prevOutValues = listOf(cpfpNodeOutput.first.toULong(), connectorPrevOut.first.toULong()),
        )

        val dcfpAgg = signAndAggregateFrost(
            sighash = dcfpSighash,
            signingKey = leafData.signingKey,
            verifyingKey = leafData.verifyingKey,
            nonce = leafData.directFromCpfpNonce,
            signingResult = result.directFromCpfpRefundTxSigningResult,
        )

        directFromCpfpSignatures.add(
            Spark.UserSignedTxSigningJob.newBuilder()
                .setLeafId(result.leafId)
                .setSigningPublicKey(ByteString.copyFrom(getPublicKeyBytes(leafData.signingKey, true)))
                .setRawTx(ByteString.copyFrom(leafData.directFromCpfpRefundTx))
                .setUserSignature(ByteString.copyFrom(dcfpAgg))
                .build()
        )
    }

    // Step 5: Prepare key tweaks (transfer leaves to SSP)
    val soListResponse = stub.getSigningOperatorList(Empty.getDefaultInstance())
    val soOperators = soListResponse.signingOperatorsMap

    val (_, tweakPackage) = KeyTweakHelper.buildSendPackage(
        transferID = transferID,
        leaves = selectedLeaves,
        receiverPubKey = receiverPubKey,
        signer = signer,
        soOperators = soOperators,
        signingOperatorConfigs = config.signingOperators,
    )

    val transferPackageBuilder = Spark.TransferPackage.newBuilder()
        .setHashVariant(Spark.HashVariant.HASH_VARIANT_V2)
        .addAllLeavesToSend(cpfpSignatures)
        .addAllDirectLeavesToSend(directSignatures)
        .addAllDirectFromCpfpLeavesToSend(directFromCpfpSignatures)
        .setUserSignature(ByteString.copyFrom(tweakPackage.signature))
    for ((soID, cipher) in tweakPackage.keyTweakPackage) {
        transferPackageBuilder.putKeyTweakPackage(soID, ByteString.copyFrom(cipher))
    }

    // Step 6: Finalize transfer with transfer package
    val finalizeReq = Spark.FinalizeTransferWithTransferPackageRequest.newBuilder()
        .setTransferId(exitResponse.transfer.id)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setTransferPackage(transferPackageBuilder.build())
        .build()

    stub.finalizeTransferWithTransferPackage(finalizeReq)

    // Step 7: Complete coop exit via SSP
    sspClient.executeRaw(
        query = GraphQLMutations.COMPLETE_COOP_EXIT,
        variables = mapOf(
            "user_outbound_transfer_external_id" to exitResponse.transfer.id,
        ),
    )

    return coopExitTxid
}

// MARK: - FROST signing helpers

private fun signAndAggregateFrost(
    sighash: ByteArray,
    signingKey: ByteArray,
    verifyingKey: ByteArray,
    nonce: NonceResult,
    signingResult: Spark.SigningResult,
): ByteArray {
    val selfPublicKey = getPublicKeyBytes(signingKey, true)
    val keyPackage = KeyPackage(
        secretKey = signingKey,
        publicKey = selfPublicKey,
        verifyingKey = verifyingKey,
    )

    val nativeCommitments = signingResult.signingNonceCommitmentsMap.mapValues { (_, c) ->
        SigningCommitment(hiding = c.hiding.toByteArray(), binding = c.binding.toByteArray())
    }

    val selfSignature = signFrost(
        msg = sighash,
        keyPackage = keyPackage,
        nonce = nonce.nonce,
        selfCommitment = nonce.commitment,
        statechainCommitments = nativeCommitments,
        adaptorPublicKey = null,
    )

    val soSignatures = signingResult.signatureSharesMap.mapValues { it.value.toByteArray() }
    val soPublicKeys = signingResult.publicKeysMap.mapValues { it.value.toByteArray() }

    return aggregateFrost(
        msg = sighash,
        statechainCommitments = nativeCommitments,
        selfCommitment = nonce.commitment,
        statechainSignatures = soSignatures,
        selfSignature = selfSignature,
        statechainPublicKeys = soPublicKeys,
        selfPublicKey = selfPublicKey,
        verifyingKey = verifyingKey,
        adaptorPublicKey = null,
    )
}

// MARK: - Raw tx helpers

/** Compute txid from raw tx (double SHA-256, internal byte order) */
internal fun computeTxId(rawTx: ByteArray): ByteArray {
    val stripped = stripWitness(rawTx)
    val hash1 = sha256(stripped)
    return sha256(hash1)
}

/** Strip witness data from a segwit tx to get legacy serialization */
internal fun stripWitness(rawTx: ByteArray): ByteArray {
    var offset = 4 // skip version
    val hasWitness = rawTx.size > 5 && rawTx[offset] == 0x00.toByte() && rawTx[offset + 1] == 0x01.toByte()
    if (!hasWitness) return rawTx

    val result = java.io.ByteArrayOutputStream()
    result.write(rawTx, 0, 4) // version

    offset += 2 // skip marker + flag

    val (inputCount, inputCountLen) = readVarInt(rawTx, offset)
    val inputCountStart = offset
    offset += inputCountLen

    for (i in 0 until inputCount.toInt()) {
        offset += 36
        val (scriptLen, scriptLenLen) = readVarInt(rawTx, offset)
        offset += scriptLenLen + scriptLen.toInt() + 4
    }

    val (outputCount, outputCountLen) = readVarInt(rawTx, offset)
    offset += outputCountLen
    for (i in 0 until outputCount.toInt()) {
        offset += 8
        val (scriptLen, scriptLenLen) = readVarInt(rawTx, offset)
        offset += scriptLenLen + scriptLen.toInt()
    }

    val afterOutputs = offset

    result.write(rawTx, inputCountStart, afterOutputs - inputCountStart)
    result.write(rawTx, rawTx.size - 4, 4) // locktime

    return result.toByteArray()
}

/** Check if a node tx has zero timelock (sequence == 0) */
internal fun isZeroTimelockNode(nodeTx: ByteArray): Boolean {
    val seq = parseSequenceFromRawTx(nodeTx)
    return (seq and 0xFFFFu) == 0u
}

/** Create raw bytes for a connector input (txid + vout + empty scriptSig + sequence) */
internal fun makeConnectorInputBytes(txId: ByteArray, vout: UInt): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write(txId) // already in internal byte order
    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(vout.toInt()).array())
    out.write(0) // scriptSig length = 0
    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).array()) // 0xFFFFFFFF
    return out.toByteArray()
}

/** Add an input to a raw transaction (handles both segwit and legacy) */
internal fun addInputToRawTx(rawTx: ByteArray, input: ByteArray): ByteArray {
    var offset = 4 // skip version

    val hasWitness = rawTx.size > 5 && rawTx[offset] == 0x00.toByte() && rawTx[offset + 1] == 0x01.toByte()
    if (hasWitness) offset += 2

    val (inputCount, inputCountLen) = readVarInt(rawTx, offset)
    val inputCountOffset = offset
    offset += inputCountLen

    for (i in 0 until inputCount.toInt()) {
        offset += 36
        val (scriptLen, scriptLenLen) = readVarInt(rawTx, offset)
        offset += scriptLenLen + scriptLen.toInt() + 4
    }
    val afterInputs = offset

    val result = java.io.ByteArrayOutputStream()

    if (hasWitness) {
        result.write(rawTx, 0, 4)
        result.write(byteArrayOf(0x00, 0x01))
    } else {
        result.write(rawTx, 0, 4)
    }

    // New input count
    result.write(encodeVarInt(inputCount + 1))
    // Existing inputs (skip old input count bytes)
    result.write(rawTx, inputCountOffset + inputCountLen, afterInputs - inputCountOffset - inputCountLen)
    // New connector input
    result.write(input)

    if (hasWitness) {
        // Outputs section
        var outOffset = afterInputs
        val (outputCount, outputCountLen) = readVarInt(rawTx, outOffset)
        outOffset += outputCountLen
        for (i in 0 until outputCount.toInt()) {
            outOffset += 8
            val (scriptLen, scriptLenLen) = readVarInt(rawTx, outOffset)
            outOffset += scriptLenLen + scriptLen.toInt()
        }
        val afterOutputs = outOffset

        result.write(rawTx, afterInputs, afterOutputs - afterInputs)

        // Existing witness data
        for (i in 0 until inputCount.toInt()) {
            val (witnessCount, witnessCountLen) = readVarInt(rawTx, outOffset)
            val witnessStart = outOffset
            outOffset += witnessCountLen
            for (j in 0 until witnessCount.toInt()) {
                val (itemLen, itemLenLen) = readVarInt(rawTx, outOffset)
                outOffset += itemLenLen + itemLen.toInt()
            }
            result.write(rawTx, witnessStart, outOffset - witnessStart)
        }
        // Empty witness for new input
        result.write(0x00)
        // Locktime
        result.write(rawTx, rawTx.size - 4, 4)
    } else {
        // Rest of tx (outputs + locktime)
        result.write(rawTx, afterInputs, rawTx.size - afterInputs)
    }

    return result.toByteArray()
}

/** Encode an integer as a Bitcoin varint */
internal fun encodeVarInt(value: Long): ByteArray = when {
    value < 0xFD -> byteArrayOf(value.toByte())
    value <= 0xFFFF -> {
        val out = java.io.ByteArrayOutputStream()
        out.write(0xFD)
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
        out.toByteArray()
    }
    value <= 0xFFFFFFFFL -> {
        val out = java.io.ByteArrayOutputStream()
        out.write(0xFE)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())
        out.toByteArray()
    }
    else -> {
        val out = java.io.ByteArrayOutputStream()
        out.write(0xFF)
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
        out.toByteArray()
    }
}
