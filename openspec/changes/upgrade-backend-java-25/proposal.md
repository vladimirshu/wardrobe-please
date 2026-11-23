## Why

Move the backend from Java 17 to Java 25 LTS for a maintained runtime baseline. Vladimir selected 25 LTS instead of the initial Java 26 proposal, whose Temurin release is already EOSL. Keep this a small compatibility-focused upgrade.

## What Changes

- Align the Gradle compiler/tests and Temurin Docker build/runtime on Java 25 LTS, without preview features.
- Verify existing dependencies, optional DeepSeek and Linux arm64 startup/readiness/search; change libraries only if compatibility requires it.
- Compare the cleaned Java 17 and Java 25 artifacts with the same local memory workload; retain host sizing pending total-host evidence.
- Update runtime/deployment docs and retain the Java 17 artifact/image for rollback.

## Capabilities

### New Capabilities
- `backend-java-runtime`: consistent Java 25 LTS build/runtime and compatibility acceptance.

### Modified Capabilities
<!-- API/search behavior and memory acceptance rules stay unchanged. -->

## Impact

`wardrobe-be/build.gradle.kts`, `Dockerfile`, memory verification tooling, backend/AWS architecture documentation. No provider, API, framework, GC or instance-size redesign. No production deployment is included.
