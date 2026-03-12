# Vendor Integration Guide

This guide explains how to build and ship a new machine vendor adapter using the `template/` scaffolding.

## 1) What is included

- `template/starter/`
  - `VendorPlugin.kt`: plugin entrypoint interface.
  - `MachineProfile.kt`: capability declaration model.
  - `CommandEncoder.kt`: outbound command contract.
  - `TelemetryDecoder.kt`: inbound telemetry contract.
  - `PluginRegistry.kt`: simple plugin registration point.
- `template/examples/demogym/`
  - Full `DemoGym` adapter implementation.
  - `DemoGymSimulatorHooks.kt` for local packet simulation.
- `template/config/vendor-template.config.json`
  - Placeholder IDs and BLE UUIDs.
- `template/assets/brand-logo-placeholder.svg`
  - Replace with your branded art.

## 2) Bootstrap a new vendor package

### Bash

```bash
scripts/new_vendor_template.sh --vendor Acme --package com.phoenix.vendor.acme
```

### PowerShell

```powershell
./scripts/new_vendor_template.ps1 -Vendor Acme -Package com.phoenix.vendor.acme
```

Optional flags:

- `--out` / `-Out`: output folder for generated files.
- `--registry` / `-Registry`: existing registry file where `register(<Vendor>Plugin())` is appended.

## 3) Required interfaces

Your adapter must implement the following interface set:

1. `VendorPlugin`
   - Stable `id` for persistence/lookup.
   - Human readable `displayName`.
   - One or more `supportedProfiles`.
   - Factory methods for an encoder and decoder.
2. `MachineProfile`
   - Model name.
   - Protocol version.
   - Feature flags (`supportsEccentricControl`, `supportsLiveTelemetry`).
   - Operational bounds (`maxResistance`).
3. `CommandEncoder`
   - Session start payload.
   - Resistance or load control payload.
   - Session stop payload.
4. `TelemetryDecoder`
   - Packet validation.
   - Byte-to-domain conversion into `TelemetryFrame`.

## 4) Local development with simulator hooks

Use the `DemoGymSimulatorHooks` example to:

- Create deterministic telemetry streams for UI/integration testing.
- Produce encoded byte packets to validate decoder behavior.
- Regression-test protocol changes before hardware is available.

Typical loop:

1. Build a `TelemetryFrame` from simulator values.
2. Encode it into a packet.
3. Decode it with your adapter decoder.
4. Assert field parity.

## 5) Conformance checklist

Before opening a PR, verify:

- [ ] Vendor package ID and class names are vendor-specific (no `template` leftovers).
- [ ] `VendorPlugin.id` is stable and unique.
- [ ] At least one `MachineProfile` accurately describes protocol capabilities.
- [ ] `CommandEncoder` validates bounds and does not emit malformed payloads.
- [ ] `TelemetryDecoder` handles invalid/short packets safely (`null` or error path).
- [ ] Config file contains final UUIDs/endpoints and branded asset references.
- [ ] Plugin is registered through `PluginRegistry` or your app DI equivalent.
- [ ] Simulator hook can emit packets that round-trip through decoder.
