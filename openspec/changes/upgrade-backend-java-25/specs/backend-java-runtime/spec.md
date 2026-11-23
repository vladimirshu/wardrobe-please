## ADDED Requirements

### Requirement: Consistent Java 25 LTS runtime
The backend SHALL compile and run tests with a Java 25 LTS toolchain and run its packaged artifact on a verified Java 25 LTS container runtime for Linux arm64, without preview features. The selected distribution's security-update/support status SHALL be recorded and resolved before production rollout.

#### Scenario: Reproducible build and startup
- **WHEN** the backend is built and started using its documented configuration
- **THEN** compilation, test execution and the container runtime use Java 25 LTS and readiness/search retain their existing behavior

#### Scenario: Unsupported distribution
- **WHEN** no maintained Java 25 LTS distribution is available for the intended deployment
- **THEN** production rollout remains pending until the support path or target version is resolved

### Requirement: Compatibility and sizing evidence
The upgrade SHALL preserve API/security/readiness, OpenAI embeddings and optional DeepSeek behavior, and SHALL pass relevant offline tests and a packaged/container smoke check. Memory evidence SHALL compare cleaned Java 17 and Java 25 LTS artifacts under equivalent conditions and SHALL NOT establish a smaller host size without total-host validation.

#### Scenario: Library incompatibility
- **WHEN** annotation processing, test instrumentation or an integration fails on Java 25 LTS
- **THEN** the upgrade records and resolves that incompatibility before acceptance, without silently disabling the affected checks

#### Scenario: Rollback
- **WHEN** Java 25 LTS startup or search validation fails
- **THEN** the previous verified Java 17 image/artifact remains available for redeployment
