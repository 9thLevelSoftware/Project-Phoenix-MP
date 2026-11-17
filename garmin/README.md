# Phoenix Remote — Garmin watch app (issue #1391, packet B7, Phase 1)

Companion **watch app** for Project Phoenix workout control. Press physical
buttons on the watch while your hands are on the bar; the phone side (packets
B2–B5) does the real work. This tree is Monkey C for Connect IQ plus its
standalone GitHub Actions workflow.

**Phase 1 scope — deliberately minimal:**

- `START_NEXT`, `STOP_SET`, and *confirmed* `CANCEL_EXERCISE` commands over the
  frozen v1 message protocol (below).
- Mandatory text feedback on every press: **pending**, **applied**, **stale**,
  **UNKNOWN_RESULT**, and the **REJECTED reason code** verbatim.
- Display shows only the minimal current target + readiness
  (`displayLabel` / `readiness` from `STATE`). The full exercise/set/rest
  dashboard is **Phase 2 and forbidden here**.
- Haptic (buzz) **only** after `RESULT` stage=`APPLIED` — never on `ACCEPTED`
  or on any rejection.

## Runtime requirements

- **The watch app must be OPEN during the workout.** Transport is Connect IQ
  Mobile SDK *foreground* messaging — there is no background delivery and no
  REST anywhere.
- **Garmin Connect Mobile must be installed and paired on the phone**, and the
  Phoenix phone app must be running to exchange `HELLO` / `STATE` / `COMMAND` /
  `RESULT` messages.

## Supported products (PROVISIONAL)

> The supported-models list is **provisional pending simulator verification**.
> Store submission is **release-time only** — nothing here is published yet.

| Product | Connect IQ id | Layout |
|---|---|---|
| Forerunner 255 | `fr255` | five-button |
| Forerunner 265 | `fr265` | five-button (acceptable per signoff correction 3) |
| Forerunner 965 | `fr965` | five-button |
| fēnix 7 | `fenix7` | five-button |

Touch-only or non-matching products are **excluded entirely**, not
half-supported. The allowlist is encoded as product entries in
`garmin/manifest.xml`; `garmin/tests/run-static-checks.sh` enforces that the
manifest, this list, and the CI matrix stay in sync.

## Button mapping (five-button products, signoff correction 3)

| Button | Action |
|---|---|
| top-right `START/ENTER` | Start/Next; **second** press confirms Cancel |
| bottom-right `BACK/LAP` | urgent `STOP_SET` — **always Stop, never confirms** |
| bottom-left `DOWN` | open Cancel confirmation |
| middle-left `UP` | dismiss confirmation |
| top-left `LIGHT` | **not intercepted** (system behaviour) |

Press/release is consumed exactly once (key-repeat and hold events are
suppressed). There is **no long-hold destructive action anywhere**.

Cancel confirmation names the exercise from `STATE.confirmationLabel`:

> **Cancel Bench Press?**
> Skip only Bench Press, not the whole routine. Previously saved sets stay
> saved.
> *Ergonomic remote, NOT an emergency stop.*

## Frozen v1 wire contract (Monkey C Dictionary, string keys)

Every message carries `v: 1` (int) and `type` (string).

- `HELLO` (watch→phone) = `{watchInstanceId:string, supportedVersions:[1]}` —
  handshake only, **never Arm**. Sent on open/initial entry/reconnect and
  before any gesture when no fresh grant exists. Never periodic.
- `STATE` (phone→watch) = `{controlEpoch, revision, contextId,
  allowedActions:array<string>, readiness, phoneForeground, foregroundRequired,
  backgroundStopSupported, displayLabel, actionLabel}` +
  optional `{actionGrant, grantTtlMs, confirmationToken, confirmationTtlMs,
  confirmationLabel}`.
- `COMMAND` (watch→phone) = `{controlEpoch, commandId, contextId,
  expectedRevision, action, actionGrant}` — `CANCEL_EXERCISE` **additionally
  requires `confirmationToken`**.
- `RESULT` (phone→watch) = `{commandId, controlEpoch, contextId,
  stage: ACCEPTED|APPLIED|REJECTED}` + optional `reason` (**required** for
  `REJECTED`).

Actions: `START_NEXT` | `STOP_SET` | `CANCEL_EXERCISE`.
Readiness: `DISABLED | UNAVAILABLE | SETUP | CONNECTED | ARMED_READY | ACTIVE |
RESTING | SUMMARY | PHONE_INPUT_REQUIRED | BUSY | ERROR | RECOVERY_REQUIRED`.
REJECTED reasons: `STALE_STATE | NOT_READY | DISCONNECTED |
UNSUPPORTED_VERSION | DISABLED | BUSY | RECOVERY_REQUIRED | SAVE_FAILED |
UNKNOWN_RESULT | INVALID_MESSAGE | PAYLOAD_TOO_LARGE | UNAUTHORIZED_DEVICE |
COMMAND_ID_CONFLICT | CONFIRMATION_EXPIRED | EPOCH_CAP_REACHED`.

Message limits (the watch validates all of these **before sending**):
≤ 2048 encoded bytes/message, ≤ 24 dictionary keys, ≤ 2 container levels
(root dict + scalar array), ≤ 3 array elements, ids/grants/tokens ≤ 64 ASCII
bytes, labels ≤ 80 UTF-8 bytes, enum strings ≤ 32 bytes.

Timing: grant TTL 3000 ms, confirmation TTL 10000 ms, result timeout 5000 ms
(displayed TTLs are advisory only; the phone is authoritative and answers
`STALE_STATE` / `CONFIRMATION_EXPIRED` when the watch oversteps).

Transport APIs (Connect IQ Mobile SDK foreground messaging):

```monkeyc
// phone -> watch
Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
// watch -> phone
Communications.transmit(messageDict, {}, sendListener);
```

## Watch-side behaviour (contract)

- Requires a **fresh `STATE` before dispatching a `COMMAND`**; a `COMMAND`
  always rides the phone-issued 3 s `actionGrant` in the same `contextId`.
- Retains **at most one gesture**, only inside that grant window; discards it
  on disconnect / app background / epoch or context invalidation.
- A `STALE_STATE` rejection requires a **new press** — the watch never
  resubmits the old command id and never auto-retries with a new one. On the
  5 s result timeout it shows `UNKNOWN_RESULT` and waits for a fresh press.
- `watchInstanceId` is **untrusted metadata**; dedupe/replay protection is
  phone-side.

## Building locally

Prerequisites: [Connect IQ SDK](https://developer.garmin.com/connect-iq/sdk)
(9.2.0 recommended to match CI) with device definitions for the allowlist, a
developer key (`openssl pkcs8 -topk8 -inform PEM -outform DER -in key.pem
-out key.der -nocrypt`), and a JRE 17+.

Exact `monkeyc` build invocation (run from the repo root):

```sh
monkeyc -f garmin/monkey.jungle -d fr255 -o build/phoenix-app-fr255.prg -y /path/to/developer_key.der
```

Exact `monkeydo` simulator invocation (start the simulator first with
`connectiq`; `-t` runs the Run No Evil protocol/input checks):

```sh
monkeyc -f garmin/monkey.jungle -d fr255 -o build/phoenix-tests-fr255.prg -y /path/to/developer_key.der -t
connectiq                                                        # launch simulator
monkeydo build/phoenix-tests-fr255.prg fr255 -t                  # run checks
```

On CI the same steps run headlessly via
`bash garmin/tests/run-simulator-tests.sh <tests.prg> <device>` (simulator
under Xvfb + D-Bus; adapted from DuckSoft/setup-connectiq-actions, MIT).

Static sanity checks (any machine with bash + xmllint):

```sh
bash garmin/tests/run-static-checks.sh
```

## CI (.github/workflows/garmin-ci.yml)

- Pins the Connect IQ SDK version (`CIQ_SDK_VERSION`) and installs the pinned
  device-definition archive — never `latest`.
- Builds the watch app per allowlisted product with `monkeyc`, then builds and
  runs the simulator protocol/input checks (`monkeyc -t` + `monkeydo -t`) from
  `garmin/tests/`: button mapping, confirm/dismiss, one action per press with
  no repeats, pending/applied/stale/unknown text, HELLO/fresh-STATE without
  flooding.
- CI artifacts are unsigned/ephemeral-signed. **Signing keys live in GitHub
  secrets (`CONNECTIQ_DEVELOPER_KEY_BASE64`) and are never committed.**
- There is **no Gradle Garmin task** — the watch app is not wired into the
  Android/iOS build at all.

## Notes for the phone side

- `commandId` format generated by the watch: `<watchInstanceId>-<n>` where
  `n` is a per-run monotonic counter starting at 1, and `watchInstanceId` is
  `w<epochSeconds>-<msSinceBoot>` (untrusted metadata, ≤ 64 ASCII bytes).
- `expectedRevision` echoes `STATE.revision` from the most recent `STATE`.
- `HELLO` retry behaviour: sent once on open/initial entry/reconnect, and
  before any gesture when no fresh grant exists (throttled to ≥ 500 ms
  between HELLOs). There is no periodic heartbeat — if the phone wants a
  re-handshake it must provoke a gesture or reconnect.
- On `RESULT` stage=`APPLIED` the watch buzzes once; `ACCEPTED` and rejections
  are text-only.

## Tree

```
garmin/
  manifest.xml              product allowlist + permissions
  monkey.jungle             build definition
  source/PhoenixApp.mc      app shell, transport glue, haptics
  source/PhoenixView.mc     minimal target + readiness + feedback / confirm UI
  source/PhoenixDelegate.mc five-button input (one action per press)
  source/PhoenixProtocol.mc frozen v1 wire contract + control machine
  resources/strings/        all user-facing copy
  tests/                    Run No Evil checks + CI scripts
```
