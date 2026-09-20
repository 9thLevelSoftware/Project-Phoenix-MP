# Reliability implementation and release gates

## Scope

The mobile changes are in `codex/reliability-mobile`, based on `a2dd2890`; the companion portal changes are in `codex/reliability-portal`, based on `97b2646`. The reviewed mobile baseline `a33f8198` and subsequent main cleanup are preserved. Brownfield work is excluded. No production deployment or release publication has occurred.

The implementation covers non-destructive parent merges, rack transitions and durable safety handling, profile baselines, startup and explicit ownership recovery, complete workout snapshots and retained deletion records, cycle edit clocks and conflict drafts, backup v6, authentication/export corrections, and immutable-tag release rebuilds.

## Deployment order

1. Validate and deploy the additive portal migration `20260920120000_sync_reliability_contract.sql`, followed by both mobile sync Edge Functions and portal callers. The migration and functions must be released together before the dependent mobile client.
2. Verify the deployed portal contract with controlled accounts: component-safe writes, permanent workout deletion, exact acknowledgements, ownership transfer and event replay, and cycle rejection/nullable-field behavior. Local PostgreSQL evidence does not claim a production deployment check.
3. Complete the macOS and trainer gates below, then publish the mobile build from the validated commit. Use the immutable SHA selected by the release workflow for tests and every selected platform build.
4. Check pending-operation age and failures during rollout. Missing acknowledgements leave operations pending; they must not be treated as success or rebound to another account.

## Compatibility and rollback

- Migrations 48–51 are additive and keep the canonical SQL, schema manifest, and resilient fallback aligned. Fallback migration 47 is backfilled. The resulting database schema version is 52.
- Backups export version 6 and accept versions 1–5. Account-bound deletion/recovery state keeps its original account owner. Baselines remain local-only.
- Older clients may omit sibling workout components or new nullable-field presence flags. The portal preserves omitted siblings and legacy omitted cycle values. A new client sends explicit presence for authoritative nullable values.
- Retain permanent workout/component tombstones and ownership records during rollback. Do not drop the new tables or reuse deleted entity IDs. Restoring deleted content requires new IDs.
- Retain full-history reconciliation. A wall-clock cursor is not a safe substitute for a separately designed incremental protocol.
- Recovery does not merge source machine/rack preferences into the destination. The portal retains omitted profile registrations while they still own preference documents because legacy `allProfiles` omission cannot distinguish deliberate cloud profile deletion from recovery. Local profile deletion still removes local preferences. Explicit removal of those retained cloud registrations needs a future authenticated profile-delete contract.
- These changes cannot reconstruct telemetry already lost before the fix; recovery requires a usable backup.

## Required external gates

These checks require environments unavailable in the Windows implementation workspace and remain release gates:

- On macOS, build the release iOS framework and app, and run native startup/database tests using the production driver. Confirm foreign-key enforcement after migration and reopening; do not change the driver flag merely to match a test fixture.
- On V-Form and Trainer+, verify manual and autoplay transitions, edited rack loads/counterweights, same-exercise and next-exercise transitions, idle/armed disconnect and recovery, and repeated timed warmups with the next exercise initially stationary.
- The suspected warmup stale-packet behavior remains unconfirmed. No speculative telemetry algorithm change is included.

## Operational checks

Track counts, age, failure categories, and commit/version identifiers for pending ownership/deletion operations, rejected stale writes, migration/repair failures, partial restores, and safety recovery failures. Do not include email addresses, tokens, backup contents, or freeform workout/profile text in telemetry. Investigate a growing pending queue before expanding rollout; do not clear it to hide errors.

## Verification evidence

Implementation and local verification are complete, including independent correctness and Ponytail reviews. The table below summarizes the final results. Detailed local execution logs are retained separately and are not included in the repository; checkpoint and run numbers identify those verification runs.

| Gate | Final evidence |
| --- | --- |
| Complete shared host suite | Checkpoint 24: 3,987 tests, 0 failures/errors, 2 skipped |
| Android unit suite | Checkpoint 24: 52 tests, 0 failures/errors/skips |
| Schema manifest and migration parity | 448 columns across 57 tables; parity and resilient legacy-upgrade regressions passed |
| Portal PostgreSQL contract | Run 13 passed against PostgreSQL 17, including deletion, ownership, transaction rollback, and cycle conflicts |
| Portal concurrent clients | Run 4 passed with fixture cleanup |
| Portal Edge Functions | 262 passed, 4 ignored; both changed sync entrypoints type-checked |
| Portal sync suite | 337 passed, 19 skipped |
| Portal unit suite | 1,629 passed, 19 skipped |
| Portal browser suite | 64 passed, 1 skipped |
| Portal typecheck/build/lint | Passed; existing lint warnings remain |
| Release helper/workflow/icon checks | 4 helper, 7 workflow, and 4 icon tests passed |
| Diff hygiene | Mobile and portal changes pass `git diff --check` |

The closing backup/sync integration regression first failed with an empty pending-workout snapshot after restoring missing children into an acknowledged parent. The fix marks new metrics, completed sets, and notes dirty in the same transaction as their insertion. Checkpoint 24 verifies complete pending payloads, rejection of stale pre-restore acknowledgements, and duplicate-restore generation stability.

The repository-wide portal Edge Function checker has an unrelated existing Deno `BufferSource` overload error at `supabase/functions/_shared/oauthTokenCrypto.ts:91`; both changed mobile sync entrypoints type-check and their Edge Function tests pass.
