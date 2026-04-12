package com.devil.phoenixproject.di

/**
 * iOS Keychain Storage Manual Verification Checklist
 *
 * Since iOS tests require macOS + Xcode, this checklist documents the manual
 * verification steps for validating Keychain token storage on iOS.
 *
 * ## Pre-requisites
 * - Physical iOS device or simulator running iOS 14+
 * - Xcode with the Phoenix app installed
 * - Access to device logs (Console.app or Xcode console)
 *
 * ## Test Case 1: Fresh Install - Tokens Stored in Keychain
 * 1. Install fresh build of the app
 * 2. Sign in with valid credentials
 * 3. Verify in logs: "Keychain" appears in token storage logs (not "NSUserDefaults")
 * 4. Kill the app completely
 * 5. Reopen the app
 * 6. Verify user is still authenticated (tokens retrieved from Keychain)
 *
 * ## Test Case 2: Migration from Legacy Storage
 * 1. Install previous build (before Keychain implementation)
 * 2. Sign in with valid credentials
 * 3. Verify tokens are stored in NSUserDefaults (check via debug tools)
 * 4. Upgrade to new build (with Keychain implementation)
 * 5. Open the app
 * 6. Verify in logs: "Migrating portal keys from NSUserDefaults to Keychain"
 * 7. Verify in logs: "Portal key migration to Keychain complete"
 * 8. Verify user is still authenticated
 * 9. Kill and reopen - verify still authenticated
 *
 * ## Test Case 3: Keychain Persistence Across Reinstall
 * 1. Sign in to the app
 * 2. Delete and reinstall the app
 * 3. Open the app
 * 4. Verify user is still authenticated (Keychain data persists across reinstall)
 *
 * ## Test Case 4: NSUserDefaults No Longer Contains Tokens
 * After migration or fresh install:
 * 1. Use a tool like FLEX or lldb to inspect NSUserDefaults
 * 2. Verify none of the following keys exist in NSUserDefaults:
 *    - portal_auth_token
 *    - portal_refresh_token
 *    - portal_token_expires_at
 *    - portal_user_id
 *    - portal_user_email
 *    - portal_user_display_name
 *    - portal_user_is_premium
 *    - portal_device_id
 *    - portal_last_sync_timestamp
 *
 * ## Test Case 5: Migration Failure Handling
 * 1. Simulate Keychain write failure (e.g., full Keychain)
 * 2. Verify app does not crash
 * 3. Verify error is logged: "Failed to migrate portal keys to Keychain"
 * 4. Verify user can re-authenticate manually
 *
 * ## Keychain Security Properties Verified
 * - Service name: "com.devil.phoenixproject.auth" (app-specific)
 * - No access group (not shared with other apps)
 * - Default kSecAttrAccessible (accessible after first unlock)
 * - Protected by iOS Data Protection
 */
object IosKeychainVerificationChecklist
