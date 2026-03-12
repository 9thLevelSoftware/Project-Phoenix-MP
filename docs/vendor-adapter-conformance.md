# Vendor Adapter Conformance Suite

When adding or updating a vendor BLE adapter, run the shared conformance suite before opening a PR.

## What it validates

The suite checks cross-vendor invariants:

1. **Command encoding invariants** (start/stop/activation packet shape)
2. **Telemetry decoding validity** (valid monitor packets decode, invalid packets are rejected)
3. **Workout lifecycle state transitions** (Idle → Active → SetSummary)
4. **Reconnect/error semantics** (connection-loss alert behavior)

## Run locally

Run all adapter targets:

```bash
./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.conformance.*"
```

Run for a single adapter target:

```bash
VENDOR_ADAPTER=phoenix ./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.conformance.*"
VENDOR_ADAPTER=demo ./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.conformance.*"
```

## CI coverage

The `CI Tests` workflow includes a `vendor-conformance` matrix job that runs the suite for:

- `phoenix`
- `demo`

Add new vendor IDs to the matrix and `VendorConformanceTargets` when introducing a new plugin.
