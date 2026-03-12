# Canonical Spec: Plugin SPI

## Purpose

Define a stable Service Provider Interface (SPI) for optional feature extensions without tightly coupling core workflows to plugin implementations.

## SPI principles

1. **Stable contracts first**: SPI contracts must be explicit, documented, and versioned.
2. **Isolation**: Plugin failures must not crash core training flows.
3. **Deterministic lifecycle**: Plugin initialization, execution, and teardown states are predictable.
4. **Capability declaration**: Plugins declare supported capabilities before activation.

## Core SPI contracts

### Provider metadata

Each plugin provider must expose:
- `id` (stable unique identifier)
- `version` (semantic version)
- `capabilities` (declared feature set)
- `compatibility` (minimum/maximum supported app contract versions)

### Lifecycle hooks

- `initialize(context)`
- `start(session)`
- `execute(event)`
- `stop(reason)`
- `dispose()`

### Error contract

- Plugins return typed failures where possible.
- Runtime exceptions are contained and converted to non-fatal plugin errors.
- Error severity levels: `warning`, `recoverable`, `fatal`.

## Compatibility policy

- SPI major version increments for breaking contract changes.
- Minor versions are backward-compatible additions.
- Deprecated hooks require at least one release cycle before removal.

## Security and safety requirements

- Plugin input must be validated at boundaries.
- Sensitive context data should be minimized and explicitly granted.
- Plugin timeouts must be enforced for non-blocking user flows.

## Validation requirements

- Contract conformance tests for lifecycle ordering.
- Negative tests for failure isolation.
- Compatibility tests across supported SPI versions.
