package gy.pig.spark

import org.junit.Assume

/**
 * Centralised access to integration-test secrets and live-network parameters.
 *
 * Values originate from (priority order, evaluated at Gradle configure time):
 *
 *  1. `<repo>/local.properties` (gitignored) — keys:
 *     ```
 *     spark.test.walletA.mnemonic=<bip39 mnemonic>
 *     spark.test.walletB.mnemonic=<bip39 mnemonic>
 *     spark.test.lnAddress=user@host
 *     ```
 *  2. Environment variables:
 *     ```
 *     SPARK_TEST_WALLET_A_MNEMONIC
 *     SPARK_TEST_WALLET_B_MNEMONIC
 *     SPARK_TEST_LN_ADDRESS
 *     ```
 *  3. Empty string — tests that require the value will skip via [requireMnemonic]
 *     or [requireLnAddress].
 *
 * **Never** log any value returned here. **Never** commit `local.properties`
 * (it is in `.gitignore`).
 */
object TestConfig {

    /** Funded test wallet — must contain ≥ [MINIMUM_BALANCE_SATS] sats on mainnet. */
    val walletAMnemonic: String get() = BuildConfig.SPARK_TEST_WALLET_A_MNEMONIC

    /** Receiver-side test wallet — does not need to be funded. */
    val walletBMnemonic: String get() = BuildConfig.SPARK_TEST_WALLET_B_MNEMONIC

    /** Lightning address (`user@host`) used by LNURL-pay test paths. */
    val lnAddress: String get() = BuildConfig.SPARK_TEST_LN_ADDRESS

    /** Minimum sats expected in `walletA` for integration tests to run. */
    const val MINIMUM_BALANCE_SATS: Long = 500L

    /**
     * Skip the calling test (via JUnit [Assume]) when [walletAMnemonic] or
     * [walletBMnemonic] is empty. Call from `@Before` so the entire test class
     * is skipped consistently when secrets aren't configured.
     */
    fun requireMnemonics() {
        Assume.assumeFalse(
            "Set spark.test.walletA.mnemonic in local.properties or " +
                "SPARK_TEST_WALLET_A_MNEMONIC env var to run integration tests.",
            walletAMnemonic.isEmpty(),
        )
        Assume.assumeFalse(
            "Set spark.test.walletB.mnemonic in local.properties or " +
                "SPARK_TEST_WALLET_B_MNEMONIC env var to run integration tests.",
            walletBMnemonic.isEmpty(),
        )
    }

    /** Skip the calling test when [lnAddress] is empty. */
    fun requireLnAddress() {
        Assume.assumeFalse(
            "Set spark.test.lnAddress in local.properties or " +
                "SPARK_TEST_LN_ADDRESS env var to run Lightning-address tests.",
            lnAddress.isEmpty(),
        )
    }
}
