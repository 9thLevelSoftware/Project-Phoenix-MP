# Android Installation Guide

This guide explains how to install Project Phoenix on your Android device.

## Prerequisites

- Android phone or tablet running Android 8.0 (Oreo) or later
- Bluetooth Low Energy (BLE) support (most devices from 2015+ have this)

---

## Install from Google Play (Recommended)

Install **[Project Phoenix from Google Play](https://play.google.com/store/apps/details?id=com.devil.phoenixproject)** (currently in open testing on Play; anyone can install it from the listing). Play installs updates automatically.

---

## Installing the APK (Alternative)

If you can't use Google Play, you can install the APK from GitHub instead:

### Step 1: Download the APK

1. Download `ProjectPhoenix-vX.Y.Z.apk` from the [latest GitHub release](https://github.com/9thLevelSoftware/Project-Phoenix-MP/releases/latest)
2. You may see a warning about downloading APK files - tap **Download anyway**

### Step 2: Enable Installation from Unknown Sources

Android requires permission to install apps from outside the Play Store.

**Android 8.0 and later:**
1. When you try to open the APK, Android will prompt you to allow installation
2. Tap **Settings** when prompted
3. Enable **Allow from this source** for your browser or file manager
4. Go back and try opening the APK again

**Or manually:**
1. Go to **Settings > Apps > Special app access > Install unknown apps**
2. Select your browser (Chrome, Firefox, etc.) or file manager
3. Enable **Allow from this source**

### Step 3: Install the App

1. Open the downloaded APK file
2. Tap **Install**
3. Wait for installation to complete
4. Tap **Open** or find **Project Phoenix** in your app drawer

---

## Permissions

When you first launch the app, you'll be asked to grant permissions:

### Bluetooth Permissions
- **Nearby devices** - Required to scan for and connect to your Phoenix trainer
- Tap **Allow** when prompted

### Location Permission
- On Android 11 and below, Bluetooth scanning requires location permission
- This is an Android requirement - the app does not track your location
- Tap **Allow** when prompted

**Note:** If you deny permissions, the app cannot connect to your trainer. You can always grant permissions later in Settings > Apps > Project Phoenix > Permissions.

---

## Updating the App

If you installed from Google Play, updates arrive through Play.

If you installed the APK:
1. Download the new APK from the [latest GitHub release](https://github.com/9thLevelSoftware/Project-Phoenix-MP/releases/latest)
2. Open and install it - Android will update the existing app

---

## Troubleshooting

### "App not installed" Error

- Check that you have enough storage space
- Uninstall any previous version and try again
- Make sure the APK downloaded completely (check file size matches the release)

### Can't Find the Trainer

- Ensure Bluetooth is enabled on your device
- Make sure you granted Bluetooth/Nearby devices permission
- On Android 11 and below, ensure Location is enabled (required for BLE scanning)
- Move closer to your Phoenix trainer
- Try turning your trainer off and on again

### App Crashes on Launch

- Make sure your device is running Android 8.0 or later
- Try clearing app data: Settings > Apps > Project Phoenix > Storage > Clear data
- Report the issue on GitHub with your device model and Android version

### Bluetooth Permission Denied

1. Go to **Settings > Apps > Project Phoenix > Permissions**
2. Enable **Nearby devices** (Android 12+) or **Location** (Android 11 and below)
3. Restart the app

---

## Data & Privacy

- Workout data stays on your device unless you turn on Cloud Sync or an integration
- Location permission is only used for Bluetooth scanning (Android requirement) - we don't track your location
- Uninstalling the app will delete your workout history unless you have backed it up or synced it
- For what is stored on the device and what is sent when you turn on optional features such as Cloud Sync, see the [Privacy Policy](https://9thlevelsoftware.github.io/Project-Phoenix-MP/privacy-policy.html)

### Backup Your Data

The app includes a built-in backup feature:
1. Go to **Settings** tab in the app
2. Tap **Export Data** to save your workout history
3. Store the backup file safely
4. Use **Import Data** to restore on a new device

---

## FAQ

**Q: Is it safe to install APKs?**
A: APKs from trusted sources are safe. Our releases are built from the source code in this repository, which is published under a source-available [proprietary license](LICENSE). You can review the code or build it yourself.

**Q: Will this work on my tablet?**
A: Yes, as long as it has Bluetooth Low Energy support and runs Android 8.0+.

**Q: Why does it need location permission?**
A: Android requires location permission for Bluetooth scanning on Android 11 and below. This is a platform limitation, not something we can change. The app never accesses your actual location.

**Q: What Phoenix devices are supported?**
A:
- Phoenix V-Form Trainer (VIT-200) - devices starting with `Vee_`
- Phoenix Trainer+ - devices starting with `VIT`

---

## Need Help?

If you run into issues, please open an issue on our [GitHub repository](../../issues).
