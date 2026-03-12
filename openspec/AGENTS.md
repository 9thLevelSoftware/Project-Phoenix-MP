# OpenSpec Authoring Guide

This file is the authoritative workflow for planning and proposal-driven changes in this repository.

## When to use OpenSpec

Use OpenSpec whenever a request involves:
- architecture changes (module boundaries, ownership shifts, layering)
- framework/platform migrations (Compose, KMP, DI, persistence, BLE stack, etc.)
- plugin/system extension points or API contracts
- release governance, compatibility, or rollout policy
- any request using words like **proposal**, **spec**, **plan**, or **migration**

## OpenSpec directory layout

- `openspec/AGENTS.md` — this workflow and conventions
- `openspec/proposal-lifecycle.md` — proposal lifecycle with migration phases
- `openspec/specs/*.md` — canonical architecture and delivery specs
- `openspec/proposals/` — proposal documents under active review

## Proposal workflow

1. **Select canonical specs first**
   - Read all relevant files in `openspec/specs/`.
   - Identify the affected contracts and constraints.
2. **Write or update a proposal**
   - Create `openspec/proposals/<yyyy-mm-dd>-<slug>.md`.
   - Reference impacted canonical specs and sections.
3. **Define phased execution**
   - Explicitly call out phase boundaries, rollout method, and rollback strategy.
4. **Review & decision**
   - Record status as `Draft`, `Review`, `Approved`, `Implemented`, or `Superseded`.
5. **Apply change**
   - Implement only approved scope.
   - Update canonical specs if behavior/contracts changed.
6. **Close out**
   - Mark proposal final status and link implementation PR(s).

## Proposal template

Use this template for new proposals:

```md
# <Title>
- Status: Draft
- Owner: <name>
- Date: <YYYY-MM-DD>
- Scope: <high-level scope>
- Related Specs:
  - openspec/specs/<file>.md#<section>

## Motivation

## Goals

## Non-Goals

## Current State

## Proposed Design

## Migration / Rollout Plan
- Phase 0:
- Phase 1:
- Phase 2:

## Risks and Mitigations

## Backward Compatibility

## Validation

## Rollback Plan

## Exit Criteria
```

## Canonical specs

- `openspec/specs/module-split.md`
- `openspec/specs/plugin-spi.md`
- `openspec/specs/migration-strategy.md`
- `openspec/specs/release-strategy.md`

Treat these as baseline contracts. If implementation deviates, update spec and proposal together.
