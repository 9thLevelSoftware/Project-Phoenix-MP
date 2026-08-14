# Issue #687 physical trainer validation

## Validation status

- [ ] **NOT EXECUTED — awaiting physical Android trainer validation.**
- Reason: no connected physical trainer, Android test device, trainer firmware identifier, or trainer serial was made available for this task.
- Automated host and build verification is recorded separately in the Task 9 report; it is not a substitute for the physical runs below.

## Build and device identifiers

| Identifier | Value |
| --- | --- |
| Source base before Task 9 | `047090bc78cccd721cccdec7906c1ac15488bf9b` |
| Task 9 implementation commit | `2623302f003d5948069ceb65abd9d419e3dcb33c` |
| Android application ID | `com.devil.phoenixproject` |
| Android app version | `0.9.6-DEBUG` (`versionCode` fallback `5`; no injected CI version) |
| Android device/model | NOT AVAILABLE |
| Android OS/build | NOT AVAILABLE |
| Trainer model | NOT AVAILABLE |
| Trainer firmware | NOT AVAILABLE |
| Trainer serial/identifier | NOT AVAILABLE |
| Tester | NOT ASSIGNED |
| Physical run date | NOT EXECUTED |

## Required procedure for every iteration

1. Start a routine cable set as profile A.
2. End Workout and immediately switch to profile B.
3. Reopen a routine and attempt Start while RESET is active.
4. Confirm Start remains disabled/rejected and RESET/config do not overlap.
5. Start after the teardown gate reaches `Ready`.
6. Confirm B remains at `0/N` until an actual B rep, data remains attributed to profile B, and exactly one session/completed set is persisted.

An iteration fails if it jumps to summary/rest, accepts a start during teardown, overlaps RESET and config, duplicates persistence, attributes A data to profile B, or requires an app restart after successful recovery.

## 50-run checklist

`—` means the observation was not made because the physical run was not executed.

| Run | Complete | Start disabled during teardown | No RESET/config overlap | B stayed 0/N until real rep | Correct profile | One session/set | Result / notes |
| ---: | :---: | :---: | :---: | :---: | :---: | :---: | --- |
| 01 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 02 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 03 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 04 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 05 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 06 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 07 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 08 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 09 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 10 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 11 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 12 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 13 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 14 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 15 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 16 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 17 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 18 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 19 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 20 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 21 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 22 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 23 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 24 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 25 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 26 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 27 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 28 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 29 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 30 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 31 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 32 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 33 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 34 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 35 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 36 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 37 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 38 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 39 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 40 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 41 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 42 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 43 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 44 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 45 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 46 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 47 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 48 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 49 | [ ] | — | — | — | — | — | NOT EXECUTED |
| 50 | [ ] | — | — | — | — | — | NOT EXECUTED |

## Recovery checks

- [ ] Force one RESET failure; verify persistent recovery UI and that start remains unavailable. **NOT EXECUTED**
- [ ] Select Retry; verify one new RESET attempt and that start reopens only after success. **NOT EXECUTED**
- [ ] Force one RESET timeout; verify persistent recovery UI and no polling/config overlap. **NOT EXECUTED**
- [ ] Disconnect and reconnect; verify the recovery RESET succeeds before start reopens. **NOT EXECUTED**
- [ ] Confirm no successful recovery requires an app restart. **NOT EXECUTED**

Physical validation remains an open release item. Do not mark this document complete until all 50 rows and every recovery check contain real device observations and identifiers.
