package gy.pig.spark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Host-runnable tests for the BOLT11 decoder used by [payLightningInvoice]
 * ([decodeBolt11PaymentHash]). A misparse here routes payments to the wrong
 * hash or settles for the wrong amount, so these double as a regression net.
 *
 * The payment-hash vector is the canonical BOLT11 spec invoice
 * (https://github.com/lightning/bolts/blob/master/11-payment-encoding.md);
 * amount variants reuse its data section with a rewritten HRP, which is exactly
 * what the decoder inspects for the amount.
 */
class Bolt11Tests {

    // Canonical BOLT11 spec invoice — no amount, payment hash
    // 0001020304050607080900010203040506070809000102030405060708090102.
    private val specInvoiceNoAmount =
        "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kget" +
            "jypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq8rkx3yf5tcsyz3d73gafnh3cax9rn449d9p5uxz9ezhhypd0elx" +
            "87sjle52x86fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w"

    private val expectedPaymentHash =
        "0001020304050607080900010203040506070809000102030405060708090102"

    /** Rewrite the HRP amount of the spec invoice, keeping its data section intact. */
    private fun withHrp(hrp: String): String = hrp + specInvoiceNoAmount.substring(4)

    @Test
    fun extractsCanonicalPaymentHash() {
        val (paymentHash, amountSats) = decodeBolt11PaymentHash(specInvoiceNoAmount)
        assertEquals(32, paymentHash.size)
        assertEquals(expectedPaymentHash, paymentHash.toHexString())
        assertNull("Spec invoice has no amount", amountSats)
    }

    @Test
    fun caseInsensitivePaymentHash() {
        // BOLT11 invoices are case-insensitive; the decoder lowercases internally.
        val (paymentHash, _) = decodeBolt11PaymentHash(specInvoiceNoAmount.uppercase())
        assertEquals(expectedPaymentHash, paymentHash.toHexString())
    }

    @Test
    fun parsesMilliMultiplier() {
        // 25 milli-BTC = 25 * 100_000 = 2_500_000 sats.
        assertEquals(2_500_000L, decodeBolt11PaymentHash(withHrp("lnbc25m")).second)
        // 1 milli-BTC = 100_000 sats.
        assertEquals(100_000L, decodeBolt11PaymentHash(withHrp("lnbc1m")).second)
    }

    @Test
    fun parsesMicroMultiplier() {
        // 2500 micro-BTC = 2500 * 100 = 250_000 sats.
        assertEquals(250_000L, decodeBolt11PaymentHash(withHrp("lnbc2500u")).second)
    }

    @Test
    fun parsesNanoMultiplierWithCeiling() {
        // 1500 nano-BTC = (1500 + 9) / 10 = 150 sats (ceiling, matches Swift).
        assertEquals(150L, decodeBolt11PaymentHash(withHrp("lnbc1500n")).second)
    }

    @Test
    fun rejectsNonBolt11Prefix() {
        try {
            decodeBolt11PaymentHash("xyz1notaninvoice")
            fail("Should have thrown for non-BOLT11 prefix")
        } catch (e: IllegalArgumentException) {
            // expected — require() guard
        }
    }
}
