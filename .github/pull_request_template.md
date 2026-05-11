## Summary

<!-- What does this PR change and why? Link the issue it resolves. -->

Closes #

## Type of change

- [ ] `feat:` New feature
- [ ] `fix:` Bug fix
- [ ] `docs:` Documentation only
- [ ] `refactor:` Code change that neither fixes a bug nor adds a feature
- [ ] `perf:` Performance improvement
- [ ] `test:` Adding or fixing tests
- [ ] `chore:` Tooling, dependencies, CI
- [ ] **Breaking change** (`feat!:` or `BREAKING CHANGE:` footer)

## Checklist

- [ ] CI is green (`./gradlew :lib:spotlessCheck :lib:testDebugUnitTest :lib:assembleRelease`).
- [ ] Tests added or updated for behaviour changes.
- [ ] Public API additions are KDoc'd.
- [ ] `CHANGELOG.md` updated under `## [Unreleased]`.
- [ ] `README.md` / examples updated if the public API changed.
- [ ] I have read [CONTRIBUTING.md](../CONTRIBUTING.md).

## Security-sensitive changes

If this PR touches `lib/src/main/jniLibs/`, `build-frost-android.sh`, `SparkSigner.kt`,
`KeyDerivation.kt`, or any FROST / key-tweak code:

- [ ] Upstream `buildonspark/spark` commit hash recorded below.
- [ ] SHA-256 hash of any replaced `.so` recorded below (`shasum -a 256 lib/src/main/jniLibs/*/lib*.so`).
- [ ] Reviewer with cryptography sign-off requested.

```
spark commit: <hash>
.so hashes:
<paste output>
```

## Screenshots / output

<!-- For docs, samples, or anything user-facing — paste relevant output / screenshots. -->
