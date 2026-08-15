# Task 4 report: deterministic drop-set offers

## Scope

Implemented the Task 4 models, pure candidate resolver, pure eligibility policy, disabled production DI binding, and immutable completion enrichment from base `04b70f23f385c8ab773ed2b2c27f80fcfde3a144`.

The root instruction references `openspec/AGENTS.md`, but that file is absent. The committed PR2 plan, approved design, retry index, and Task 4 brief were used as authority. The referenced agent persona contract was also absent at its configured path.

No detector, threshold, timer, status parser, rep-cancellation, BLE command/packet, teardown, rest-navigation, routine-configuration, or UI behavior was changed. The production feature gate remains bound to `DisabledDropSetFeatureGate`, and no production caller creates an offer.

## TDD evidence

### RED

Tests were added before production edits and run with:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests com.devil.phoenixproject.domain.usecase.DropSetCandidateResolverTest --tests com.devil.phoenixproject.domain.usecase.DropSetEligibilityPolicyTest --tests com.devil.phoenixproject.presentation.manager.SetExecutionCompletionContractTest --console=plain
```

Result: expected feature-missing compile RED in `:shared:compileAndroidHostTest`. The unresolved symbols were the new `DropPercentage`, candidate resolution/reason types, eligibility types/policy/request, `RoutineExecutionIdentity`, and enriched `SetExecutionCompletion` arguments. Production was untouched at this boundary.

### GREEN progression

- Focused candidate, eligibility, and completion contract gate: 19 tests, 0 failures.
- Completion lifecycle and production DI gate: 5 tests, 0 failures. The first attempted lifecycle compile exposed only test-fixture references to unavailable public flows; the fixture was corrected to the existing coordinator seam without changing production behavior.
- Affected Task 1/2/3/4 model, resolver, completion, lifecycle, persistence, SQL, and routine-flow gate: 222 tests, 0 failures, 0 errors, 0 skipped.
- Full Android host suite: 3,215 tests across 297 suites, 0 failures, 0 errors, 0 skipped.
- Final focused completion/lifecycle/guard rerun after bodyweight self-review hardening: 47 tests, 0 failures, 0 errors, 0 skipped.

## Source decisions

- Candidate resolution validates finite positive configured start, programmed base, and minimum in that order; then applies machine-increment rounding, floor rejection, command-shape validation, and strict-lower rejection in the brief's numbered priority. It copies only candidate weight into the immutable command template and returns the un-clamped candidate/base multiplier.
- Eligibility checks the injected feature gate first. With a test-enabled gate it requires terminal `STALL_FAILURE`, enabled valid configuration, immutable completion mode/type/flags, drop budget, full live routine identity, optional captured planned-set identity, and at least one valid candidate. Candidate order is stable 10/20/30.
- One immutable `SetExecutionActivationFacts` value captures completion facts at activation. It uses the matching persisted planned type when present; otherwise it derives semantic set type from the planned routine set, never timed transport AMRAP. Programmed base is resolved at multiplier 1 with no manual adjustment. Configured start is captured before rack/counterweight conversion.
- Production `SetExecutionCompletion` construction funnels through the activation-facts builder. The bodyweight gate retains the original immutable pending completion and lease; confirmation changes only `actualReps` via `copy` immediately before the first completion claim. A hook-based regression proves there was no earlier claim and that the first claimed value contains confirmed reps.
- Completion eligibility never reads detector samples. Post-activation coordinator parameter mutations cannot alter captured completion facts.
- Production DI resolves the policy with `DisabledDropSetFeatureGate`; the regression asserts `FEATURE_GATED` even for otherwise eligible inputs.

## Behavior-sensitive coverage

- Exact 10/20/30 math, configured-start source, half-kilo/tie rounding, exact/below floor, equal-after-rounding, non-finite/non-positive inputs, hardware bounds including 110 kg, invalid reps/progression, manual-adjustment normalization, consecutive `50 -> 40 -> 32` multiplier `0.64`, and source immutability.
- One negative eligibility case per reason, gate short-circuit, all identity dimensions, asymmetric optional planned-set matching, all-invalid/one-valid candidates, drop counts 0/1/2, semantic AMRAP preservation, timed cable rejection, and absence of raw detector inputs.
- Completion constructor invariants, planned-type fidelity, configured-start versus programmed-base capture, exclusion of rack/counterweight adjustment, post-capture mutation resistance, bodyweight final reps, and first-claim semantics.
- Existing Task 3 persistence and SQL regressions remained in the affected gate.

## Verification record

| Check | Result |
|---|---|
| Focused Task 4 tests | 19 passed |
| Lifecycle and DI tests | 5 passed |
| Affected regression gate | 222 passed |
| `:shared:testAndroidHostTest --continue` | 3,215 passed |
| Final completion/lifecycle/guard gate | 47 passed |
| `:shared:compileKotlinIosArm64` | Passed |
| `git diff --check` | Passed |
| Exact-diff scope searches | No detector/BLE/rest/config/UI additions or files |

`compileKotlinMetadata` was skipped by Gradle. `compileTestKotlinIosArm64` remains blocked by an unchanged Task 1 test name in `RoutineSetWeightResolverTest.kt:17` containing `,`, which Kotlin/Native rejects as an illegal test-name character. Android main and Android host test compilation pass.

Repository-wide `spotlessCheck` remains blocked by the unchanged `shared/build.gradle.kts` line-ending violation, and repository-wide `spotlessKotlinCheck` reports more than 218 pre-existing unrelated files. No formatting apply command was run because it would mutate files outside Task 4; the exact Task 4 diff passes `git diff --check`.

## Self-review

- Audited every production `SetExecutionCompletion` construction and retention path. Direct production construction exists only in `SetExecutionActivationFacts.complete`; delayed bodyweight state retains an immutable value and finalizes only confirmed reps.
- Audited bodyweight claim ordering: the guard has no claimed completion immediately before confirmation's first claim, and the claimed completion is the final immutable value.
- Audited mutable reads: eligibility consumes only the completion/request values; completion facts are captured at activation. The manual fallback is identity-null and therefore fails eligibility closed.
- Audited feature rollout: production DI is disabled and no enabled binding exists.
- Audited candidate diagnostic precedence against the brief's six numbered checks.
- Audited scope with path and added-line searches: no detector status, BLE packet/command, rest transition/navigation, routine drop configuration, UI, or `SetType.DROP_SET` behavior was added.
- Tests assert externally meaningful values and include mutation-sensitive source-template, post-capture coordinator, planned-type, identity, gate-priority, and first-claim cases.

No unresolved Task 4 behavior issue remains. The iOS test-name and repository formatting failures above are pre-existing and unchanged.
