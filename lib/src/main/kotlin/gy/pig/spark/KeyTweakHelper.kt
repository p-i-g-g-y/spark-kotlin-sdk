package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import spark.Spark
import uniffi.spark_frost.*

object KeyTweakHelper {

    data class Package(val keyTweakPackage: Map<String, ByteArray>, val signature: ByteArray,)

    fun buildSendPackage(
        transferID: String,
        leaves: List<SparkLeaf>,
        receiverPubKey: ByteArray,
        signer: SparkSignerProtocol,
        soOperators: Map<String, Spark.SigningOperatorInfo>,
        signingOperatorConfigs: List<SigningOperatorConfig>,
    ): Pair<Map<String, Spark.SendLeafKeyTweaks.Builder>, Package> {
        val soCount = soOperators.size.toUInt()
        val threshold = maxOf(2u, (soCount + 2u) / 2u)

        val perSoTweaks = mutableMapOf<String, Spark.SendLeafKeyTweaks.Builder>()
        for (soID in soOperators.keys) {
            perSoTweaks[soID] = Spark.SendLeafKeyTweaks.newBuilder()
        }

        for (leaf in leaves) {
            val oldSigningKey = signer.deriveLeafSigningKey(leaf.id)
            val newRandomKey = randomSecretKeyBytes()
            val keyTweak = subtractPrivateKeys(oldSigningKey, newRandomKey)
            val vssShares = splitSecretWithProofsUniffi(keyTweak, threshold, soCount)
            val secretCipher = encryptEcies(newRandomKey, receiverPubKey)

            val sigPayload = leaf.id.toByteArray(Charsets.UTF_8) +
                transferID.toByteArray(Charsets.UTF_8) +
                secretCipher
            val tweakSig = signer.signCompactWithIdentityKey(sha256(sigPayload))

            val pubkeyBySOID = mutableMapOf<String, ByteArray>()
            for ((soID, soInfo) in soOperators) {
                val matchedShare = vssShares.first { it.index == soInfo.index.toUInt() + 1u }
                pubkeyBySOID[soID] = getPublicKeyBytes(matchedShare.share, true)
            }

            for ((soID, soInfo) in soOperators) {
                val share = vssShares.first { it.index == soInfo.index.toUInt() + 1u }
                val secretShareProto = Spark.SecretShare.newBuilder()
                    .setSecretShare(ByteString.copyFrom(share.share))
                for (proof in share.proofs) {
                    secretShareProto.addProofs(ByteString.copyFrom(proof))
                }
                val leafTweak = Spark.SendLeafKeyTweak.newBuilder()
                    .setLeafId(leaf.id)
                    .setSecretShareTweak(secretShareProto.build())
                    .setSecretCipher(ByteString.copyFrom(secretCipher))
                    .setSignature(ByteString.copyFrom(tweakSig))
                for ((otherSoID, pubkey) in pubkeyBySOID) {
                    leafTweak.putPubkeySharesTweak(otherSoID, ByteString.copyFrom(pubkey))
                }
                perSoTweaks[soID]?.addLeavesToSend(leafTweak.build())
            }
        }

        val builtTweaks = perSoTweaks.mapValues { it.value.build() }
        val pkg = encryptAndSign(
            transferID = transferID,
            perSoTweaks = builtTweaks,
            soOperators = soOperators,
            signingOperatorConfigs = signingOperatorConfigs,
            signer = signer,
            tag = "transfer",
        )

        return perSoTweaks to pkg
    }

    fun <T : MessageLite> encryptAndSign(
        transferID: String,
        perSoTweaks: Map<String, T>,
        soOperators: Map<String, Spark.SigningOperatorInfo>,
        signingOperatorConfigs: List<SigningOperatorConfig>,
        signer: SparkSignerProtocol,
        tag: String,
    ): Package {
        val keyTweakPackage = mutableMapOf<String, ByteArray>()
        for (soID in soOperators.keys) {
            val tweaksBytes = perSoTweaks[soID]!!.toByteArray()
            val soConfig = signingOperatorConfigs.first { it.identifier == soID }
            val identityPubKey = soConfig.identityPublicKeyHex.hexToByteArray()
            val encrypted = encryptEcies(tweaksBytes, identityPubKey)
            keyTweakPackage[soID] = encrypted
        }

        val transferIdBytes = transferID.replace("-", "").hexToByteArray()
        val hasher = SparkHasher(listOf("spark", tag, "signing payload"))
        hasher.addBytes(transferIdBytes)
        hasher.addMapStringToBytes(keyTweakPackage)
        val packagePayload = hasher.hash()
        val packageSignature = signer.signWithIdentityKey(packagePayload)

        return Package(keyTweakPackage = keyTweakPackage, signature = packageSignature)
    }
}
