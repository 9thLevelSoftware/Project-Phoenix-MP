# MVP Cloud Sync — E2E Test Procedure

**Version**: 0.7.0
**Date**: 2026-03-15
**Platforms**: Android (primary), iOS (secondary)

---

## Pre-conditions

- [ ] Phoenix Portal deployed to production domain (Vercel + Supabase Edge Functions)
- [ ] Supabase secrets configured (`APP_URL`, `ENVIRONMENT=production`, `REVENUECAT_WEBHOOK_SECRET`)
- [ ] Edge Functions deployed (`mobile-sync-push`, `mobile-sync-pull`)
- [ ] Test device with Android 8.0+ (SDK 26) or iOS 15+
- [ ] Vitruvian Trainer available (or Simulator Mode enabled in Settings → Developer Tools)

---

## Test Cases

### TC-01: Portal Sign Up
1. Open portal in browser → Landing page
2. Click Sign Up → Enter display name, email, password
3. Check email → Click confirmation link
4. Verify: Redirected to portal dashboard (empty state)
- **Pass criteria**: Account created, email confirmed, dashboard accessible

### TC-02: Mobile Sign In
1. Open app → Settings tab (first tab-bar item from right)
2. **Cloud Sync card should be the FIRST card** at the top of Settings
3. Tap "Link Portal Account"
4. LinkAccountScreen opens → Select "Login" tab
5. Enter email + password from TC-01 → Tap Sign In
6. Verify: Screen shows user email, display name, sync status
- **Pass criteria**: Authenticated state shows, no errors

### TC-03: Auto-Sync on App Foreground
1. After TC-02, minimize app (home button)
2. Wait 5+ seconds, return to app
3. Check LinkAccountScreen → sync status should update
- **Pass criteria**: "Last synced: [timestamp]" updates (may show "never" if no data yet)
- **Note**: Auto-sync has 5-minute throttle for foreground triggers

### TC-04: Workout Push Sync
1. Start a workout (connect to trainer or use Simulator Mode)
2. Complete at least 3 reps of any exercise
3. End workout → save session
4. Sync should trigger automatically (no throttle for workout completion)
5. Open portal dashboard in browser
6. Verify: Workout session appears with correct:
   - Exercise name
   - Number of sets and reps
   - Weight values
   - Session duration
- **Pass criteria**: Workout data matches between mobile and portal

### TC-05: Routine Pull Sync
1. On portal: Go to Routines → New Routine
2. Create a routine with 2-3 exercises, give it a unique name
3. Save the routine
4. On mobile: Open LinkAccountScreen → tap "Sync Now"
5. Navigate to Routines/Workouts → verify the new routine appears
- **Pass criteria**: Routine name, exercises, and settings match portal

### TC-06: Offline Resilience
1. Enable Airplane Mode on device
2. Open Settings → Cloud Sync → tap "Link Portal Account"
3. Tap "Sync Now"
4. Verify: Error message shown (not a crash)
5. Disable Airplane Mode
6. Tap "Sync Now" again
7. Verify: Sync succeeds
- **Pass criteria**: Graceful error handling, recovery on reconnect

### TC-07: Sync Error Indicator
1. Enable Airplane Mode
2. Trigger sync 3+ times (minimize/restore app 3 times with >5min gaps, or manipulate for testing)
3. Check Settings → Cloud Sync card
4. Verify: Warning icon + "Sync error — tap above to retry" appears below the card description
5. Disable Airplane Mode → sync successfully
6. Verify: Error indicator disappears
- **Pass criteria**: Error indicator shows after 3+ failures, clears on success
- **Note**: May be difficult to trigger naturally due to 5-min throttle. Consider testing with modified throttle or by temporarily breaking the Supabase URL.

### TC-08: Sign Out / Unlink
1. Open Settings → Cloud Sync → tap "Link Portal Account"
2. Tap "Unlink Account"
3. Verify: Returns to login/signup form
4. Verify: Auto-sync stops (no sync attempts on foreground)
- **Pass criteria**: Clean sign-out, no lingering auth state

### TC-09: Navigation Flow (UI Only)
1. Launch app
2. Verify: Bottom nav shows 3 tabs (Analytics, Workouts, Settings)
3. Verify: NO Insights tab visible
4. Navigate to Settings → verify Cloud Sync is FIRST card
5. Tap "Link Portal Account" → verify LinkAccountScreen opens
6. Press back → verify Settings returns cleanly
7. Navigate to Workouts → verify NO Strength Assessment button
8. Verify: NO Color Blind Mode toggle in Settings
- **Pass criteria**: All UX cleanup changes are effective

---

## Known Limitations

- Rep telemetry (50Hz force curves) is NOT synced — deferred to v0.8.0
- Community features (sharing, voting) are portal-only — no mobile UI
- 3rd-party integrations (Strava, Fitbit, Garmin) are portal-only
- iOS TestFlight build requires macOS CI runner + Apple Developer credentials

---

## Reporting

For each test case, record:
- **Status**: Pass / Fail / Blocked
- **Notes**: Any unexpected behavior, performance issues, or UX concerns
- **Screenshots**: Capture key states (sync success, error indicator, portal data)
