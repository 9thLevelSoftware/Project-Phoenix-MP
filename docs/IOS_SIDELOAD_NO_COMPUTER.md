# iOS Sideloading Without a Computer

While we wait for TestFlight approval, you can install Project Phoenix using a signing service directly from your iPhone.

## Option 1: Signulous (Recommended)

**Cost:** $19.99/year

1. Visit [signulous.com](https://signulous.com) on your iPhone
2. Create an account and purchase a subscription
3. Download the Signulous app and install the profile when prompted
4. Go to **Settings > General > VPN & Device Management** and trust the profile
5. Open Signulous and tap **"Import IPA"**
6. Download the IPA from our [GitHub Releases](https://github.com/DasBluEyedDevil/Project-Phoenix-MP/releases)
7. Select the downloaded IPA file
8. Tap **"Sign & Install"**
9. Wait for installation to complete
10. Trust the app: **Settings > General > VPN & Device Management > [Developer name] > Trust**

## Option 2: AltStore PAL (EU Only)

**Cost:** €1.50/year
**Availability:** European Union only (due to Digital Markets Act)

1. Visit [altstore.io](https://altstore.io) on your iPhone
2. Download AltStore PAL from the website
3. Subscribe to enable sideloading
4. Download the IPA from our [GitHub Releases](https://github.com/DasBluEyedDevil/Project-Phoenix-MP/releases)
5. Open the IPA with AltStore
6. Tap Install

## Option 3: Other Signing Services

These work similarly to Signulous:

- **AppDB Pro** - appdb.to ($21.99/year)
- **Scarlet** - usescarlet.com (free, less reliable)
- **ESign** - esign.yyyue.xyz (free, requires more setup)

## Important Notes

- **Certificate Revocation:** Apple periodically revokes enterprise certificates. If the app stops opening, you'll need to reinstall.
- **Re-signing Required:** Free options require re-signing every 7 days. Paid services handle this automatically.
- **TestFlight Coming Soon:** Once our TestFlight build is approved, that will be the easiest and most reliable method.

## Troubleshooting

**"Unable to Install" error:**
- Make sure you have enough storage
- Try restarting your iPhone
- Delete any previous version of the app first

**"Untrusted Developer" error:**
- Go to Settings > General > VPN & Device Management
- Find the developer profile and tap "Trust"

**App crashes on launch:**
- The certificate may have been revoked
- Reinstall using your signing service

## TestFlight (Recommended - Coming Soon)

Once approved, TestFlight will be the best option:
- Free
- No certificate revocation issues
- Automatic updates
- No re-signing needed

Join the waitlist: [testflight.apple.com/join/TFw1m89R](https://testflight.apple.com/join/TFw1m89R)
