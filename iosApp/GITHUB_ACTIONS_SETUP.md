# GitHub Actions iOS Build Setup

This guide explains how to configure GitHub Actions to automatically build iOS .ipa files and optionally upload to TestFlight.

## Prerequisites

- Apple Developer account ($99/year) - you already have this
- Access to a Mac (one-time, to export certificates)
- Repository admin access (to add secrets)

## Overview

The workflow (`.github/workflows/ios-build.yml`) does:

1. **On Pull Requests**: Builds debug version (no signing) to verify compilation
2. **On Push to main**: Builds signed .ipa and uploads as artifact
3. **Optionally**: Auto-uploads to TestFlight

## Required GitHub Secrets

You need to add these secrets to your repository:
**Settings → Secrets and variables → Actions → New repository secret**

### Supabase Runtime Config

The iOS workflows generate `iosApp/VitruvianPhoenix/Config/Supabase.xcconfig`
from encrypted GitHub secrets before building. The Xcode project reads the
tracked `iosApp/VitruvianPhoenix/Config/SupabaseBase.xcconfig`, which
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

### TestFlight Upload Secrets (Optional)

| Secret Name | Description | How to Get |
|-------------|-------------|------------|
| `APPSTORE_API_KEY_ID` | App Store Connect API Key ID | See Step 4 below |
| `APPSTORE_ISSUER_ID` | App Store Connect Issuer ID | See Step 4 below |
| `APPSTORE_API_KEY` | API Key .p8 file contents | See Step 4 below |

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

### Step 4: Create App Store Connect API Key (Optional - for TestFlight)

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

### Test PR Build (No Signing Required)

1. Create a branch and make a small change to `iosApp/` or `shared/`
2. Open a PR
3. The workflow runs and builds without signing
4. Verify the build succeeds

### Test Full Build (After Adding Secrets)

1. Push to `main` branch
2. Go to **Actions** tab in GitHub
3. Watch the workflow run
4. Download the .ipa artifact when complete

### Manual Trigger

You can also trigger manually:
1. Go to **Actions** → **iOS Build**
2. Click **Run workflow**
3. Select branch and run

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

- This happens on PR builds intentionally (we skip signing for PRs)
- For main branch, ensure all secrets are set

### "No such module 'shared'"

- The XCFramework build may have failed
- Check the "Build shared XCFramework" step logs
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
| `.github/workflows/ios-build.yml` | CI/CD workflow |
| `iosApp/ExportOptions.plist` | Archive export settings |
| `iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj` | Xcode project |

---

## Quick Reference: All Secrets

```
BUILD_CERTIFICATE_BASE64    = base64 of .p12 certificate
P12_PASSWORD                = password for .p12
PROVISION_PROFILE_BASE64    = base64 of .mobileprovision
KEYCHAIN_PASSWORD           = random string (e.g., openssl rand -base64 32)
TEAM_ID                     = 10-char Apple Team ID
PROVISIONING_PROFILE_NAME   = name of profile in Apple Developer

# Optional (for TestFlight):
APPSTORE_API_KEY_ID         = App Store Connect API Key ID
APPSTORE_ISSUER_ID          = App Store Connect Issuer ID
APPSTORE_API_KEY            = contents of .p8 file
```
