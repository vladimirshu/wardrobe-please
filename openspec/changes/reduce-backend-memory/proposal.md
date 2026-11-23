## Why

Reduce the backend's measured heap and total runtime memory with small dependency/configuration changes, and establish whether a 512 MiB EC2 host is viable. The reported 567 MB came from macOS Activity Monitor during IDE/Gradle execution; it is process memory, not a measured live heap requirement or an EC2 benchmark.

## What Changes

- Remove the unused OAuth2 client starter and its unneeded transitive runtime libraries.
- Replace the single AspectJ-based search timer with programmatic Micrometer timing, then remove the AspectJ starter and TimedAspect configuration.
- Comment out the DeepSeek starter in the default build, isolate its type references and retain a quick documented re-enablement path. Default production runtime is OpenAI-only; OpenAI embeddings remain.
- Keep Spring Security, Actuator/readiness, validation, Qdrant and the search pipeline.
- Retain a single bounded runtime configuration (64–256 MiB heap, 640 MiB no-swap container, JVM-default GC) and evaluate micro with small fallback. Nano experiment tooling is retired.

## Capabilities

### New Capabilities
- `backend-memory-footprint`: dependency scope, optional provider packaging, preserved timing and evidence-based runtime sizing.

### Modified Capabilities
<!-- Public API and search semantics remain unchanged; DeepSeek becomes an optional build-time inclusion. -->

## Impact

`wardrobe-be` Gradle dependencies, provider-specific configuration, search metrics and related tests/documentation. No framework migration, native-image build, new monitoring service, Spring Security removal or forced EC2 downsize. Dependency cleanup, optional provider isolation and local verification are implemented. Runtime memory settings have been consolidated to the single configuration above; AWS total-host validation remains pending. See verification.md.
