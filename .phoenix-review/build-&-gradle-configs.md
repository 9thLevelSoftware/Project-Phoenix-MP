# Build & Gradle Configs Review

Reviewed files:
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle/libs.versions.toml`
- `gradle.properties`
- `gradle/wrapper/gradle-wrapper.properties`
- `androidApp/build.gradle.kts`
- `shared/build.gradle.kts`

Validation notes:
- Read all 7 assigned files in full.
- Searched assigned Gradle files for TODO/FIXME/HACK/stub/placeholder/unimplemented markers: none found.
- Parsed `libs.*` version-catalog accessors used by Gradle scripts: 100 references, 0 missing catalog aliases.
- Attempted `./gradlew tasks -Pskip.supabase.check=true --stacktrace --no-daemon`, but this host has no Java runtime installed, so Gradle execution could not proceed.
- Verified externally that the configured Kotlin plugin `2.4.0`, AGP plugin `9.2.1`, and Gradle distribution `9.5.1` are published/available.

## Summary

Findings count: 9

Severity breakdown:
- Critical: 0
- High: 2
- Medium: 5
- Low: 2

Category breakdown:
- Bug: 2
- Stub: 0
- Error: 1
- Failure-point: 6

---

## `build.gradle.kts`

### Finding F-001
- File path: `build.gradle.kts`
- Line number(s): 73-79
- Category: failure-point
- Severity: medium
- Description: `VerifyMigrationTask` mutates daemon-global JVM properties (`java.io.tmpdir` and `org.sqlite.tmpdir`) inside `doFirst`. With `org.gradle.parallel=true`, this global mutation can leak into unrelated tasks running in the same daemon, and the original values are never restored. It also makes the task behavior depend on action ordering and daemon process state rather than a task-local input.
- Suggested fix direction: Prefer a task-local/worker JVM argument or plugin-supported configuration for SQLite temp directories. If the system-property workaround must remain, scope it tightly around the migration verification action and restore the previous values in `finally`, or disable parallelism for the affected task path.

---

## `settings.gradle.kts`

### Finding F-002
- File path: `settings.gradle.kts`
- Line number(s): 14-18
- Category: failure-point
- Severity: low
- Description: `dependencyResolutionManagement` makes `https://jitpack.io` a global repository for all modules. Because it is unrestricted, every dependency can be attempted against JitPack, increasing supply-chain exposure and creating avoidable resolution ambiguity if a coordinate exists in multiple repositories.
- Suggested fix direction: Remove JitPack if no dependency requires it. If it is required, wrap it in `exclusiveContent` with explicit group/module filters for the exact JitPack artifacts that need it.

---

## `gradle/libs.versions.toml`

### Finding F-003
- File path: `gradle/libs.versions.toml`
- Line number(s): 17, 27, 101-104, 131; related usages in `shared/build.gradle.kts` lines 68-72 and `androidApp/build.gradle.kts` line 291
- Category: bug
- Severity: high
- Description: Navigation Compose is declared from two different artifact families and versions: JetBrains Multiplatform Navigation (`org.jetbrains.androidx.navigation:navigation-compose`, version `2.9.2`) for shared/common code, and AndroidX Navigation (`androidx.navigation:navigation-compose`, version `2.9.8`) for the Android app. These artifacts expose overlapping `androidx.navigation.*` APIs and are not version-aligned, which can cause duplicate-class failures, dependency eviction surprises, or runtime ABI mismatches when the Android app consumes the shared Android variant.
- Suggested fix direction: Choose one Navigation Compose artifact family for the Android runtime path, or align versions and add explicit dependency constraints/exclusions so only one implementation reaches the Android app classpath. Verify with `:androidApp:dependencyInsight --dependency navigation-compose` once Java is available.

---

## `gradle.properties`

### Finding F-004
- File path: `gradle.properties`
- Line number(s): 4, 11, 16-17
- Category: failure-point
- Severity: medium
- Description: The Gradle daemon is allowed up to 8 GiB heap while Kotlin/Native is allowed up to 12 GiB, and parallel execution is enabled globally. The comment says macOS runners have about 14 GiB RAM, but these caps can oversubscribe memory when Gradle, Kotlin/Native, Android/R8, and parallel workers overlap. This can cause local or CI OOMs even though each individual cap looks intentional.
- Suggested fix direction: Move the 12 GiB Kotlin/Native setting to CI-specific properties or gate it by environment, lower the default local heap caps, and/or cap worker parallelism for memory-heavy native/release tasks.

---

## `gradle/wrapper/gradle-wrapper.properties`

### Finding F-005
- File path: `gradle/wrapper/gradle-wrapper.properties`
- Line number(s): 3
- Category: failure-point
- Severity: low
- Description: The wrapper pins the Gradle distribution URL but does not pin `distributionSha256Sum`. `validateDistributionUrl=true` checks the URL shape, not the downloaded archive contents, so a corrupted or compromised distribution mirror/cache would not be caught by wrapper checksum verification.
- Suggested fix direction: Add `distributionSha256Sum` for `gradle-9.5.1-bin.zip` from the official Gradle checksum source and keep it updated when bumping Gradle.

---

## `androidApp/build.gradle.kts`

### Finding F-006
- File path: `androidApp/build.gradle.kts`
- Line number(s): 125-166
- Category: error
- Severity: high
- Description: Supabase credentials are read and validated during Gradle configuration, and missing values throw immediately unless `-Pskip.supabase.check=true` is supplied. This blocks unrelated tasks such as `tasks`, `projects`, dependency inspection, IDE sync, lint setup, and many test-only workflows before task selection can narrow the build. It also means simply including/configuring `:androidApp` requires secrets or a skip flag.
- Suggested fix direction: Move required-secret validation into release/runtime verification tasks or variant-specific configuration. Use Gradle `Provider` APIs for env/properties, wire the values lazily to `buildConfigField`, and only fail tasks that actually need non-empty Supabase runtime config.

### Finding F-007
- File path: `androidApp/build.gradle.kts`
- Line number(s): 127-144, 201-203
- Category: bug
- Severity: medium
- Description: Values from `local.properties`/environment are interpolated directly into Java/Kotlin string literals for `BuildConfig` using `"\"$value\""`. If a value contains a quote, backslash, newline, or other literal-significant character, generated `BuildConfig` source can fail to compile or contain a different value than intended. The manual `local.properties` parser also does not implement Java properties escaping/continuations.
- Suggested fix direction: Load properties with `java.util.Properties` or Gradle providers, and escape BuildConfig string values with a Java/Kotlin literal escaper before passing them to `buildConfigField`.

### Finding F-008
- File path: `androidApp/build.gradle.kts`
- Line number(s): 53-66, 260-270
- Category: failure-point
- Severity: medium
- Description: `verifyReleaseCueResources` depends on `assembleRelease` but scans both release APK and release bundle directories. If a stale `.aab` remains from an earlier `bundleRelease`, the task will validate that stale bundle even when the current invocation only rebuilt the APK. Conversely, the task does not explicitly depend on `bundleRelease` when a fresh bundle is required.
- Suggested fix direction: Split APK and bundle verification into separate tasks, or make the task consume explicit artifact providers from `assembleRelease`/`bundleRelease` instead of walking broad output directories. Only validate artifacts produced by the current task graph.

---

## `shared/build.gradle.kts`

### Finding F-009
- File path: `shared/build.gradle.kts`
- Line number(s): 251-279, 282-324, 327-367, 397-402
- Category: failure-point
- Severity: medium
- Description: `validateSchemaManifest` uses regexes and comma-splitting to parse SQL `CREATE TABLE` blocks and Kotlin raw-string SQL. This is fragile for valid SQL constructs containing nested parentheses or commas, such as `CHECK (...)`, composite constraints, default expressions, quoted identifiers, or comments. Because the task is wired into SQLDelight generation, a parser false positive can block builds, while a false negative can let a schema provenance gap through.
- Suggested fix direction: Reuse SQLDelight/SQLite parser metadata if available, or replace comma-splitting with a small balanced-parentheses tokenizer that understands strings/comments/quoted identifiers. Add focused tests/fixtures for constraints and defaults containing commas.
