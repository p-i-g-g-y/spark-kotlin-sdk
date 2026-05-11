<h1 align="center">spark-kotlin-sdk</h1>

<p align="center">
  A Kotlin / Android SDK for the <a href="https://spark.money">Spark</a> protocol —
  self-custodial Bitcoin wallets powered by threshold FROST signing.
</p>

<p align="center">
  <a href="https://github.com/p-i-g-g-y/spark-kotlin-sdk/actions/workflows/ci.yml"><img src="https://github.com/p-i-g-g-y/spark-kotlin-sdk/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/p-i-g-g-y/spark-kotlin-sdk/actions/workflows/codeql.yml"><img src="https://github.com/p-i-g-g-y/spark-kotlin-sdk/actions/workflows/codeql.yml/badge.svg" alt="CodeQL"></a>
  <a href="https://securityscorecards.dev/viewer/?uri=github.com/p-i-g-g-y/spark-kotlin-sdk"><img src="https://api.securityscorecards.dev/projects/github.com/p-i-g-g-y/spark-kotlin-sdk/badge" alt="OpenSSF Scorecard"></a>
  <a href="https://codecov.io/gh/p-i-g-g-y/spark-kotlin-sdk"><img src="https://codecov.io/gh/p-i-g-g-y/spark-kotlin-sdk/graph/badge.svg" alt="Coverage"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.2-blueviolet.svg?logo=kotlin" alt="Kotlin 2.2"></a>
  <a href="https://developer.android.com/about/versions/nougat"><img src="https://img.shields.io/badge/minSdk-24-3DDC84.svg?logo=android" alt="Android minSdk 24"></a>
  <img src="https://img.shields.io/badge/ABIs-arm64--v8a%20%7C%20armeabi--v7a%20%7C%20x86%20%7C%20x86__64-lightgrey.svg" alt="ABIs">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"></a>
  <a href="https://github.com/p-i-g-g-y/spark-kotlin-sdk/releases"><img src="https://img.shields.io/github/v/release/p-i-g-g-y/spark-kotlin-sdk?include_prereleases&sort=semver" alt="Latest release"></a>
</p>

> ⚠️ **Self-custody warning.** `spark-kotlin-sdk` manages cryptographic keys that control
> real Bitcoin. Mistakes — losing a mnemonic, leaking it, calling APIs without
> understanding the consequences — can result in permanent, irrecoverable loss of funds.
> Read [SECURITY.md](SECURITY.md) and the threat model before shipping this in production.

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Usage](#usage)
  - [Creating a wallet](#creating-a-wallet)
  - [Deposits](#deposits)
  - [Lightning](#lightning)
  - [Spark transfers](#spark-transfers)
  - [Withdrawals](#withdrawals)
  - [Tokens](#tokens)
  - [Events & history](#events--history)
- [Networks](#networks)
- [Architecture](#architecture)
- [Error handling](#error-handling)
- [Concurrency](#concurrency)
- [ProGuard / R8](#proguard--r8)
- [Security model](#security-model)
- [Testing](#testing)
- [Contributing](#contributing)
- [License](#license)

---

## Features

- **Deposits** — one-time and reusable static taproot (P2TR) deposit addresses, with UTXO
  enumeration and claim flows.
- **Lightning** — create BOLT-11 invoices, pay invoices, fee estimation.
- **Spark transfers** — send and receive between Spark wallets with sub-second finality.
- **Withdrawals** — cooperative exit to any on-chain Bitcoin address.
- **Tokens** — create, mint, burn, transfer, and query Spark token balances.
- **Swaps** — denominate leaves via the SSP swap service.
- **Events** — `Flow<SparkEvent>` of incoming transfers and deposit confirmations.
- **History** — paginated query of inbound and outbound transfers.
- **Privacy controls** — toggle transaction visibility on the SSP.
- **Modern Kotlin** — coroutines (`suspend` everywhere), typed `sealed class` errors,
  `Flow`-based streams.

## Requirements

|              | Minimum |
|--------------|---------|
| Kotlin       | 2.2     |
| JVM target   | 11      |
| Android SDK  | API 24 (Android 7.0) |
| compileSdk   | 35      |
| AGP          | 9.1     |

The SDK ships precompiled `.so` libraries for `arm64-v8a`, `armeabi-v7a`, `x86`, and
`x86_64` (16 KB ELF page alignment for Android 15+ compatibility).

## Installation

> Maven Central publication is in progress. Until then, consume via JitPack or a local
> Maven repository — see [CONTRIBUTING.md](CONTRIBUTING.md) for `publishToMavenLocal`.

### Gradle (once published)

```kotlin
// build.gradle.kts
dependencies {
    implementation("gy.pig:spark-kotlin-sdk:0.1.0")
}
```

```groovy
// build.gradle
dependencies {
    implementation 'gy.pig:spark-kotlin-sdk:0.1.0'
}
```

### JitPack (interim)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.p-i-g-g-y:spark-kotlin-sdk:main-SNAPSHOT")
}
```

## Quick Start

```kotlin
import gy.pig.spark.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

runBlocking {
    // 1. Create a wallet from a BIP-39 mnemonic
    val wallet = SparkWallet.fromMnemonic(
        config = SparkConfig(network = SparkNetwork.MAINNET),
        mnemonic = "abandon abandon abandon abandon abandon abandon " +
                   "abandon abandon abandon abandon abandon about",
    )

    try {
        // 2. RECEIVE — print a BOLT-11 Lightning invoice and have someone
        //    pay it from an external Lightning wallet. (Lightning is the
        //    fast path; on-chain via `getStaticDepositAddress()` works too
        //    but requires a confirmation and a separate `claimStaticDeposit`.)
        val invoice = wallet.createLightningInvoice(amountSats = 1_000)
        println("Pay this invoice: ${invoice.encodedInvoice}")

        // 3. Wait for the invoice to settle. In a real app, subscribe to
        //    `wallet.subscribeToEvents()` instead of sleeping.
        delay(30.seconds)

        // 4. CLAIM — inbound transfers are pending until claimed. `send()`
        //    will see no balance until this step runs.
        val claimed = wallet.claimAllPendingTransfers()
        println("Claimed $claimed pending transfer(s)")

        val balance = wallet.getBalance()
        println("Available: ${balance.satsBalance.available} sats")

        // 5. SEND — to another Spark wallet (33-byte compressed secp256k1 key).
        val transfer = wallet.send(
            receiverIdentityPublicKey = recipientPubKey,
            amountSats = 500,
        )
        println("Sent: ${transfer.id}")
    } finally {
        wallet.close()
    }
}
```

## Usage

### Creating a wallet

```kotlin
// From a BIP-39 mnemonic (the most common case)
val wallet = SparkWallet.fromMnemonic(
    config = SparkConfig(),     // mainnet by default
    mnemonic = "...",
    account = 0,                // optional; defaults to 1 on mainnet, 0 on regtest
)

// From a raw 64-byte account key (32-byte key + 32-byte chain code)
val wallet = SparkWallet.fromAccountKey(
    config = SparkConfig(network = SparkNetwork.MAINNET),
    accountKey = key64Bytes,
)

// With a custom signer (e.g. Android Keystore-backed, hardware-backed)
val wallet = SparkWallet.fromSigner(
    config = SparkConfig(),
    signer = mySigner,          // implements SparkSignerProtocol
)
```

### Deposits

```kotlin
// Static (reusable) deposit address — preferred for most apps
val staticDeposit = wallet.getStaticDepositAddress()
val utxos = wallet.getUtxosForDepositAddress(address = staticDeposit.address)

// Once a UTXO confirms on-chain, claim it into your Spark balance
val transferId = wallet.claimStaticDeposit(
    txID = utxo.txid,
    vout = utxo.vout,
)
```

### Lightning

```kotlin
// Receive
val invoice = wallet.createLightningInvoice(
    amountSats = 1_000,
    memo = "Coffee",
)

// Send
val fee = wallet.getLightningSendFeeEstimate(encodedInvoice = "lnbc...")
val paymentId = wallet.payLightningInvoice(paymentRequest = "lnbc...")
```

### Spark transfers

```kotlin
// Pubkey form (33-byte compressed secp256k1)
val transfer = wallet.send(
    receiverIdentityPublicKey = "02abcd...".hexToByteArray(),
    amountSats = 500,
)

// Receive side: claim any pending inbound transfers
val claimed: Int = wallet.claimAllPendingTransfers()
```

### Withdrawals

```kotlin
val l1Txid: String = wallet.withdraw(
    onChainAddress = "bc1q...",
    amountSats = 10_000,
)
```

### Tokens

```kotlin
import java.math.BigInteger

val token = wallet.createToken(
    tokenName = "Acme",
    tokenTicker = "ACME",
    decimals = 6u,
    maxSupply = BigInteger.valueOf(1_000_000),
    isFreezable = false,
)
wallet.mintTokens(
    tokenIdentifier = token.tokenIdentifier!!,
    tokenAmount = BigInteger.valueOf(1_000),
)
val balances = wallet.getTokenBalances()
```

### Events & history

```kotlin
import kotlinx.coroutines.flow.collect

// Stream of inbound transfer / deposit events
wallet.subscribeToEvents().collect { event ->
    println("event: $event")
}

// Paginated history
val transfers = wallet.getTransfers(
    direction = TransferDirection.BOTH,
    limit = 20,
    offset = 0,
)
```

## Networks

```kotlin
val mainnet = SparkConfig(network = SparkNetwork.MAINNET)
val regtest = SparkConfig(network = SparkNetwork.REGTEST)

// Custom operators / SSP
val custom = SparkConfig(
    network = SparkNetwork.MAINNET,
    signingOperators = listOf(/* SigningOperatorConfig(...) */),
    sspURL = "https://api.lightspark.com/graphql/spark/2025-03-19",
)
```

## Architecture

```
                  ┌─────────────────────────────────────┐
                  │              SparkWallet            │
                  └─────────────────────────────────────┘
                                    │
              ┌─────────────────────┼─────────────────────┐
              ▼                     ▼                     ▼
    ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
    │ Spark operators  │  │   SSP GraphQL    │  │ FROST signer     │
    │ (gRPC over HTTP/2)│ │  (HTTP/JSON)     │  │ (Rust, via JNI)  │
    └──────────────────┘  └──────────────────┘  └──────────────────┘
```

| File / package | Responsibility |
|---|---|
| `SparkWallet.kt`              | Public entry point, lifecycle, identity key |
| `SparkConfig.kt`              | Network / operator / SSP configuration |
| `SparkError.kt`               | Typed `sealed class` errors |
| `SparkSigner.kt`              | Identity-key signing (`SparkSignerProtocol`) |
| `KeyDerivation.kt`            | BIP-39 / BIP-32 derivation |
| `GrpcConnectionManager.kt`    | gRPC channel management |
| `SspGraphQLClient.kt`         | SSP GraphQL transport + auth |
| `BalanceService.kt`           | Balance queries, leaf management |
| `TransferService.kt`, `TransferQueryService.kt`, `ClaimTransferService.kt` | Spark-to-Spark send / claim / query |
| `LightningService.kt`         | BOLT-11 invoice + payment |
| `DepositService.kt`, `AddressService.kt` | One-time and static deposit addresses |
| `WithdrawalService.kt`        | Cooperative on-chain exits |
| `TokenService.kt`, `TokenIdentifier.kt`, `TokenHashing.kt` | Token create / mint / burn / transfer / query |
| `SwapService.kt`              | SSP-mediated leaf denomination |
| `EventService.kt`             | Real-time event streaming (`Flow<SparkEvent>`) |
| `SettingsService.kt`          | Privacy mode, wallet settings |
| `FrostSigningHelper.kt`, `KeyTweakHelper.kt` | FROST round helpers |
| `frost/uniffi/spark_frost/`   | Generated UniFFI bindings — do not edit |
| `Models.kt`                   | Public data types |
| `src/main/proto/`             | `.proto` source-of-truth |
| `src/main/jniLibs/`           | Precompiled FROST native libraries |

## Error handling

All public APIs throw `SparkError` — a `sealed class` extending `Exception`:

```kotlin
try {
    wallet.payLightningInvoice(paymentRequest = "lnbc...")
} catch (e: SparkError) {
    when (e) {
        is SparkError.InsufficientBalance -> {
            // show user: need ${e.need}, have ${e.have}
        }
        is SparkError.AuthenticationFailed -> {
            // re-auth flow
        }
        is SparkError.GrpcError,
        is SparkError.GraphqlError,
        is SparkError.FrostSigningFailed -> {
            // transport / protocol failures
        }
        else -> {
            // log and surface
        }
    }
}
```

## Concurrency

- Every I/O method is `suspend`. Call from any coroutine scope.
- Real-time event subscription returns a `kotlinx.coroutines.flow.Flow<SparkEvent>`.
- gRPC channels are managed internally; `SparkWallet.close()` cancels the internal
  scope and shuts down channels — always call it (use `try`/`finally` or `use`-style
  scoping) to avoid leaking connections.
- The SDK is **not** main-thread-safe — never invoke `suspend` calls from
  `Dispatchers.Main` without offloading. Prefer `Dispatchers.IO`.

## ProGuard / R8

The SDK ships `consumer-rules.pro` (consumer ProGuard rules) so that apps minifying with
R8 do not need to manually keep gRPC, protobuf-lite, JNA, or BouncyCastle classes. If
you hit a `ClassNotFoundException` in release builds, please
[file an issue](https://github.com/p-i-g-g-y/spark-kotlin-sdk/issues) with the missing
class name.

## Security model

This is a self-custody wallet SDK. **Read [SECURITY.md](SECURITY.md) before shipping.**

Highlights:

- The host process is trusted — the SDK does not defend against a compromised app.
- Mnemonic and account-key storage is the **app's responsibility**. Use the
  [Android Keystore](https://developer.android.com/training/articles/keystore) with
  `setUserAuthenticationRequired(true)` for production wallets.
- The bundled `.so` native libraries are documented with SHA-256 hashes and
  reviewer verification steps in [NATIVE_BINARIES.md](NATIVE_BINARIES.md); the
  rebuild pipeline lives in [CONTRIBUTING.md](CONTRIBUTING.md).
- The SDK has **not** yet undergone a third-party audit.

To report a vulnerability, do **not** open a public issue. Use private vulnerability
reporting on this repo or email `gm@orklabs.com`.

## Testing

### Unit tests (no network, no funds)

```bash
./gradlew :lib:testDebugUnitTest
```

These cover key derivation, BIP-39 vectors, hex parsing, token validation, and
deterministic helpers. They use the canonical `abandon abandon … about` BIP-39 test
vector and require no configuration.

### Integration tests (live network, real funds)

Integration tests connect to live Spark operators and submit real transactions. They
require funded test wallets and a connected device / emulator:

```bash
./gradlew :lib:connectedAndroidTest
```

> ⚠️ **Never commit funded mnemonics.** Move them to environment variables read at
> test time. See [CONTRIBUTING.md](CONTRIBUTING.md) for the test config pattern.

## Contributing

PRs welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for the dev setup, proto
regeneration, native rebuild via `build-frost-android.sh`, coding standards, and the
release process. Please read the [Code of Conduct](CODE_OF_CONDUCT.md) before
contributing.

A Swift implementation of the same protocol lives at
[`spark-swift-sdk`](https://github.com/p-i-g-g-y/spark-swift-sdk).

Generated API documentation is published to
[`p-i-g-g-y.github.io/spark-kotlin-sdk`](https://p-i-g-g-y.github.io/spark-kotlin-sdk/)
on every release tag.

## License

[MIT](LICENSE) © Piggy
