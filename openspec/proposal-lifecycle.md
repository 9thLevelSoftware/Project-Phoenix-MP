# Proposal Lifecycle for Major Architecture Changes

This lifecycle governs major architectural changes, especially framework migrations.

## Status model

- **Draft**: Problem and intended direction are documented.
- **Review**: Stakeholders are validating feasibility and risks.
- **Approved**: Scope and migration phases are accepted for implementation.
- **Implemented**: Code changes landed and validated.
- **Superseded**: Replaced by a newer proposal.

## Required phase model (Template Framework Migration)

Every major framework migration must use these phases unless explicitly justified.

### Phase 0 — Discovery & Baseline

- Inventory impacted modules, build tooling, test suites, and release surfaces.
- Define measurable baseline metrics (build time, startup, crash rate, parity checklists).
- Document blockers and unknowns.

**Deliverables**
- Baseline report
- Compatibility matrix
- Risk register

### Phase 1 — Contract Stabilization

- Freeze or version current public/internal contracts that consumers depend on.
- Introduce adapter boundaries to isolate framework-specific code.
- Add conformance tests to lock behavior before migration.

**Deliverables**
- Contract definitions and ownership
- Adapter interfaces
- Conformance test suite

### Phase 2 — Parallel Implementation

- Implement new framework path behind feature flags or build variants.
- Run dual-path validation where old/new implementations can be compared.
- Track parity deltas and resolve critical gaps.

**Deliverables**
- New framework implementation (non-default)
- Feature-flag controls
- Parity and regression dashboard

### Phase 3 — Incremental Cutover

- Migrate modules in small, reversible slices.
- Increase traffic/adoption progressively (internal, beta, staged rollout).
- Keep rollback route active for each slice.

**Deliverables**
- Cutover schedule
- Rollout checkpoints and go/no-go gates
- Active rollback playbook

### Phase 4 — Decommission & Hardening

- Remove legacy paths and dead adapters.
- Consolidate docs, specs, CI/CD checks, and release notes.
- Perform post-migration retrospective.

**Deliverables**
- Legacy code removal PRs
- Updated canonical specs
- Retrospective with follow-up actions

## Governance gates

A proposal cannot advance to the next status unless:

1. Phase exit criteria are met and recorded.
2. Release strategy impact is captured.
3. Rollback procedures are validated in at least one rehearsal.
4. Canonical specs remain consistent with implementation intent.

## Minimal artifacts checklist

- Proposal document in `openspec/proposals/`
- Architecture diagram or module ownership table
- Test/validation plan
- Rollout and rollback procedures
- Release communication notes
