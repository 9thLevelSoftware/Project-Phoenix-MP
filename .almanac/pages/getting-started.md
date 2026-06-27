---
title: Getting Started
summary: This page is the wiki's navigation hub and routes future agents to the few synthesis pages that establish the Phoenix mental model fastest.
topics: [systems, product]
sources:
  - id: phoenix-page
    type: file
    path: .almanac/pages/project-phoenix.md
    note: Establishes the product and hardware constraints that only some tasks need up front.
  - id: architecture-page
    type: file
    path: .almanac/pages/app-architecture.md
    note: Establishes the shared app entry point and main runtime boundaries for most coding tasks.
  - id: frontend-page
    type: file
    path: .almanac/pages/frontend.md
    note: Establishes the shared Compose UI-boundary hub for screen ownership, route placement, and presentation-layer questions.
  - id: settings-page
    type: file
    path: .almanac/pages/settings-surface.md
    note: Establishes the shared Settings route as a cross-cluster operational surface instead of a pure preferences screen.
  - id: workouts-page
    type: file
    path: .almanac/pages/workouts.md
    note: Establishes the workout cluster hub and its drill-down order.
  - id: routines-page
    type: file
    path: .almanac/pages/routines-and-training-cycles.md
    note: Establishes the routine-editor and training-cycle programming boundary inside the workout cluster.
  - id: assessment-page
    type: file
    path: .almanac/pages/strength-assessment-and-insights.md
    note: Establishes the local 1RM assessment and Smart Insights layer that sits inside the workout cluster.
  - id: workout-safety-page
    type: file
    path: .almanac/pages/workout-safety-and-feedback.md
    note: Establishes workout cue playback and safe-word stop behavior inside the workout cluster.
  - id: diagnostics-page
    type: file
    path: .almanac/pages/machine-diagnostics.md
    note: Establishes the machine diagnostics troubleshooting workflow and redacted export contract inside the workout cluster.
  - id: sync-page
    type: file
    path: .almanac/pages/sync.md
    note: Establishes the remote-sync cluster hub and its drill-down order.
  - id: auth-page
    type: file
    path: .almanac/pages/auth.md
    note: Establishes the narrower login, OAuth callback, token storage, and account-linking hub inside the remote cluster.
  - id: premium-page
    type: file
    path: .almanac/pages/premium-entitlements.md
    note: Establishes the entitlement-resolution page that sits between auth state and premium feature gates.
  - id: integrations-page
    type: file
    path: .almanac/pages/integrations.md
    note: Establishes the integrations bridge that separates device-local health-store behavior from portal-fed provider sync.
  - id: health-page
    type: file
    path: .almanac/pages/health-platform-integration.md
    note: Establishes the device-local health-store export and body-weight import leaf inside the integrations cluster.
  - id: provider-page
    type: file
    path: .almanac/pages/external-provider-sync.md
    note: Establishes the portal-fed provider-sync leaf that populates local external-data repositories.
  - id: portal-page
    type: file
    path: .almanac/pages/portal-sync-transport.md
    note: Establishes the authenticated remote transport layer that starts after login.
  - id: csv-page
    type: file
    path: .almanac/pages/csv-workout-import-export.md
    note: Establishes the local CSV import or export flow inside the integrations area.
  - id: data-page
    type: file
    path: .almanac/pages/data.md
    note: Establishes the data-cluster hub that routes schema, profile scope, backup-repair, and imported external state questions.
  - id: local-data-page
    type: file
    path: .almanac/pages/local-data-model.md
    note: Establishes the lower-level schema and repair mechanics inside the broader data cluster.
  - id: profiles-page
    type: file
    path: .almanac/pages/profiles.md
    note: Establishes the active-profile and profile-scope concept that cuts across workouts, sync, backup repair, and entitlement state.
  - id: backup-repair-page
    type: file
    path: .almanac/pages/data-backup-and-repair.md
    note: Establishes the user-visible recovery layer for backup, restore, auto-backup, and startup repair behavior.
  - id: hosts-page
    type: file
    path: .almanac/pages/platform-hosts.md
    note: Establishes the Android and iOS host differences that cut across both major clusters.
  - id: theme-page
    type: file
    path: .almanac/pages/theme-mode.md
    note: Establishes shared theme preference ownership, three-state theme selection, and the host boundary for system-theme following.
status: active
verified: 2026-06-26
---
This page is the repo-wide router. [[project-phoenix]] explains rescue-app goals, supported hardware, and the local-first product contract, while [[app-architecture]] explains the shared Compose runtime, DI boundaries, and the main code entry points [@phoenix-page] [@architecture-page].

## Fast default route

For most coding tasks, read in this order:

1. [[app-architecture]] for runtime boundaries and entry points [@architecture-page]
2. One hub page: [[frontend]], [[workouts]], [[sync]], [[data]], or [[integrations]] [@frontend-page] [@workouts-page] [@sync-page] [@data-page] [@integrations-page]
3. One cross-cutting page only if the symptom already crosses boundaries: [[profiles]], [[data-backup-and-repair]], or [[platform-hosts]] [@profiles-page] [@backup-repair-page] [@hosts-page]

Read [[project-phoenix]] first only when the task depends on hardware support, per-cable machine assumptions, compatibility-preservation behavior, or why remote services stay optional [@phoenix-page].

## Top-level page roles

The top of the wiki has three different jobs. This page chooses what to read next, [[project-phoenix]] explains why the app exists and which hardware assumptions constrain changes, and [[app-architecture]] explains how the shared app is wired [@phoenix-page] [@architecture-page].

The next layer is not one uniform hub type. [[frontend]] is the structural UI-boundary hub for shared Compose ownership, route placement, and presentation-state questions; [[workouts]], [[sync]], and [[data]] are the main subsystem hubs; [[integrations]] is a bridge hub for one screen that immediately splits into health-store, local CSV, and provider-sync branches; and [[settings-surface]] is the main route-entry hub for one screen that launches several of those clusters without owning them [@frontend-page] [@workouts-page] [@sync-page] [@data-page] [@integrations-page] [@settings-page].

Leaf pages such as [[auth]], [[premium-entitlements]], [[routines-and-training-cycles]], [[local-data-model]], [[profiles]], and [[platform-hosts]] are faster only when the task already names that exact boundary [@auth-page] [@premium-page] [@routines-page] [@local-data-page] [@profiles-page] [@hosts-page].

## Choose one hub first

Use [[frontend]] when the issue is clearly about shared Compose ownership, route placement, Settings entry points, theme state, diagnostics UI, or other presentation-layer wiring before the feature boundary is known [@frontend-page] [@settings-page] [@theme-page] [@hosts-page].

Use [[settings-surface]] when the issue starts in the Settings tab but still spans more than one subsystem, especially for link-account entry, integrations entry, backup or restore actions, workout-preference edits, diagnostics shortcuts, or body-weight state that might actually belong to profiles or health integration. That page is a route hub, not a subsystem hub: it explains which downstream page actually owns the behavior after the shared Settings screen launches it [@settings-page].

Use [[workouts]] for BLE, live sessions, routines, diagnostics, safety cues, voice stop, assessments, or machine-behavior questions [@workouts-page].

Open [[routines-and-training-cycles]] directly when the symptom is already clearly in routine editors, superset structure, cycle day progression, or percentage-based programming inputs rather than in live session control [@routines-page].

Use [[sync]] for portal auth, entitlement, remote transport, Supabase-backed behavior, or provider-sync issues that are already clearly outside the local-only integrations split [@sync-page] [@auth-page] [@premium-page].

Use [[data]] for schema, migrations, profile scope, backup or restore, imported local files, or any symptom where state seems to have moved between workout and sync features [@data-page] [@local-data-page] [@backup-repair-page].

Use [[integrations]] when the symptom starts from the shared integrations screen or the first real split is still Health Connect or HealthKit versus local CSV versus provider sync [@integrations-page] [@health-page] [@provider-page] [@csv-page].

## Cross-cutting pages

Open [[profiles]] early when the same problem appears only after a profile switch or only affects one user's local rows [@profiles-page].

Open [[data-backup-and-repair]] early when the symptom starts after restore, auto-backup, or startup repair [@backup-repair-page].

Open [[platform-hosts]] early when Android and iOS disagree about the same feature, because the shared code path may still be correct while host wiring differs [@hosts-page].

Keep [[app-architecture]] nearby when the remaining question is which manager, route, or lifecycle boundary owns the change instead of which subsystem is failing [@architecture-page].

## Fast symptom routes

- Rescue-app purpose, supported hardware, local-first expectations, or compatibility-preservation branches: [[project-phoenix]]
- Shared UI structure, navigation, and manager ownership: [[app-architecture]]
- Shared Compose ownership, route placement, theme state, diagnostics UI, or presentation-state projection: [[frontend]]
- Shared Settings tab issue where the owning subsystem is still unclear: [[settings-surface]] [@settings-page]
- Login, logout, OAuth callback, secure token storage, or account linking before sync is eligible: [[auth]]
- Supabase project URL, anon-key injection, GoTrue redirect wiring, or Edge Function naming: [[supabase]]
- Integrations issue where the first split is still health-store versus local CSV versus provider sync: [[integrations]]
- Health Connect, HealthKit, body-weight import, workout export markers, or health-permission recovery with no portal state: [[health-platform-integration]] [@health-page]
- Provider connection, imported activities, routines, programs, measurements, cursors, or disconnect cleanup: [[external-provider-sync]] [@provider-page]
- Strong CSV export, Strong or Hevy import, or external-activity file ingestion: [[csv-workout-import-export]]
- Routine editor screens, supersets, training-cycle day order, or percentage-based programming setup before a session starts: [[routines-and-training-cycles]] [@routines-page]
- Schema changes, migration repairs, repository shape, or shared persistence contracts: [[local-data-model]]
- Data that changes after profile switch or profile deletion: [[profiles]]
- Backup, restore, auto-backup, or startup repair behavior: [[data-backup-and-repair]]
- Accessory loads, weighted vests, counterweights, or routine-default rack items: [[equipment-rack]]
- Badges, streaks, PR celebrations, or the RPG profile card: [[gamification]]
- Android or iOS host differences, secure storage, boot order, or foreground-service behavior: [[platform-hosts]]
- Theme preference persistence, System or Light or Dark selection, dynamic color, or platform theme-following behavior: [[theme-mode]] [@theme-page]

## Common read sequences

- New shared-UI feature where the owning feature boundary is still unclear: [[frontend]] -> [[app-architecture]] -> [[workouts]] or [[integrations]] [@frontend-page] [@architecture-page] [@workouts-page] [@integrations-page]
- Shared screen or route bug where the owning feature is still unclear: [[frontend]] -> [[app-architecture]] -> [[workouts]] or [[integrations]]
- Settings-tab issue that could still be auth, integration, backup, or workout-preference state: [[settings-surface]] -> [[auth]] or [[integrations]] or [[data-backup-and-repair]] or [[workout-safety-and-feedback]] [@settings-page]
- BLE bug or machine-behavior mismatch: [[project-phoenix]] -> [[workouts]] -> [[vitruvian-ble-protocol]]
- Diagnostics fault codes or crash snapshots: [[workouts]] -> [[machine-diagnostics]] -> [[vitruvian-ble-protocol]] [@diagnostics-page]
- Voice stop or cue playback mismatch: [[workouts]] -> [[workout-safety-and-feedback]] -> [[platform-hosts]] [@workout-safety-page] [@hosts-page]
- Routine editor or training-cycle bug before live execution starts: [[workouts]] -> [[routines-and-training-cycles]] -> [[profiles]] or [[strength-assessment-and-insights]] [@routines-page] [@profiles-page] [@assessment-page]
- 1RM assessment or Smart Insights issue: [[workouts]] -> [[strength-assessment-and-insights]] -> [[local-data-model]] [@assessment-page]
- Login callback or secure-storage issue: [[auth]] -> [[platform-hosts]] -> [[portal-sync-transport]] [@portal-page]
- Backend configuration or redirect-allowlist issue: [[supabase]] -> [[auth]] -> [[platform-hosts]]
- Sync or premium issue after login succeeds: [[sync]] -> [[premium-entitlements]] -> [[auth]] [@premium-page] [@auth-page]
- Integrations-screen issue where the transport branch is still unknown: [[integrations]] -> [[health-platform-integration]] or [[csv-workout-import-export]] or [[external-provider-sync]] [@integrations-page]
- Provider connect, import, or disconnect issue: [[integrations]] -> [[external-provider-sync]] -> [[sync]] if entitlement or transport state becomes the real gate [@integrations-page] [@sync-page]
- Backup or repair issue that later affects sync: [[data-backup-and-repair]] -> [[local-data-model]] -> [[sync]] [@backup-repair-page] [@sync-page]

## Aliases and routing rule

[[health-and-external-integrations]] is archived in favor of [[integrations]], and [[portal-sync-and-auth]] is archived in favor of [[portal-sync-transport]]. New links should target the active slugs directly [@integrations-page] [@portal-page].

Open [[data]] before drilling into [[workouts]] or [[sync]] when the symptom touches both clusters, because remote state and workout history converge on the same local persistence layer [@data-page].
