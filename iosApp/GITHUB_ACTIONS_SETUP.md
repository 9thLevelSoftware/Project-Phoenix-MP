# GitHub Actions iOS Build Setup

This guide explains how to configure GitHub Actions to automatically build iOS .ipa files and optionally upload to TestFlight.

## Prerequisites

- Apple Developer account ($99/year) - you already have this
- Access to a Mac (one-time, to export certificates)
- Repository admin access (to add secrets)

## Overview

All iOS build workflows are **manually triggered** (`workflow_dispatch`), or called
by the release workflows (`workflow_call`). None runs on pull requests or on push:

| Workflow | File | What it does |
|----------|------|--------------|
| iOS TestFlight | `.github/workflows/ios-testflight.yml` | Builds a signed .ipa, uploads it to App Store Connect, and adds it to the TestFlight group. Set `skip_distribution` to upload without group distribution |
| iOS Release IPA | `.github/workflows/ios-release-ipa.yml` | Builds a signed .ipa and attaches it to a GitHub release |
| Release All Platforms | `.github/workflows/release-all.yml` | Creates the `v<version>` tag and runs the Play Store, APK, IPA and TestFlight workflows |
| Release All (Existing) | `.github/workflows/release-all-existing.yml` | Re-runs the Play Store, APK, IPA and TestFlight workflows for an existing release tag |

**iOS is not built on pull requests.** On PRs, `ci-tests.yml` only compiles the
shared Kotlin module for the iOS target on Linux (`ios-target-tests-compile`). It
never runs `xcodebuild` or produces an .ipa, and it doesn't run the iOS tests.

## Required GitHub Secrets

You need to add these secrets to your repository:
**Settings → Secrets and variables → Actions → New repository secret**

### Supabase Runtime Config

The iOS workflows generate `iosApp/PhoenixApp/Config/Supabase.xcconfig`
from encrypted GitHub secrets before building. The Xcode project reads the
tracked `iosApp/PhoenixApp/Config/SupabaseBase.xcconfig`, which
optionally includes that generated local file. The real xcconfig file is
ignored by git and must stay local; only templates and base configs are tracked.

| Secret Name | Description |
|-------------|-------------|
| `SUPABASE_URL` | Supabase project URL for the app runtime |
| `SUPABASE_ANON_KEY` | Supabase anon key for the app runtime |

If a real anon key was ever committed, rotate it in Supabase and update these
GitHub secrets before the next release.

### Core Signing Secrets (Required)

| Secret Name | Description | How to Get |
|-------------|-------------|------------|
| `BUILD_CERTIFICATE_BASE64` | Distribution certificate as base64 | See Step 1 below |
| `P12_PASSWORD` | Password for the .p12 file | You set this when exporting |
| `PROVISION_PROFILE_BASE64` | Provisioning profile as base64 | See Step 2 below |
| `KEYCHAIN_PASSWORD` | Any random password | Generate: `openssl rand -base64 32` |
| `TEAM_ID` | 10-character Apple Team ID | See Step 3 below |
| `PROVISIONING_PROFILE_NAME` | Name of provisioning profile | e.g., "Phoenix Distribution" |

### App Store Connect Secrets (TestFlight only)

`ios-testflight.yml` uses the API key secrets and `APP_APPLE_ID`. `TESTFLIGHT_GROUP_NAME` is required unless the run sets `skip_distribution`. `ios-release-ipa.yml` needs only the signing and Supabase secrets.

| Secret Name | Description | How to Get |
|-------------|-------------|------------|
| `APPSTORE_API_KEY_ID` | App Store Connect API Key ID | See Step 4 below |
| `APPSTORE_ISSUER_ID` | App Store Connect Issuer ID | See Step 4 below |
| `APPSTORE_API_KEY` | API Key .p8 file contents | See Step 4 below |
| `APP_APPLE_ID` | The app's numeric Apple ID (App Store Connect → App Information) | Used by `ios-testflight.yml` |
| `TESTFLIGHT_GROUP_NAME` | TestFlight beta group to add builds to | Used by `ios-testflight.yml` unless `skip_distribution` is set |

---

## Step-by-Step Setup

### Step 1: Export Distribution Certificate

**On your Mac:**

1. Open **Keychain Access** (Applications → Utilities)
2. In the left sidebar, select **login** keychain
3. Select **My Certificates** category
4. Find your **Apple Distribution** certificate (or create one in Apple Developer portal)
5. Right-click → **Export**
6. Save as `.p12` file with a strong password
7. Convert to base64:
   ```bash
   base64 -i Certificates.p12 | pbcopy
   ```
8. Paste into GitHub secret `BUILD_CERTIFICATE_BASE64`
9. Save the password as `P12_PASSWORD`

**If you don't have a distribution certificate:**

1. Go to [Apple Developer → Certificates](https://developer.apple.com/account/resources/certificates/list)
2. Click **+** to create new certificate
3. Select **Apple Distribution**
4. Follow the CSR creation process
5. Download and install the certificate
6. Then export as above

### Step 2: Create Provisioning Profile

1. Go to [Apple Developer → Profiles](https://developer.apple.com/account/resources/profiles/list)
2. Click **+** to create new profile
3. Select **App Store Connect** (for distribution)
4. Select your App ID: `com.devil.phoenixproject.projectphoenix`
   - If it doesn't exist, create it under **Identifiers** first
5. Select your Distribution certificate
6. Name it (e.g., "Phoenix Distribution")
7. Download the `.mobileprovision` file
8. Convert to base64:
   ```bash
   base64 -i Phoenix_Distribution.mobileprovision | pbcopy
   ```
9. Paste into GitHub secret `PROVISION_PROFILE_BASE64`
10. Save the profile name as `PROVISIONING_PROFILE_NAME`

### Step 3: Find Your Team ID

1. Go to [Apple Developer → Membership](https://developer.apple.com/account/#!/membership)
2. Your **Team ID** is listed (10-character alphanumeric, e.g., `ABC123XYZ9`)
3. Save as `TEAM_ID`
4. **Also update** `iosApp/ExportOptions.plist`:
   ```xml
   <key>teamID</key>
   <string>YOUR_ACTUAL_TEAM_ID</string>
   ```

### Step 4: Create App Store Connect API Key (for the TestFlight workflows)

1. Go to [App Store Connect → Users and Access → Keys](https://appstoreconnect.apple.com/access/api)
2. Click **+** to generate a new key
3. Name: "GitHub Actions"
4. Access: **App Manager** (or Admin)
5. Click **Generate**
6. **Download the .p8 file immediately** (can only download once!)
7. Note the **Key ID** shown
8. Note the **Issuer ID** at the top of the page
9. Save as secrets:
   - `APPSTORE_API_KEY_ID` = Key ID
   - `APPSTORE_ISSUER_ID` = Issuer ID
   - `APPSTORE_API_KEY` = Contents of the .p8 file

### Step 5: Update ExportOptions.plist

Edit `iosApp/ExportOptions.plist` with your actual values:

```xml
<key>teamID</key>
<string>YOUR_TEAM_ID</string>

<key>provisioningProfiles</key>
<dict>
    <key>com.devil.phoenixproject.projectphoenix</key>
    <string>YOUR_PROVISIONING_PROFILE_NAME</string>
</dict>
```

Commit this change.

### Step 6: Create App ID (if needed)

If you haven't registered the App ID:

1. Go to [Apple Developer → Identifiers](https://developer.apple.com/account/resources/identifiers/list)
2. Click **+**
3. Select **App IDs** → **App**
4. Bundle ID: `com.devil.phoenixproject.projectphoenix` (Explicit)
5. Description: "Project Phoenix"
6. Enable capabilities:
   - **Background Modes** (for BLE)
   - Check "Uses Bluetooth LE accessories"
7. Register

---

## Testing the Workflow

### Manual Trigger

After adding the secrets:
1. Go to **Actions** → **iOS TestFlight**
2. Click **Run workflow**
3. Select the branch. Leave `skip_distribution` off to add the build to the TestFlight group. Turn it on to upload only: icon validation and unit tests still run, then the build is uploaded to App Store Connect and the workflow stops before the tester group, Beta App Review, and the `testflight/*` tag.
4. Watch the run and check that the build appears in App Store Connect. When distribution ran, it also appears in the TestFlight group.

---

## Troubleshooting

### "No signing certificate found"

- Verify `BUILD_CERTIFICATE_BASE64` is correctly base64 encoded
- Check `P12_PASSWORD` matches what you used when exporting
- Ensure the certificate isn't expired

### "Provisioning profile doesn't match"

- The bundle ID in the profile must exactly match `com.devil.phoenixproject.projectphoenix`
- The profile must include your distribution certificate
- Re-download and re-encode the profile if recently regenerated

### "Code signing is required"

- Ensure all signing secrets are set

### "No such module 'shared'"

- The shared framework build may have failed
- Check the "Build shared framework and resources" step logs
- Ensure Gradle is set up correctly

### TestFlight upload fails

- Verify API key has correct permissions (App Manager or Admin)
- Check the .p8 file contents are complete (including BEGIN/END lines)
- Ensure the app version/build number is incremented

---

## Security Notes

- Never commit certificates or provisioning profiles to the repo
- Use GitHub's encrypted secrets for all sensitive data
- The keychain is created temporarily and deleted after each run
- Consider using environments for production vs. staging builds

---

## File Locations

| File | Purpose |
|------|---------|
| `.github/workflows/ios-testflight.yml` | TestFlight build, upload, and optional group distribution |
| `.github/workflows/ios-release-ipa.yml` | Release .ipa for a GitHub release |
| `.github/workflows/release-all.yml` | Full release across platforms |
| `iosApp/ExportOptions.plist` | Archive export settings |
| `iosApp/PhoenixApp/PhoenixApp.xcodeproj` | Xcode project |

---

## Quick Reference: All Secrets

```
BUILD_CERTIFICATE_BASE64    = base64 of .p12 certificate
P12_PASSWORD                = password for .p12
PROVISION_PROFILE_BASE64    = base64 of .mobileprovision
KEYCHAIN_PASSWORD           = random string (e.g., openssl rand -base64 32)
TEAM_ID                     = 10-char Apple Team ID
PROVISIONING_PROFILE_NAME   = name of profile in Apple Developer

SUPABASE_URL                = Supabase project URL
SUPABASE_ANON_KEY           = Supabase anon key

# App Store Connect (ios-testflight.yml):
APPSTORE_API_KEY_ID         = App Store Connect API Key ID
APPSTORE_ISSUER_ID          = App Store Connect Issuer ID
APPSTORE_API_KEY            = contents of .p8 file
APP_APPLE_ID                = numeric Apple ID of the app
TESTFLIGHT_GROUP_NAME       = TestFlight beta group (not used when skip_distribution is set)
```
