# Native binaries

This SDK ships precompiled native libraries that implement the FROST threshold
signing protocol. They are loaded at runtime by the
[`gy.pig.spark.frost.uniffi.spark_frost`](lib/src/main/kotlin/gy/pig/spark/frost/uniffi/spark_frost)
package via [JNA](https://github.com/java-native-access/jna).

> ⚠️ **Security-critical.** These binaries operate on private key material. Treat
> any change to them with the same care as a change to `SparkSigner.kt` —
> reviewer-required, hash-verified, sourced from a pinned upstream commit.

## What's shipped

| File | ABI | Architecture | Android-only | Size |
|---|---|---|---|---|
| `lib/src/main/jniLibs/arm64-v8a/libspark_frost.so` | `arm64-v8a` | ARMv8 64-bit | ✅ | 4 242 120 bytes |
| `lib/src/main/jniLibs/arm64-v8a/libuniffi_spark_frost.so` | `arm64-v8a` | _alias of the above_ | ✅ | 4 242 120 bytes |
| `lib/src/main/jniLibs/armeabi-v7a/libspark_frost.so` | `armeabi-v7a` | ARMv7 32-bit | ✅ | 3 554 788 bytes |
| `lib/src/main/jniLibs/armeabi-v7a/libuniffi_spark_frost.so` | `armeabi-v7a` | _alias of the above_ | ✅ | 3 554 788 bytes |
| `lib/src/main/jniLibs/x86/libspark_frost.so` | `x86` | Intel 32-bit (emulator) | ✅ | 4 317 300 bytes |
| `lib/src/main/jniLibs/x86/libuniffi_spark_frost.so` | `x86` | _alias of the above_ | ✅ | 4 317 300 bytes |
| `lib/src/main/jniLibs/x86_64/libspark_frost.so` | `x86_64` | Intel 64-bit (emulator) | ✅ | 4 309 520 bytes |
| `lib/src/main/jniLibs/x86_64/libuniffi_spark_frost.so` | `x86_64` | _alias of the above_ | ✅ | 4 309 520 bytes |

**Why two names per ABI?** UniFFI's generated Kotlin bindings call
`Native.load("uniffi_spark_frost")`, but the Rust `cdylib` produced by
`spark-frost-uniffi` is named `spark_frost`. We ship the same bytes under both
names so JNA's library lookup resolves either way. The two files in each ABI
directory are byte-identical copies (verify with the table below).

**16 KB ELF alignment.** All four binaries are built with
`RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"` so they pass the Play Store
16 KB page-size requirement (mandatory from 2025-11-01).

## SHA-256 of currently shipped binaries

Recorded on **2026-05-11**. Regenerate with:

```bash
find lib/src/main/jniLibs -name "*.so" | sort | xargs shasum -a 256
```

```
d515e7ac5128c3bb9e7373dcb819668a71c406b5a2724c8bbc0bb7e47eac55ad  lib/src/main/jniLibs/arm64-v8a/libspark_frost.so
d515e7ac5128c3bb9e7373dcb819668a71c406b5a2724c8bbc0bb7e47eac55ad  lib/src/main/jniLibs/arm64-v8a/libuniffi_spark_frost.so
d6673bfe6cf5a8573431f958ecde75a7f7653c4fc14f63bbceb64875adca13c9  lib/src/main/jniLibs/armeabi-v7a/libspark_frost.so
d6673bfe6cf5a8573431f958ecde75a7f7653c4fc14f63bbceb64875adca13c9  lib/src/main/jniLibs/armeabi-v7a/libuniffi_spark_frost.so
7cbbbca908da821753da6a0b192106bba195d3029a58e5c32255a8582ca4436e  lib/src/main/jniLibs/x86/libspark_frost.so
7cbbbca908da821753da6a0b192106bba195d3029a58e5c32255a8582ca4436e  lib/src/main/jniLibs/x86/libuniffi_spark_frost.so
a1b0475895445934d106cac0bbf8186dd481b197540e08ec8528baab10da0548  lib/src/main/jniLibs/x86_64/libspark_frost.so
a1b0475895445934d106cac0bbf8186dd481b197540e08ec8528baab10da0548  lib/src/main/jniLibs/x86_64/libuniffi_spark_frost.so
```

## Upstream provenance

These binaries are compiled from the `spark-frost-uniffi` crate inside the
[`buildonspark/spark`](https://github.com/buildonspark/spark) repository.

> ⏳ **TODO before public release.** The exact upstream git commit hash for the
> currently shipped binaries is not yet recorded. Before tagging `v0.1.0`, run
> `./build-frost-android.sh` from a known commit, replace the binaries, and
> fill in the section below with:
>
> - Upstream commit: `<sha>`
> - Build date: `YYYY-MM-DD`
> - Builder: `<name / handle>`
> - Build environment: `Docker rust:1.88-bookworm + Android NDK r27c`
> - SHA-256 manifest: re-run the command above and paste the output here
>
> Subsequent updates follow the same template — see
> [CONTRIBUTING.md → "Rebuilding the FROST native libraries"](CONTRIBUTING.md#rebuilding-the-frost-native-libraries).

## How a reviewer verifies the binaries

Anyone — auditor, security researcher, downstream integrator — can independently
verify that the shipped `.so` files match the upstream Rust source.

### 1. Verify SHA-256 of the shipped files

```bash
cd spark-kotlin-sdk
find lib/src/main/jniLibs -name "*.so" | sort | xargs shasum -a 256
```

Compare against the manifest above. Any mismatch means the binaries have been
tampered with locally — re-clone before continuing.

### 2. Reproduce the build

Requires Docker with ~6 GB free disk. The build is fully containerised so the
host OS doesn't matter:

```bash
./build-frost-android.sh
find lib/src/main/jniLibs -name "*.so" | sort | xargs shasum -a 256
```

The output should match the manifest **bit-for-bit** when both:

- The upstream `buildonspark/spark` commit is the one recorded above, and
- The build is run on the same `rust:1.88-bookworm` Docker base image with
  Android NDK r27c.

If your local `./build-frost-android.sh` clones a different upstream commit
(because `git clone --depth 1` always pulls the tip of `main`), the hashes will
diverge. To pin a specific commit, edit the script to `git checkout <sha>`
after the clone.

### 3. Read the source

The `spark-frost-uniffi` crate lives at
`signer/spark-frost-uniffi/` inside the
[`buildonspark/spark`](https://github.com/buildonspark/spark) repository. The
public Rust API surface — what UniFFI exposes to Kotlin — is in
`signer/spark-frost-uniffi/src/spark_frost.udl`.

## Reporting a tampered or malicious binary

Do **not** open a public issue. Follow
[SECURITY.md](SECURITY.md) — email `gm@orklabs.com` or use GitHub's private
vulnerability reporting form.

## Why not download the binaries at build time instead?

Two reasons:

1. **Reproducibility.** A consumer building the AAR gets exactly the bytes the
   maintainers built and reviewed. A download step introduces a moving target
   (CDN, mirror, rebuild) that a malicious actor could intercept.
2. **Offline / air-gapped builds.** Several integrators have asked for fully
   offline builds; shipping the binaries in-tree satisfies that.

The trade-off is repository size (~14 MB of `.so` files compressed in git). We
consider that an acceptable cost for a wallet SDK.
