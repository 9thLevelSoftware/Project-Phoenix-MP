# Canonical Spec: Migration Strategy

## Purpose

Provide a repeatable approach for major technical migrations while preserving user-facing stability.

## Migration model

All major migrations should follow phased execution:

1. Discovery & baseline
2. Contract stabilization
3. Parallel implementation
4. Incremental cutover
5. Decommission & hardening

See `openspec/proposal-lifecycle.md` for required outputs per phase.

## Strategy requirements

### Parity first

- Define functional parity criteria before cutover.
- Track parity using automated checks where possible.

### Reversible slices

- Deliver migration in independently reversible increments.
- Keep rollback procedures current for each slice.

### Observability

- Add instrumentation to compare old and new paths.
- Monitor regressions in performance, reliability, and user-critical workflows.

### Documentation synchronization

- Proposal, canonical specs, and release notes must stay aligned.
- Any intentional drift must be documented and approved.

## Exit criteria

Migration is complete only when:
- Legacy path is removed.
- Operational metrics are at or better than baseline thresholds.
- Canonical specs and onboarding docs are updated.
