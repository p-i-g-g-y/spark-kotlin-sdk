/*
 * SPDX-License-Identifier: MIT
 *
 * spark-kotlin-sdk quickstart — copy this file into your Android app's
 * `src/main/kotlin/...` directory and call `runQuickstart()` from a coroutine
 * scope (e.g. `lifecycleScope.launch { runQuickstart() }`).
 *
 * Targets regtest by default. Switch to mainnet only when you understand the
 * implications — see SECURITY.md.
 *
 * End-to-end flow:
 *   1. Create wallet from BIP-39 mnemonic.
 *   2. Receive: print a BOLT-11 Lightning invoice and wait for an external
 *      wallet to pay it.
 *   3. Claim the inbound transfer so the sats land in the Spark balance.
 *   4. Verify balance.
 *   5. Send a Spark transfer to another wallet.
 *   6. Close the wallet (drains gRPC connections).
 */
package samples.quickstart

import gy.pig.spark.SparkConfig
import gy.pig.spark.SparkError
import gy.pig.spark.SparkNetwork
import gy.pig.spark.SparkWallet
import gy.pig.spark.claimAllPendingTransfers
import gy.pig.spark.createLightningInvoice
import gy.pig.spark.getBalance
import gy.pig.spark.send
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Minimal end-to-end walkthrough of the SDK.
 *
 * Replace the placeholder values (mnemonic, recipient pubkey, on-chain address)
 * with your own. **Never** commit a real funded mnemonic.
 */
suspend fun runQuickstart() {
    // 1. Configure the network. Default is mainnet — explicitly use regtest
    //    for development.
    val config = SparkConfig(network = SparkNetwork.REGTEST)

    // 2. Create a wallet from a BIP-39 mnemonic. The canonical BIP-39 test
    //    vector — DO NOT USE IN PRODUCTION.
    val mnemonic =
        "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"

    val wallet = SparkWallet.fromMnemonic(config = config, mnemonic = mnemonic)

    try {
        // 3. RECEIVE — create a BOLT-11 Lightning invoice and ask an external
        //    wallet (e.g. an LN-enabled phone wallet, or another Spark wallet
        //    via payLightningInvoice) to pay it.
        //
        //    Lightning is the fast path. A static taproot deposit also works
        //    (`getStaticDepositAddress()` + `claimStaticDeposit(txID, vout)`),
        //    but requires an on-chain confirmation first.
        val invoice =
            wallet.createLightningInvoice(
                amountSats = 1_000,
                memo = "spark-kotlin-sdk quickstart",
            )
        println("Lightning invoice (pay this from an external wallet):")
        println(invoice.encodedInvoice)

        // 4. Wait for the invoice to be paid. In a real app, subscribe to
        //    `wallet.subscribeToEvents()` and react to `SparkEvent.TransferReceived`
        //    instead of sleeping.
        println("Waiting 30s for the invoice to be paid…")
        delay(30.seconds)

        // 5. CLAIM — pending inbound transfers must be claimed before their
        //    sats become spendable. `send()` will fail with InsufficientBalance
        //    if there's nothing claimed yet.
        val claimed = wallet.claimAllPendingTransfers()
        println("Claimed $claimed pending transfer(s).")

        // 6. Verify the balance moved.
        val balance = wallet.getBalance()
        println("Available balance: ${balance.satsBalance} sats")

        if (balance.satsBalance <= 0) {
            println("No balance yet — invoice probably not paid. Stopping.")
            return
        }

        // 7. SEND — to another Spark wallet (33-byte compressed secp256k1 pubkey).
        //    Replace with the recipient's `wallet.identityPublicKeyHex`.
        val recipientPubKeyHex = "02".padEnd(66, '0') // placeholder
        try {
            val transfer =
                wallet.send(
                    receiverIdentityPublicKey = recipientPubKeyHex.hexToByteArray(),
                    amountSats = 100,
                )
            println("Sent Spark transfer: ${transfer.id}")
        } catch (e: SparkError.InsufficientBalance) {
            println("Skipping Spark transfer — wallet has ${e.have} sats, need ${e.need}.")
        }

        // 8. (Optional) cooperative on-chain exit. Uncomment to actually spend.
        // val l1Txid = wallet.withdraw(onChainAddress = "bc1q...", amountSats = 500)
        // println("Submitted L1 withdrawal: $l1Txid")
    } catch (e: SparkError) {
        // Typed sealed-class errors — branch on the case you care about.
        println("SDK error: $e")
    } finally {
        // Always close — drains the internal coroutine scope and shuts down gRPC.
        wallet.close()
    }
}

// Convenience helper so the placeholder string-to-bytes call in the sample
// compiles even on JDK targets without the Kotlin stdlib hexToByteArray()
// extension function. (Kotlin 1.9+ provides this natively on JVM 11+.)
private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) +
            Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}
