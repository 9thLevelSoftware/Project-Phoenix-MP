# Integration Guide

## Third-Party Vendor Minimum Safety Requirements

Any third-party vendor integrating machine control MUST implement and honor a safety policy equivalent to `SafetyPolicy`.

### Required policy controls

1. **Load limits**
   - Enforce minimum and maximum per-cable load limits before command dispatch.
   - Reject out-of-range start requests and clamp/deny invalid adjustment requests.

2. **Timeout behavior**
   - Enforce a command timeout budget for dispatch attempts.
   - Treat timeout as a safety intervention and stop command escalation/retries once the timeout budget is exceeded.

3. **Emergency stop semantics**
   - Emergency stop must dispatch immediately and bypass non-critical workflow gating.
   - If policy disables emergency dispatch for any reason, integration must fail closed and log a safety intervention.

4. **Session watchdogs**
   - Run a watchdog loop while workout sessions are active.
   - Watchdog must detect stalled command pipelines/telemetry silence and trigger a stop intervention.

5. **Structured audit events (mandatory)**
   - Emit structured audit records for:
     - Command dispatch attempts (`start`, `adjust`, `stop`)
     - Safety interventions (blocked start/adjust/stop, clamped adjustments, timeout escalations)
   - Each event must include at least: timestamp, operation, outcome, and details.

### Compliance notes

- Integrations must route **workout start**, **weight adjustments**, and **stop commands** through policy checks **before** command emission.
- Direct BLE writes that bypass policy checks are non-compliant.
