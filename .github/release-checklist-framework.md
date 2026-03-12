# Framework Release Checklist

Use this checklist for any framework-core / protocol-spi release.

## Versioning & API Governance

- [ ] Semantic versions selected for `framework-core` and `framework-protocol-spi`.
- [ ] API diff reviewed for breaking changes.
- [ ] Deprecations documented with replacement paths and removal target.
- [ ] Stability annotations (`@StableApi`, `@ExperimentalApi`, `@InternalApi`) reviewed for changed APIs.

## Documentation

- [ ] Release notes published with compatibility statement.
- [ ] Upgrade guide updated for plugin authors.
- [ ] Compatibility matrix updated.

## Template & Samples

- [ ] `framework-template` branch regenerated with `framework-template/generate-framework-template.sh`.
- [ ] Sample adapter updated and validated against latest SPI.

## Quality Gates

- [ ] Unit/integration test suite passed.
- [ ] Conformance suite updated (if protocol behavior changed) and passing.
- [ ] CI workflows green for target release commit.

## Publish

- [ ] Release tags pushed.
- [ ] Artifacts published.
- [ ] Release announcement includes migration and compatibility notes.
