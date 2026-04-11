# Plan 31-02 Summary: Sync Discoverability & Error Indicator

**Status**: Complete

## What Was Done

1. **Moved Cloud Sync card to top of SettingsTab** — now the first card users see
2. **Added SyncTriggerManager injection** + `hasPersistentError` observer via `collectAsState()`
3. **Added conditional error indicator** — Warning icon + "Sync error — tap above to retry" in error color, visible only when 3+ consecutive sync failures

## Files Modified
- SettingsTab.kt (moved Card block, added import + state + error UI)

## Verification
- [x] Cloud Sync is first card in Settings
- [x] Error indicator conditionally renders
- [x] assembleDebug passes
