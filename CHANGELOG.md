# Changelog

All notable changes to `spark-kotlin-sdk` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

While the SDK is in `0.x`, the public API is considered unstable and minor releases may
contain breaking changes. Each breaking change will be documented under "Changed" with a
migration note.

---

## [Unreleased]

## [0.2.0]

### Changed
- **BREAKING:** `WalletBalance` no longer carries a single `totalSats: Long`.
  It now exposes `satsBalance: SatsBalance` with three buckets — `available`
  (spendable), `owned` (available + locked in outgoing operations), and
  `incoming` (pending inbound transfers + on-chain deposits still in
  `CREATING` state). This matches the official Swift SDK so the same wallet
  queried from iOS and Android composes into identical numbers.
- `WalletBalance.totalSats` is retained as a deprecated accessor that
  returns `satsBalance.available`, so code compiled against `0.1.0` keeps
  working with a deprecation warning. Migrate to either
  `satsBalance.available` (spendable now) or `satsBalance.available +
  satsBalance.incoming` (with in-flight credits).
- `getBalance()` now computes the breakdown locally from `query_nodes`
  and `queryPendingTransfers()` instead of relying on the coordinator's
  `query_balance` RPC, mirroring the Swift SDK's algorithm exactly.

## [Unreleased prior to 0.2.0]

### Added
- `LICENSE` (MIT), `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, this
  `CHANGELOG.md`, `NOTICE`, and a comprehensive `README.md`.
- `maven-publish` + `signing` configuration with a complete POM (description, license,
  SCM, developer, issue tracker), sources JAR, and Sonatype OSSRH staging repository
  wired for both snapshot and release lanes.
- Publishing coordinates surfaced as Gradle properties
  (`sdk.groupId`, `sdk.artifactId`, `sdk.version`) so the version can be bumped without
  touching `lib/build.gradle.kts`.
- Consumer ProGuard / R8 rules covering the public `gy.pig.spark.**` API, protobuf-lite,
  gRPC stubs, UniFFI / JNA, BouncyCastle, and native JNI methods. Apps minifying their
  release builds no longer need manual keep rules.
- GitHub Actions CI workflow (`.github/workflows/ci.yml`) running build, unit tests, and
  lint on every PR; release workflow (`.github/workflows/release.yml`) for tagged
  publications; Dependabot config; issue / PR templates; `CODEOWNERS`; `FUNDING.yml`.
- Spotless + ktlint formatting (`./gradlew :lib:spotlessCheck`) and Dokka API
  documentation (`./gradlew :lib:dokkaHtml`).
- Kotlin explicit API mode (`explicitApi()`) so public surface cannot leak by accident.
- `samples/quickstart/` minimal CLI demonstrating wallet creation, deposit address,
  invoice, transfer, withdraw.
- Documentation of the native FROST build pipeline (`build-frost-android.sh`) and
  binary provenance requirements in `CONTRIBUTING.md` and `SECURITY.md`.

### Changed
- `.gitignore` extended to cover `.env`, `*.keystore`, `*.jks`, `*.pem`, `*.p12`,
  `secring.gpg`, `.DS_Store`, IDE / VSCode caches, and Dokka output.

### Removed
- `local.properties` and `.DS_Store` removed from version control. They had been
  committed by accident and leaked an absolute SDK path on a maintainer's machine.

### Security
- Documented threat model, scope, and reporting channel in `SECURITY.md`.
- Documented the supply chain for the bundled `libspark_frost.so` /
  `libuniffi_spark_frost.so` native libraries: reproducible from the published
  [`buildonspark/spark`](https://github.com/buildonspark/spark) commit hash via
  `build-frost-android.sh`. PRs that update the binaries must include a SHA-256 hash
  and the upstream commit they were built from.

---

## [0.1.0] — TBD

Initial public release.

### Added
- `SparkWallet` core API with deposits, transfers, withdrawals, Lightning send / receive,
  tokens, swaps, settings, and event streaming via `Flow<SparkEvent>`.
- `SparkConfig` with mainnet and regtest defaults plus pluggable operator / SSP override.
- `SparkError` typed `sealed class` covering key derivation, balance, auth, gRPC,
  GraphQL, FROST signing, token validation, and not-implemented cases.
- gRPC transport via `grpc-okhttp` + `grpc-kotlin-stub` and SSP GraphQL client built on
  OkHttp.
- FROST threshold signing via the bundled `libspark_frost.so` /
  `libuniffi_spark_frost.so` native libraries (Rust UniFFI bindings) for `arm64-v8a`,
  `armeabi-v7a`, `x86`, `x86_64`, all built with 16 KB ELF page alignment.
- Test suite: BIP-39 vectors, key derivation, hex parsing, token validation
  (`SparkSDKTests`, `TokenTests`), and live-network integration coverage
  (`IntegrationTests`) gated on a connected device / emulator.

[Unreleased]: https://github.com/p-i-g-g-y/spark-kotlin-sdk/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/p-i-g-g-y/spark-kotlin-sdk/releases/tag/v0.1.0
