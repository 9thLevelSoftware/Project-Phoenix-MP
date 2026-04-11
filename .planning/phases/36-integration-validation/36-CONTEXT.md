# Phase 36: Integration Validation — Context

## Phase Goal
End-to-end verification that all v0.8.0 fixes work together across platforms. No new code — verification only.

## Requirements
- VAL-01: BLE connect + reconnect scenario validation
- VAL-02: Sync E2E + profile isolation validation
- VAL-03: iOS launch + session load + schema validation

## Plan Structure
| Plan | Wave | Focus | Agent |
|------|------|-------|-------|
| 36-01: BLE Validation | 1 | Build, test, code review of Phase 32 fixes | Senior Developer |
| 36-02: Sync Validation | 1 | Build, test, profile isolation, sync DTO check | Senior Developer |
| 36-03: iOS Validation | 1 | Build, schema version check, column completeness | Senior Developer |

All 3 plans run in parallel (single wave) — they verify different subsystems with no overlap.
