# Framework Template Distribution Target

This repository now supports a dedicated distribution target named `framework-template`.

## Strategy

- **Distribution model:** maintained branch in this repository (`framework-template`).
- **Source of truth:** `main` remains canonical; template artifacts are generated from source and published from `framework-template`.
- **Generator:** `framework-template/generate-framework-template.sh`.

## Release Flow

1. Checkout the `framework-template` branch.
2. Rebase/merge from `main`.
3. Run:
   ```bash
   ./framework-template/generate-framework-template.sh
   ```
4. Validate generated output (`./gradlew :shared:check`).
5. Commit generated template snapshot.
6. Tag with `v<core-version>-template`.

## Why a branch instead of a separate repository?

- Keeps code and template evolution synchronized.
- Preserves one issue tracker and one release cadence.
- Reduces cross-repo automation overhead while retaining a dedicated install target for consumers.
