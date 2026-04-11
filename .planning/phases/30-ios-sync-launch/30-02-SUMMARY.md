# Plan 30-02 Summary: iOS Build & TestFlight Verification

**Status**: Complete
**Agent**: Direct execution

## What Was Done

### Task 1: Ktor Darwin Engine Dependency — CONFIRMED

- `shared/build.gradle.kts` line 193: `implementation(libs.ktor.client.darwin)` in iosMain source set
- `gradle/libs.versions.toml` line 163: `ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }`
- Ktor version: 3.3.3 (consistent across all Ktor dependencies: core, okhttp, darwin, content-negotiation, serialization)
- No conflicting HTTP engines in the iOS target

### Task 2: TestFlight CI Workflow Review — COMPATIBLE

Reviewed `.github/workflows/ios-testflight.yml` (manual trigger workflow):

**Build pipeline** (all steps verified):
1. Checkout → Java 17 → Gradle setup → Kotlin/Native cache
2. Auto-increment build number via `CURRENT_PROJECT_VERSION` in pbxproj
3. `linkReleaseFrameworkIosArm64` → `generateComposeResClass` → `iosArm64ProcessResources`
4. Copy Compose resources to framework bundle (4 fallback paths)
5. Create XCFramework → Link framework
6. Select Xcode 26.2 → Install cert + profile
7. `xcodebuild archive` → `xcodebuild -exportArchive`
8. Upload to TestFlight via `altool`
9. Add to beta group via App Store Connect API

**Info.plist compatibility**:
- The workflow modifies `CFBundleVersion` via `sed` targeting `<!-- CFBundleVersion -->` comment pattern
- Our Info.plist does NOT have this comment, so the sed is a no-op (with `|| true` fallback)
- Our `SUPABASE_URL` and `SUPABASE_ANON_KEY` entries are safe — not touched by any build step
- Build number is primarily set via `CURRENT_PROJECT_VERSION` in pbxproj (not Info.plist)

**Credential security**: Supabase publishable keys in Info.plist are safe for client-side distribution. Same pattern as Android's BuildConfig approach.

### Task 3: Deployment Blockers Documented

**Required GitHub Secrets for TestFlight** (10 total):

| Secret | Purpose | Status |
|--------|---------|--------|
| `BUILD_CERTIFICATE_BASE64` | Apple Distribution certificate (.p12) | Unknown — check repo settings |
| `P12_PASSWORD` | Certificate password | Unknown |
| `PROVISION_PROFILE_BASE64` | Provisioning profile | Unknown |
| `KEYCHAIN_PASSWORD` | Temp keychain password (any string) | Unknown |
| `TEAM_ID` | Apple Developer Team ID | Unknown |
| `PROVISIONING_PROFILE_NAME` | Profile specifier | Unknown |
| `APPSTORE_API_KEY` | App Store Connect API key (.p8) | Unknown |
| `APPSTORE_API_KEY_ID` | Key ID | Unknown |
| `APPSTORE_ISSUER_ID` | Issuer ID | Unknown |
| `TESTFLIGHT_GROUP_NAME` | Beta group name | Unknown |

**Known Blockers**:

1. **Pre-existing `@Volatile` error** in `BlePacketFactory.kt:21` — blocks `compileKotlinIosArm64` and therefore `linkReleaseFrameworkIosArm64`. Must be fixed before any iOS build can succeed. Fix: add `import kotlin.concurrent.Volatile` or use `AtomicReference` pattern.

2. **Info.plist version is stale**: Shows `0.1.1` / `2025122504`. Needs update to `0.7.0` in Phase 31 (version bump task).

3. **GitHub secrets status unknown**: The 10 Apple Developer secrets may or may not be configured. Verify in GitHub repo settings → Secrets.

4. **macOS runner required**: Workflow uses `macos-26`. GitHub Actions macOS runners are available but slower and more expensive than Linux runners.

5. **Xcode 26.2 assumption**: Workflow hardcodes `Xcode_26.2.app`. If the runner has a different version, the `xcode-select` step will fail.

## Files Modified

None (read-only verification plan).

## Verification

- [x] Ktor Darwin engine dependency confirmed (shared/build.gradle.kts line 193, version 3.3.3)
- [x] TestFlight workflow reviewed — compatible with Info.plist changes
- [x] Required GitHub secrets documented (10 secrets)
- [x] Deployment blockers documented (5 items, 1 code blocker)
- [x] No workflow modifications needed
