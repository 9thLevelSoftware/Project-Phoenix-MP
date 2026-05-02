# `Project-Phoenix-MP/audit/`

UX/UI audit deliverable for the Phoenix mobile app (Compose Multiplatform). Audited 2026-05-01.

## Start here

1. **`PLAN.md`** — definitive plan for this repo. Top-fix-first list, severity-ranked findings catalog, sequencing recommendation, mockup index.
2. **`PARITY-COORDINATION.md`** — cross-cutting items requiring coordinated changes with `phoenix-portal`. **Identical copy lives in the portal repo's `audit/` folder.** Keep them in sync.

## Folder layout

```
audit/
├── README.md                       — this file
├── PLAN.md                         ★ start here
├── PARITY-COORDINATION.md          — cross-repo coordination spec (identical in both repos)
│
├── findings/                       — per-stream finding files (raw audit output, full text)
│   ├── 01-mobile-static.md         — 22 findings, Compose static review
│   ├── 03-visual-brand.md          — 21 findings, palette/type/spacing parity (cross-platform)
│   ├── 04-a11y-parity.md           — 19 findings + WCAG ratios + 20-row parity matrix (cross-platform)
│   └── 06-mobile-live.md           — 30 findings, Pixel 8 emulator walkthrough
│
├── mockups/                        — proposed-redesign annotated mockups
│   ├── M-01-mobile-ble-state-machine.md     — 574 lines, BLE pill state machine + ConnectionLostDialog redesign + cause-aware ConnectionErrorDialog + AUTO-START gating
│   └── M-02-mobile-routine-editor.md        — 419 lines, empty state + template picker + save validation + back-handler discard guard + UNDO snackbar parity
│
└── screenshots/                    — 64 PNG captures from emulator walkthrough
    └── *.png                       — Pixel 8 Pro, Android 14, 1080x2400 @ 420dpi
                                      (splash → home → workout-modes → BLE-fail → analytics → insights →
                                       settings → badges → routines → cycles → exercise picker →
                                       font_scale 1.30/1.5 → reduced-motion → light theme → landscape →
                                       airplane mode → +light theme variants)
```

## How findings cross-reference

The master audit at the monorepo root (`../_audit/`, untracked archival) renumbers all findings globally as `G-###`. The PLAN.md in this folder uses **mobile-local IDs** (`M-1`, `M-2`, ...) for the top fix-first list and **per-stream IDs** (`01-F-001`, `06-F-001`, etc.) when referring to specific findings. Cross-repo parity items are tagged `(PARITY)` and linked to `PARITY-COORDINATION.md` sections (`§1`, `§2`, ...).

## Re-running the live walkthrough

The screenshots were produced by driving an Android emulator via `adb shell` + UIAutomator XML dumps. The walkthrough script isn't committed because it depends on emulator state; if you need to re-capture:

```bash
# Prereqs: Android SDK + emulator + AVD (Pixel_8_Pro recommended)
emulator -avd Pixel_8_Pro &
adb wait-for-device
adb shell getprop sys.boot_completed  # wait until "1"

# Build & install debug APK
cd Project-Phoenix-MP
./gradlew :androidApp:installDebug

# Launch
adb shell monkey -p com.devil.phoenixproject.debug -c android.intent.category.LAUNCHER 1

# Capture screenshots manually or via your preferred test runner.
# Reset device state when done:
adb shell settings put system font_scale 1.0
adb shell settings put global animator_duration_scale 1
adb shell "cmd uimode night yes"
adb shell svc data enable && adb shell svc wifi enable
```

The UIAutomator `NAF="true"` flag (used to discover finding M-2 / `06-F-005`) can be re-checked via `uiautomator dump` and grepping the resulting XML.

## Branch & commit hygiene

This audit is on branch **`feat/ux-ui-audit-2026-05-01`** (created from `main` at commit `3932e49a`). Nothing is committed yet — `git add audit/` and commit when you're ready. The `_audit/` folder at the monorepo root is the archival master and is **not** part of this repo (it's untracked at a non-git level above).

## Out of scope for this audit

See `PLAN.md` §9 for the full list. Notably:
- BLE protocol / Nordic stack details
- Sync logic (`SyncManager`, `SyncTriggerManager`, `PortalApiClient`)
- iOS-specific rendering (no Mac available — iOS findings flagged as risks only)
- BLE-connected workout flows (`ActiveWorkoutScreen`, `SetReadyScreen`, `SetSummaryCard`, `RestTimerCard`, `RoutineCompleteScreen`) — not testable on emulator without real Vitruvian hardware. Recommend follow-up on-device live walkthrough
- TalkBack live walkthrough — recommended *after* M-2 (NAF fix) ships
- Performance benchmarking
- Gamification badge computation logic
- Brand voice / copy tone

## Pre-existing repo issues (flagged, not addressed)

- The mobile `CLAUDE.md` Daem0n covenant section references `project_path="C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP"` — actual path is `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP`. Logged in `PARITY-COORDINATION.md §11`.

---

Questions or scope changes? Update `PLAN.md` first; downstream tickets reference it.
