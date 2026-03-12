# Plugin Author Upgrade Guide

This guide explains how plugin authors should evaluate and upgrade against framework releases.

## Compatibility Matrix

| Framework Core | Protocol SPI | Expected Plugin Impact |
|---|---|---|
| PATCH | PATCH | No source changes expected |
| MINOR | PATCH/MINOR | Optional feature adoption only |
| MAJOR | MAJOR or mixed | Migration required; check release notes |

## Upgrade Workflow

1. **Read release notes** for both `framework-core` and `framework-protocol-spi`.
2. **Check deprecations** and replacement APIs.
3. **Run adapter tests** against the new target versions.
4. **Run conformance suite** before publishing plugin updates.
5. **Publish plugin compatibility declaration** (supported framework version range).

## Migration Notes Template (for each release)

For each framework release, maintain this section in release docs:

- Breaking changes
- Deprecated APIs and replacement path
- Behavioral changes in protocol handling
- Required configuration updates
- Conformance suite result delta

## Risk-based Upgrade Recommendations

- **From PATCH to PATCH:** safe for immediate upgrade after CI passes.
- **From MINOR to MINOR:** schedule normal sprint validation; verify optional SPI additions do not shadow custom logic.
- **Across MAJOR versions:** allocate explicit migration sprint and complete full regression + conformance run.

## Backward Compatibility Notes for Maintainers

When publishing framework releases, include:

- Whether prior plugin binaries still load.
- Whether plugin source changes are required.
- Whether protocol defaults changed.
