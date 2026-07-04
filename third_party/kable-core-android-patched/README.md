# Patched Kable Android Variant

This module vendors the Android sources from `com.juul.kable:kable-core-android:0.43.1`.

Phoenix substitutes only the Android variant of Kable with this project. The common
metadata and non-Android variants still come from the upstream `kable-core`
dependency. The local patch is intentionally narrow: Android characteristic
writes use the legacy one-argument `BluetoothGatt.writeCharacteristic(...)`
path on all API levels, matching the official Vitruvian app while preserving
Kable's operation guard and callback handling.
