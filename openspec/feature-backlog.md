# Feature Request Backlog

**Last reviewed:** 2026-03-19  
**Open enhancement issues reviewed:** 24  
**Consolidated items:** 22 (2 duplicate pairs merged)

---

## Methodology

All open issues labelled `enhancement` were pulled from the tracker and reviewed.
Each item was assigned a **complexity tier** from **T0** (trivial, hours of work) to **T5**
(epic, months of work / external platform dependency).

### Tier definitions

| Tier | Typical effort | Characteristics |
|------|----------------|-----------------|
| T0 | < 1 day | Single-screen UI tweak or restore of existing behaviour |
| T1 | 1–3 days | Small, isolated feature contained in one area |
| T2 | 3–7 days | Moderate feature; touches several screens or requires light state design |
| T3 | 1–2 weeks | New subsystem, meaningful data-model change, or cross-platform work |
| T4 | 2–4 weeks | Major feature with schema migration, significant UI work, or platform APIs |
| T5 | Months | External API, AI/voice integration, subscription platform, or marketplace |

---

## Duplicate / Overlap Analysis

Two duplicate pairs were found and consolidated into single backlog items:

| Duplicate pair | Reason for consolidation |
|----------------|--------------------------|
| **#296** (Pause/Resume Button) + **#228** (Pause & Restart for timed exercises) | Both request the ability to pause and resume the current workout. #296 targets the rest-timer screen; #228 targets timed-exercise screens. A single "Pause/Resume Workout" implementation covers both. |
| **#233** (Tag exercise after Just Lift) + **#237** (Trigger set start with Just Lift movement) | Both improve the Just Lift mode's awareness of which exercise is being performed. #233 wants post-set exercise labelling; #237 wants motion-triggered set start. Together they form a "smart Just Lift" feature. |

---

## Consolidated Feature List (T0 → T5)

### T0 — Trivial

> Single-screen cosmetic change or restoration of a previously working behaviour.

| # | Issue(s) | Title | Notes |
|---|----------|-------|-------|
| 1 | [#190](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/190) | **Auto-start routine on first tap** | Regression since v0.3.0: user must tap "Start" twice. Remove the redundant confirm step or restore the original single-tap behaviour. |
| 2 | [#201](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/201) | **Show combined cable weight in Just Lift** | Add a "Total" field below the two individual cable weights. Pure calculation + display; no data-model change required. |

---

### T1 — Small

> Isolated, well-understood feature contained in a single area of the app.

| # | Issue(s) | Title | Notes |
|---|----------|-------|-------|
| 3 | [#297](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/297) | **+30 Seconds button on rest countdown** | Add a tappable "+30s" button beside the rest-timer circle. Tapping adds 30 s cumulatively. No data-model change needed. |
| 4 | [#100](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/100) | **Sound improvements** | Louder / more distinct rep beeps, countdown tones at 10 s, correct warm-up beep cadence. Audio-asset and timing tuning only. |

---

### T2 — Moderate

> Touches several screens or requires light state/config additions.

| # | Issue(s) | Title | Notes |
|---|----------|-------|-------|
| 5 | [#266](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/266) | **Granular weight increments (0.1 lb / 0.1 kg option)** | Add a user setting for minimum weight step. Affects weight picker UI and BLE packet construction. Current floor is 0.5 lbs/kg. |
| 6 | [#152](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/152) | **Navigate forward / backward during a workout** | Add back/forward controls so the user can revisit a previous exercise or skip ahead without aborting the session. |
| 7 | [#296](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/296) + [#228](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/228) *(consolidated)* | **Pause / Resume workout** | Unified pause state covering both the between-set rest timer (#296) and in-progress timed exercises (#228). Requires a workout-level `isPaused` flag and graceful BLE suspension. |
| 8 | [#29](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/29) | **Per-set Echo level in routines** | Allow each set in a routine to specify its own Echo mode level. Routine editor + BLE packet change per set; no schema migration needed if stored as a new optional set field. |

---

### T3 — Significant

> New subsystem, meaningful data-model change, or non-trivial cross-platform work.

| # | Issue(s) | Title | Notes |
|---|----------|-------|-------|
| 9 | [#113](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/113) | **Rest timer for Just Lift mode** | Just Lift currently has no inter-set rest timer. Add a configurable rest countdown (scrollable dial) that appears after each Just Lift set. |
| 10 | [#259](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/259) | **Per-exercise rest times within supersets** | Supersets currently share one rest value. Allow each exercise in a superset to carry its own rest period, or inherit its individual exercise rest setting. |
| 11 | [#225](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/225) | **Learn estimated routine duration from history** | Replace static time estimates with an average of the user's actual completion times for that routine. Requires querying `WorkoutSession` history. |
| 12 | [#293](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/293) | **Auto-backup to device filesystem after workout** | Add a Settings toggle. On workout completion, automatically write a JSON backup to a user-accessible location (Documents / Files) without manual intervention. |
| 13 | [#226](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/226) | **Import / correct workout data from file** | Allow users to delete erroneous sessions and re-import corrected JSON/CSV. Clarify file-picker target location; add a data-validation step on import. |
| 14 | [#30](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/30) | **Variable warm-up sets** | Let users define custom warm-up sets (reps + % of working weight) before the first working set of each exercise. Requires routine-editor additions and BLE weight scaling. |

---

### T4 — Large

> Schema migration, significant new UI, or use of platform APIs (filesystem, health, i18n).

| # | Issue(s) | Title | Notes |
|---|----------|-------|-------|
| 15 | [#233](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/233) + [#237](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/237) *(consolidated)* | **Smart Just Lift: exercise tagging & motion-triggered start** | (a) Post-set exercise label picker so Just Lift data is attributable to a specific exercise. (b) Motion-based set-start trigger (hold handles in position) to avoid false warm-up reps. Together these bring Just Lift up to routine-quality tracking. |
| 16 | [#229](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/229) | **Body-weight exercise volume metrics** | Add a body-weight field in Settings. Define per-exercise BW coefficients (push-ups ≈ 64 %, pull-ups, decline variants, etc.) and prompt for reps after timed exercises. Feeds volume totals in Analytics. |
| 17 | [#136](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/136) | **Exercise progress charts** | Historical line/bar charts per exercise showing weight, reps, and/or estimated 1RM over time. Requires a charting library and aggregation queries against `MetricSample` / `WorkoutSession`. |
| 18 | [#111](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/111) | **Mode-split PR tracking** | Track personal records independently per `WorkoutMode` (e.g. Echo vs. Old School) because peak loads differ meaningfully across modes. Requires a schema migration on `PersonalRecord` to add a `mode` column. |
| 19 | [#238](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/238) | **Multi-language / localisation support** | Extract all user-visible strings into resource files and ship translated bundles. Requires coordination across both the Android (strings.xml) and iOS (Localizable.strings / Swift strings catalogue) targets. |

---

### T5 — Epic

> External platform, AI/hardware integration, or subscription infrastructure.

| # | Issue(s) | Title | Notes |
|---|----------|-------|-------|
| 20 | [#141](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/141) | **Voice "safe word" to stop / deload** | Use the device microphone and an on-device speech recogniser to detect a configurable word (e.g. "STOP") and immediately send a deload command over BLE. Safety-critical; requires careful hardware latency analysis and platform audio-session handling. |
| 21 | [#202](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/202) | **Liftosaur API integration** | Push completed sets (weight, reps, exercise name) to the Liftosaur platform after each workout. Requires OAuth/API-key auth, exercise-name mapping, and network-layer additions. |
| 22 | [#103](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/103) | **Published workouts marketplace** | Allow certified trainers to publish routines that subscribers can download and follow. Requires a backend, payment/subscription infrastructure, a trainer portal, and content-moderation tooling—well outside the current scope of a BLE control app. |

---

## Summary

| Tier | Count | Issues |
|------|-------|--------|
| T0 | 2 | #190, #201 |
| T1 | 2 | #297, #100 |
| T2 | 4 | #266, #152, #296+#228, #29 |
| T3 | 6 | #113, #259, #225, #293, #226, #30 |
| T4 | 5 | #233+#237, #229, #136, #111, #238 |
| T5 | 3 | #141, #202, #103 |
| **Total** | **22** | *(24 raw issues; 2 duplicate pairs merged)* |
