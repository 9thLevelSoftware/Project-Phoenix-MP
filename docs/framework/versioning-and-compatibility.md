# Framework API Versioning, Stability, and Deprecation Policy

## Scope

This policy applies to:

- **Core Framework API:** public Kotlin APIs consumed by host applications.
- **Protocol SPI:** plugin/service provider interfaces and protocol integration points.

## Semantic Versioning

Both Core API and Protocol SPI follow **Semantic Versioning** (`MAJOR.MINOR.PATCH`).

### Core API SemVer Rules

- **MAJOR:** breaking API change, removed public type/member, changed behavior requiring consumer code changes.
- **MINOR:** backward-compatible feature additions.
- **PATCH:** backward-compatible bug fixes and internal changes.

### Protocol SPI SemVer Rules

- **MAJOR:** SPI contract or protocol behavior breaks existing plugins/adapters.
- **MINOR:** additive SPI capability that does not break existing implementations.
- **PATCH:** SPI bug fixes without contract changes.

### Version Publication

Each release must publish:

- `framework-core` version
- `framework-protocol-spi` version
- compatibility statement in release notes

## Compatibility Contract for Plugin Authors

- Core `PATCH` and `MINOR` releases are required to preserve source compatibility unless explicitly documented.
- SPI `PATCH` releases are required to preserve binary and source compatibility.
- SPI `MINOR` releases may add optional extension points but cannot invalidate existing plugin registrations.

## Deprecation Policy

Deprecations are handled in three stages:

1. **Soft deprecation (N):** mark API as deprecated with replacement guidance.
2. **Transition window (N+1):** retain deprecated API, update docs/examples, and warn in release notes.
3. **Removal (N+2 or next major):** remove only in a major release unless an urgent security issue requires earlier removal.

Default minimum deprecation period:

- Core API: **2 minor releases**.
- Protocol SPI: **3 minor releases** (to allow ecosystem migration).

## Stability Annotations

Framework packages should use explicit stability markers:

- `@StableApi` for APIs with semver compatibility guarantees.
- `@ExperimentalApi` for opt-in features with no stability guarantees.
- `@InternalApi` for non-public APIs (not for plugin consumption).

When Kotlin annotations are not yet implemented, document intended stability using KDoc tags:

- `@since`
- `@deprecated`
- `@replacement`
- `@stability Stable|Experimental|Internal`

## Breaking Change Exception Process

A breaking change outside a major release requires:

1. Security or critical correctness rationale.
2. Written migration instructions.
3. Explicit compatibility exception section in release notes.
