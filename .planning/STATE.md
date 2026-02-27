# Session State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-27)

**Core value:** Users can connect to their Vitruvian trainer and execute workouts with accurate rep counting, weight control, and progress tracking — reliably, on both platforms.
**Current focus:** Defining requirements for v0.5.1

## Position

**Milestone:** v0.5.1 Board Polish & Premium UI
**Current phase:** Not started (defining requirements)
**Status:** Defining requirements
**Last activity:** 2026-02-27 — Milestone v0.5.1 started

## Decisions

(Carried from v0.5.0)
- Dependencies in androidMain not commonMain - MediaPipe and CameraX are Android-only
- android:required=false for camera features - Form Check is optional premium feature
- CPU delegate only for v1 - GPU delegate has documented crashes on some devices
- 100ms minimum inference interval (~10 FPS max) balances form check accuracy with BLE pipeline protection
- Bundled lite model in APK assets - 5.78MB is acceptable for v1
- 160x120dp PiP size - small enough to not block workout metrics
- iOS stub provides no-op actual - Form Check deferred to v0.6.0+

## Accumulated Context

- Board of Directors review (2026-02-27): 5-0 APPROVED WITH REVIEW, 9 conditions
- SmartSuggestions classifyTimeWindow() uses UTC not local time — affects all non-UTC users
- android:allowBackup=true exposes DB to cloud backup extraction — needs exclusion rules
- HUD has 6+ simultaneous data streams — needs user-configurable page visibility before adding ghost racing
- Velocity zones, balance bar, readiness card use color-only encoding — WCAG AA failure for ~8% of male users
- versionName still reads 0.4.0 in androidApp/build.gradle.kts despite v0.5.0 content
- Phase 17 RPG attributes may require schema migration 17 (gamification singleton row insufficient)
- FeatureGate.Feature enum missing CV_FORM_CHECK — Phase 16 must add it

## Session Log

- 2026-02-27: v0.5.0 completed (Phases 13-15), Phases 16-17 rolled forward to v0.5.1
- 2026-02-27: Milestone v0.5.1 started — Board Polish & Premium UI
