# Retrospective Log

Retrospective findings from completed phases and milestones.
Referenced by `/legion:plan` for continuous improvement.

## v0.9.0 Enhancement Sweep (Phases 37-44) — 2026-04-28

### Key Findings

**What Went Well:**
- 7/8 phases passed review first cycle (87.5% first-pass rate)
- Zero new regressions across 1,682 tests; 257 new tests added
- Dynamic review panels (3-4 reviewers) caught more issues than static 2-person pairing — Phase 39's 4-person panel caught 2 data-loss blockers
- Wave execution parallelism worked without file conflicts across all 8 phases
- engineering-senior-developer + testing-qa-verification-specialist pairing was consistent and effective for all build/test plans
- DWSMTestHarness and FakePreferencesManager became reliable shared test infrastructure
- Cross-repo portal fix (Phase 40 transforms.ts) executed cleanly

**What Didn't Work:**
- Phase 37 guard tests were tautologies — test agent generated always-pass tests without scanning source files (required 2nd review cycle)
- Phase 39 had 2 data-loss blockers: new DB column (groupId) not threaded through all repository write paths
- Pre-existing PortalPullPaginationTest failure carried through 6 phases as report noise, never fixed
- iOS .ogg-to-.caf sound conversion TODO persists across 3 phases with no owner
- PROJECT.md still describes v0.8.0 scope — stale context

### Action Items

| # | Action | Priority | Evidence |
|---|--------|----------|----------|
| 1 | Fix PortalPullPaginationTest — update test input for MAX_PARITY_IDS=10000 | High | Flagged in phases 39-44 |
| 2 | Fix portal WEIGHT_MULTIPLIER in Analytics.tsx, Dashboard.tsx, challenges.ts, profile.ts | High | Phase 40 critical double-multiplication |
| 3 | Convert iOS sound files .ogg to .caf | Medium | Phases 41, 43, 44 |
| 4 | Update PROJECT.md for current milestone | Medium | Stale v0.8.0 content |
| 5 | Require source-file scanning in guard test plans | Medium | Phase 37 tautology blocker |
| 6 | Add "thread new columns through ALL write paths" to migration plan checklist | Medium | Phase 39 data-loss blockers |
| 7 | Prefer 3+ reviewer panels for validation phases | Low | Phase 44 best coverage ratio |
| 8 | Add PersonalRecord.cableCount field for accurate PR weight display | Low | Phase 37 deferred |

### Metrics

- Plans: 21/21 (100%) | Review first-pass: 7/8 (87.5%) | Blockers: 3 (all resolved)
- Tests: +257 new, 1,682 total, 0 regressions | Commits: 40 | Agents: 5
- Primary build agent: engineering-senior-developer | Primary test agent: testing-qa-verification-specialist

### Patterns for Future Planning

**Keep:**
- Wave 1 = build, Wave 2 = tests/validation
- Dynamic review panels for multi-domain or validation phases
- **Reviewer panel sizing**: Use 3+ reviewer panels for validation and integration phases. Phase 44's 3-person panel gave best coverage-to-effort ratio. Phase 39's 4-person panel caught 2 data-loss blockers that smaller panels would have missed.
- SchemaManifest validator for migration safety
- Fix-in-cycle review approach (avoid full re-review when possible)
- DWSMTestHarness + FakePreferencesManager as shared test infrastructure

**Drop:**
- **Guard tests without source scanning**: Test agents MUST grep actual source files to understand implementation, not just assert interface contracts. Phase 37 produced tautology tests that passed regardless of correctness because the agent never read the source.
- Indefinite deferral of pre-existing test failures
- TODO comments without assigned owners or milestone targets

### Checklist Items for Migration Plans

When a plan adds new DB columns via SQLDelight migration:

1. [ ] Column added to CREATE TABLE or ALTER TABLE in .sq file
2. [ ] Column added to ALL insert queries (insertX, upsertX) in .sq file
3. [ ] Column added to domain model data class
4. [ ] Column added to repository mapping function (mapToX)
5. [ ] Column added to SyncDto if entity is synced
6. [ ] Column populated at all call sites (not just read paths)
7. [ ] SchemaManifest updated if project uses manifest validation

Evidence: Phase 39 had 2 data-loss BLOCKERS because insertRoutine and upsertRoutine were missing the new groupId parameter. Reads worked fine; writes silently dropped the value.

---
