# iOS Installation Guide

This guide explains how to install Vitruvian Phoenix on your iPhone or iPad.

## Prerequisites

- iPhone or iPad running iOS 14.0 or later
- Bluetooth Low Energy (BLE) support

---

## Option 1: App Store (Recommended)

The easiest way to install Vitruvian Phoenix is from the App Store.

1. Open the **App Store** on your iPhone or iPad
2. Search for **"Vitruvian Phoenix"** or [tap here to go directly to the listing](https://apps.apple.com/app/vitruvian-phoenix/id6740537270)
3. Tap **Get** (or the download icon)
4. Authenticate with Face ID, Touch ID, or your Apple ID password
5. Once installed, tap **Open** or find the app on your home screen

**Benefits of App Store installation:**
- Automatic updates when new versions are released
- Reviewed and signed by Apple
- No expiration or re-signing required
- Easy to install and manage

---

## Option 2: TestFlight (Beta Testing)

Want early access to new features? Join our TestFlight beta program.

### Step 1: Install TestFlight

1. Download **TestFlight** from the App Store (it's free, made by Apple)
2. Open TestFlight

### Step 2: Join the Beta

1. [Tap here to join the Vitruvian Phoenix beta](https://testflight.apple.com/join/YourTestFlightLink)
2. Or open this link on your device: `https://testflight.apple.com/join/YourTestFlightLink`
3. Tap **Accept** to join the beta program
4. Tap **Install** to download the beta version

**TestFlight benefits:**
- Early access to new features before App Store release
- Easy one-tap updates
- Provide feedback directly to developers
- Apps last 90 days between updates (automatic)

---

## Option 3: Sideloading (Advanced)

If you prefer to install directly from GitHub or need a specific version, you can sideload the app.

**Note:** This method requires a computer and apps need to be refreshed every 7 days with a free Apple ID.

### Using AltStore

AltStore is a free app that lets you sideload apps using your Apple ID.

#### Step 1: Install AltServer on Your Computer

**Mac:**
1. Download AltServer from [altstore.io](https://altstore.io/)
2. Open the downloaded file and drag AltServer to your Applications folder
3. Launch AltServer - it will appear in your menu bar

**Windows:**
1. Download AltServer from [altstore.io](https://altstore.io/)
2. Run the installer
3. Launch AltServer - it will appear in your system tray

#### Step 2: Install AltStore on Your iPhone

1. Connect your iPhone to your computer with a USB cable
2. **Trust** the computer on your iPhone if prompted
3. On Mac: Click the AltServer icon in the menu bar > Install AltStore > Select your iPhone
4. On Windows: Click the AltServer icon in the system tray > Install AltStore > Select your iPhone
5. Enter your Apple ID and password when prompted
6. AltStore will appear on your iPhone home screen

#### Step 3: Install Vitruvian Phoenix

1. Download the `VitruvianPhoenix.ipa` file from [GitHub Releases](../../releases)
2. Open AltStore on your iPhone
3. Go to the **My Apps** tab
4. Tap the **+** button in the top left
5. Select the `VitruvianPhoenix.ipa` file
6. Wait for installation to complete

#### Step 4: Trust the App

1. Go to **Settings > General > VPN & Device Management**
2. Tap your Apple ID under "Developer App"
3. Tap **Trust** and confirm

### Keeping Sideloaded Apps Active

Apps installed with a free Apple ID expire after **7 days**. AltStore can refresh apps automatically:

1. Keep AltServer running on your computer
2. Make sure your iPhone and computer are on the **same WiFi network**
3. AltStore will refresh apps automatically in the background

**Tip:** With a paid Apple Developer account ($99/year), apps last 1 year instead of 7 days.

---

## Permissions

When you first launch the app, you'll be asked to grant permissions:

### Bluetooth Permission
- Required to scan for and connect to your Vitruvian trainer
- Tap **OK** or **Allow** when prompted

**Note:** If you deny Bluetooth permission, the app cannot connect to your trainer. You can grant it later in Settings > Vitruvian Phoenix > Bluetooth.

---

## Troubleshooting

### App Won't Open (Sideloaded Only)

- The app may have expired - refresh via AltStore
- Make sure you trusted the developer certificate in Settings

### Can't Find the Trainer

- Ensure Bluetooth is enabled on your device
- Make sure you granted Bluetooth permission to the app
- Move closer to your Vitruvian trainer
- Try turning your trainer off and on again

### App Crashes on Launch

- Make sure your device is running iOS 14.0 or later
- Try deleting and reinstalling the app
- Report the issue on GitHub with your device model and iOS version

---

## Data & Privacy

- All workout data is stored **locally on your device**
- No data is sent to any server
- Deleting the app will delete your workout history

### Backup Your Data

The app includes a built-in backup feature:
1. Go to **Settings** tab in the app
2. Tap **Export Data** to save your workout history
3. Save the backup file to Files, iCloud, or share it
4. Use **Import Data** to restore on a new device

---

## FAQ

**Q: Which installation method should I use?**
A: Use the App Store for the easiest experience. Use TestFlight if you want early access to new features.

**Q: Will I lose my data when updating?**
A: No. Updates preserve all your workout history and settings.

**Q: Can I switch from TestFlight to App Store?**
A: Yes. Install from the App Store and your data will be preserved.

**Q: What Vitruvian devices are supported?**
A:
- Vitruvian V-Form Trainer (VIT-200) - devices starting with `Vee_`
- Vitruvian Trainer+ - devices starting with `VIT`

**Q: Does it work on iPad?**
A: Yes, the app works on any iPad running iOS/iPadOS 14.0 or later with Bluetooth support.

---

## Need Help?

If you run into issues, please open an issue on our [GitHub repository](../../issues).
