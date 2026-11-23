## Current implementation (2026-11-23)

Unused OAuth2/AspectJ dependencies are removed, search timing is programmatic,
and DeepSeek is optional. Java 25 LTS is implemented in its separate change.
The nano memory experiments, benchmark runner, measurement JSON files and
alternate Compose profile have been removed at the owner's request.

The retained runtime configuration is one shared `runtime-jvm.args` file with
`-Xms64m -Xmx256m`, loaded by Docker and bootRun and documented for direct/IDE
runs. GC selection and metaspace sizing use JVM defaults. Build/test JVMs remain
independent. Compose caps the backend at 640 MiB without swap. AWS evaluates
micro (1 GiB), with small fallback; total-host acceptance remains pending.
See [verification.md](verification.md).

## Context

The backend runs Spring MVC, Spring AI, OpenAI chat/embeddings and remote Qdrant. OAuth2 login is not configured. The AspectJ setup backs one `@Timed` search method via `MetricsConfig`. DeepSeek types are referenced directly by `AiConfig`, so commenting the starter alone is insufficient. Test dependencies and DevTools are already excluded from the packaged production artifact.

## Decisions

1. Remove `spring-boot-starter-oauth2-client`; retain the existing Spring Security starter and filter chain. Check the resolved production classpath for leftover OAuth/OIDC libraries rather than assuming all transitives disappear.
2. Preserve the `product.search` metric name and latency/failure recording using Micrometer's programmatic Timer around the search method, including exceptional exits. Remove `@Timed`, the TimedAspect bean/configuration and `spring-boot-starter-aspectj`. Do not add user input or conversation IDs as metric tags. No dashboards or exporters are introduced.
3. Keep the DeepSeek starter declaration visibly commented out with re-enablement instructions. Default packaged/runtime dependencies must exclude DeepSeek. Retain its integration source and tests, but isolate provider-only type references so OpenAI builds and starts without DeepSeek classes. Compile-only API dependencies or conditional provider configuration may be used if necessary; neither may pull DeepSeek into the default runtime. Run DeepSeek-dependent tests only when its runtime is explicitly included. Re-enable by uncommenting the starter, rebuilding and selecting `spring.ai.model.chat=deepseek` with its key; embeddings stay on OpenAI. Selecting DeepSeek without its runtime must fail clearly, not produce an obscure missing-class error or silently fall back. No generic provider framework redesign is required.
4. Keep Spring Security, readiness/Actuator, validation and the Qdrant/search flow. Do not remove working integrations just to shrink the JAR. JAR size, heap used, committed heap and total process/container memory are different measurements.
5. Keep one ordinary bounded runtime configuration rather than multiple experimental profiles. Heap and total container/host memory remain distinct; the current limits are defaults for testing, not proof of AWS capacity. Preserve the concise historical findings, but remove experiment-only tooling/artifacts from the project.
6. Validate total-host headroom, startup/reindex, repeated real-provider searches and swap on micro before accepting it. Use small if micro fails. No nano deployment is planned.

## Non-goals and rollout

No global lazy initialization, token-counter replacement, Qdrant transport rewrite or GraalVM migration in this change. Keep existing timeout/admission behavior; the separate search-timeout-budget change owns deadline enforcement. Update backend deployment/provider instructions and architecture documentation when implementation lands. Coordinate the host JVM flags and size with the AWS backend-host design only after measurements support the change.
