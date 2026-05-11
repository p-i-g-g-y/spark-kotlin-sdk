# Samples

Code samples for `spark-kotlin-sdk`. These are not part of the published build —
they're standalone snippets you can drop into an Android app (or a JVM-friendly
host) to see end-to-end usage.

| Sample | Description |
|---|---|
| [`quickstart/Quickstart.kt`](quickstart/Quickstart.kt) | Create a wallet, generate a deposit address and a Lightning invoice, query balance, send a Spark transfer, withdraw on-chain. |

## Using a sample in your app

1. Add the SDK dependency to your `app/build.gradle.kts`:

   ```kotlin
   dependencies {
       implementation("gy.pig:spark-kotlin-sdk:0.1.0")
   }
   ```

2. Copy the file from this directory into your module's source set
   (`app/src/main/kotlin/...`).
3. Replace placeholders (mnemonic, recipient pubkey, on-chain address) with your
   own values. **Never** ship mnemonics or test keys in a real release build.
4. Call the sample from a coroutine scope:

   ```kotlin
   lifecycleScope.launch { runQuickstart() }
   ```

> ⚠️ Samples target **regtest** by default. Switch the `SparkConfig` to mainnet
> only after you understand the implications — see [SECURITY.md](../SECURITY.md).
