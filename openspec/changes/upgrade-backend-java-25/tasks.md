## 1. Runtime alignment

- [x] 1.1 Verify the maintained Java 25 LTS distribution and Linux arm64 builder/runtime tags
- [x] 1.2 Switch the Gradle compiler/test toolchain and Docker stages to Java 25 LTS; make only necessary dependency compatibility updates

## 2. Verification and documentation

- [x] 2.1 Run the offline suite and optional DeepSeek checks on Java 25 LTS; verify packaged/container readiness, search and JVM versions
- [x] 2.2 Compare cleaned Java 17/25 with equivalent local workloads (completed; experiment tooling subsequently removed at owner request)
- [x] 2.3 Update backend/architecture/AWS runtime documentation with versions, results, support status and Java 17 artifact rollback; retain host sizing pending total-host evidence


See [verification.md](verification.md) for tested versions, warnings, container and memory results.
AWS total-host acceptance remains tracked separately in `reduce-backend-memory` task 2.3.
