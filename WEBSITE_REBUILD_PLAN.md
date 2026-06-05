# Project Phoenix — Website Rebuild Plan

**Status:** Implemented in this branch (`claude/sharp-johnson-NgcQG`)
**Scope:** Rebuild the GitHub Pages site in `docs/` (landing page + legal pages + social graphic)
**Guiding rule:** Every feature claim on the site is **grounded in an actual shipped GitHub release** (v0.1.0-beta1 → v0.9.1), *not* in the aspirational `.planning/` docs. Many planned "headline" features were built and then **deleted before shipping** (see v0.6.0 "The Great Purge"), so they are deliberately **excluded**.

---

## 1. Why the old site needed a rebuild

The previous `docs/index.html` was written around the v0.1–v0.3 era and never kept up. Concrete problems:

| Problem | Old site said | Reality (shipped) |
|---|---|---|
| Framing | "**Planned** features" / "Android beta · iOS beta" | Mature app at **v0.9.1**; Android on Google Play (open testing), iOS on TestFlight |
| Exercise count | "200+ exercises" | **572 exercises** (564 with video tutorials) |
| Privacy policy | "**No Cloud Sync · No Account · No External Servers**" | **Optional** Supabase cloud sync + Google/Apple OAuth + Phoenix Portal shipped in v0.6.0–v0.7.0 — the policy is now factually wrong |
| "What's New" | "v0.3.2" | Current is **v0.9.1** (May 2026) |
| Broken link | links to `terms-of-service.html` | File does not exist → 404 |
| Missing flagship features | — | VBT, Smart Insights, Safe Word voice-stop, multi-profile, Phoenix Portal, Health integrations, CSV import/export, training cycles — none mentioned |
| Feature graphic | placeholder comments left in | n/a |

## 2. Source of truth: what actually shipped

Grounded in release notes for tags: `v0.1.0-beta1`, `v0.2.x`, `v0.3.0`–`v0.3.4`, `v0.4.5`, `v0.5.0`, `v0.6.0`, `v0.6.5`, `v0.7.0`, `v0.9.0`, `v0.9.1`. (Note: there is **no v0.8.0** release/tag despite planning docs referencing one.)

### Confirmed shipped feature inventory

**Hardware & connection**
- BLE control of **Vitruvian V-Form Trainer** (VIT-200, `Vee_*`, 200 kg) and **Trainer+** (`VIT*`, 220 kg total / 110 kg per cable) over Nordic UART Service.
- Hardened, modular BLE stack (v0.4.5 decomposition; v0.6.x reliability), BLE diagnostics & fault decoding (v0.9.1), handle-state + **motion-start detection** (won't count reps until you actually move — v0.6.0).

**Training modes** — 6 structured + Just Lift (verified in `domain/model/Models.kt`)
- **Just Lift** (freestyle grab-and-go, optional auto weight, rest timer w/ +30s/pause/resume — v0.6.0)
- **Old School · Pump · TUT · TUT Beast · Eccentric Only**
- **Echo** — 4 levels (Hard / Harder / Hardest / Epic), 0–150% eccentric load, per-set Echo levels (v0.6.0)

**Real-time training**
- Live **position / velocity / load / power**; automatic machine-based rep counting + warm-up tracking; stall detection / auto-stop; workout HUD.
- **Velocity-Based Training (VBT)** — velocity zones, real-time feedback, auto-end on velocity drop, threshold model + strength assessment (v0.9.0).
- **"Safe Word" voice-stop** — say your phrase (e.g. "DAMN DEVIL STOP") to halt the machine; 3-rep calibration (v0.6.0). *Genuinely unique safety feature.*
- Audio + haptic rep cues; configurable weight increments (0.5/1/2.5/5 kg, etc. — v0.9.0).

**Exercises, routines & programming**
- **572-exercise library** with video tutorials, 6 major muscle groups (Arms, Back, Chest, Core, Legs, Shoulders), 9 equipment types, grip/sidedness metadata.
- Custom routines with **supersets** (4 colors, drag-and-drop, per-exercise rest times, block reordering — v0.3.0/v0.6.0/v0.9.0), **routine groups/folders** (v0.9.0).
- **% of PR** weight scaling (per-set — v0.3.0), **AMRAP** sets, variable warm-up sets (v0.6.0), bulk routine weight adjustment (v0.9.0).
- **Training Cycles** — multi-week periodized rolling programs (v0.3.0/v0.5.0); progression engine (progressive overload + periodization — v0.5.0).

**Tracking, PRs & analytics**
- Automatic **PR detection** w/ celebrations; **phase-specific** PRs (concentric/eccentric/combined — v0.6.0) and mode-specific PRs; **1RM** estimates (hybrid Brzycki ≤10 reps / Epley >10).
- Workout history + drill-down, **streaks** + calendar, **badges** (Bronze/Silver/Gold/Platinum).
- **Smart Insights & Smart Suggestions** (v0.9.1) — Snapshot/Trends/Diagnostics/Actions, weekly volume, time-of-day analysis, readiness; volume-trend charts, muscle balance, exercise variety; **Recent Activity replay** (v0.9.1); bodyweight volume integration.

**Cloud, profiles & the Portal**
- **Phoenix Portal** — web companion in public beta (v0.7.0).
- **Optional** bidirectional **cloud sync** via Supabase (v0.6.0, rebuilt v0.6.5: pagination, retry/backoff, token refresh, conflict resolution).
- **Google / Apple OAuth** sign-in (v0.7.0); **multi-profile** household support w/ isolation + move/copy routines (v0.6.5).

**Integrations & data portability**
- **Apple Health (HealthKit)** + **Health Connect** (v0.6.0/v0.6.5).
- **CSV import/export** in **Strong** + **Hevy** formats (v0.6.0); expanded external-integration sync + screens (v0.9.1); **backup/restore** (V2, schema-drift safe — v0.3.1/v0.9.0).

**Platform & polish**
- **Android + iOS** from one Kotlin Multiplatform / Compose Multiplatform codebase.
- **5 languages**: English, Dutch, German, Spanish, French (v0.6.0). Tablet/responsive UI (v0.3.0). **Launch Pad** home screen (v0.9.1). Accessibility (screen-reader semantics, iOS dynamic type).
- **Free — no subscriptions** (RevenueCat + paywalls removed in v0.6.0). **Local-first**; cloud is opt-in.

### Explicitly excluded (built then purged in v0.6.0 — never shipped to users)
CV/MediaPipe **form check**, **ghost racing**, **RPG attributes / character classes**, **LED biofeedback**, color-blind mode, **HUD preset customization**, BLE simulator, **Isokinetic** mode, leaderboards/challenges. *The domain engines for some of these still exist in `domain/premium/`, but only VBT and Smart Insights/Suggestions are surfaced to users — so only those are marketed.*

## 3. Design direction (creative license)

- **Theme:** "Rise from the ashes." Obsidian/charcoal background with molten-ember gradients; phoenix fire (orange `#ff7a3d` → gold `#f2c14e`) as primary, recovery teal/emerald (`#39d2a4`) as the "brought back to life" accent. Evolves the existing palette rather than discarding brand recognition.
- **Typography:** keep **Fraunces** (display serif), **Space Grotesk** (body), **IBM Plex Mono** (technical/stats) — already on-brand, loaded from Google Fonts.
- **Tech:** single self-contained `index.html` with embedded CSS + a little vanilla JS (sticky nav, scroll-reveal, FAQ accordion, ember particles). **No build step** — stays GitHub-Pages friendly from `docs/`. Honors `prefers-reduced-motion`, semantic HTML, alt text, good contrast.

### Page structure
1. Sticky nav (logo + section anchors + Get-the-app / GitHub CTAs)
2. Hero — "Rise from the ashes", real CTAs (Google Play, TestFlight), trust chips (Free · Android & iOS · v0.9.1 · No ads)
3. Rescue story + headline stats (572 exercises · 7 ways to train · 2 machines · 5 languages · $0)
4. Feature sections (grouped exactly as the shipped inventory above)
5. Workout-modes showcase (6 + Just Lift)
6. Phoenix Portal (web companion / optional cloud sync)
7. Supported hardware table
8. Download / get started (Android + iOS, install guides)
9. "Your data, your way" privacy/honesty section (local-first + optional sync — corrects the old contradiction)
10. Support the project (Ko-fi / BMC)
11. For developers / tech stack / open beta
12. FAQ
13. Footer (independent-project disclaimer, privacy, terms, GitHub, DeepWiki)

## 4. Files changed
- `docs/index.html` — full rebuild (overwrite)
- `docs/privacy-policy.html` — corrected to describe **optional** cloud sync / account (was factually wrong). ⚠️ *Legal text — owner should give it a final read.*
- `docs/terms-of-service.html` — **new**, fixes the broken link; includes the safety warnings & liability disclaimers the site references. ⚠️ *Template — owner should review with legal eye.*
- `docs/feature-graphic.html` — refreshed social/feature graphic (placeholder comments removed)
- `WEBSITE_REBUILD_PLAN.md` — this document

## 5. Follow-ups for the owner
- Review the two legal pages (privacy + terms) — they're now accurate/complete but should get a human/legal pass.
- Confirm the Google Play package + TestFlight links are current (`com.devil.phoenixproject` / `TFw1m89R`).
- Optional: add real in-app screenshots / a short demo clip to the hero and Portal sections (placeholders are structured and ready).
