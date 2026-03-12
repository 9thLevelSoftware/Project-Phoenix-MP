# Canonical Spec: Release Strategy

## Purpose

Define release governance for architecture-heavy changes so rollout risk stays controlled.

## Release tracks

- **Internal validation**: Maintainer-only validation builds.
- **Beta rollout**: Limited user cohort for real-world verification.
- **General availability**: Full release once quality gates are met.

## Required release gates

1. Proposal status is `Approved` and references all impacted specs.
2. Migration phase checkpoint for current slice is complete.
3. Test matrix passes for affected platforms (Android/iOS/shared).
4. Rollback mechanism is documented and verified.
5. Release notes include user-visible impact and known limitations.

## Versioning guidance

- Breaking architecture changes should trigger a documented versioning decision.
- Contract-breaking SPI/module changes require explicit compatibility notes.

## Rollout policy

- Prefer staged rollouts for high-risk architectural updates.
- Define stop conditions (crash spikes, BLE instability, severe regressions).
- Promote rollout only after checkpoint metrics remain healthy.

## Post-release follow-up

- Track issues during stabilization window.
- Close proposal lifecycle with implementation references.
- Record lessons learned for future migrations.
