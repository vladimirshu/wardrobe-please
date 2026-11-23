## 1. Dependency cleanup

- [x] 1.1 Remove the OAuth2 client starter and verify its unused transitives disappear; retain Spring Security and existing filter behavior
- [x] 1.2 Replace `@Timed` with programmatic `product.search` timing for success/failure; remove MetricsConfig/TimedAspect and the AspectJ starter
- [x] 1.3 Comment out the DeepSeek starter; isolate optional type references so default compilation, tests and OpenAI startup work without its runtime; preserve source/tests and document uncomment/rebuild/provider-selection re-enablement

## 2. Verification and sizing

- [x] 2.1 Inspect the packaged production classpath and run relevant offline checks for readiness, search, preserved timing and missing/re-enabled DeepSeek configurations
- [x] 2.2 Compare packaged Java 17 baseline and cleaned runtime with the same settings/workload; try a 192 MiB heap/SerialGC and record used/committed heap, total memory, startup and search behavior without sending data to paid providers
- [ ] 2.3 Validate total-host headroom, startup/reindex peaks, latency and swap on the candidate deployment for micro acceptance; use small if micro fails
- [x] 2.4 Update backend provider/deployment documentation, affected architecture docs and AWS host sizing/JVM settings with measured results; do not mark nano viable without those results


## Current runtime configuration

- [x] 3.1 Retain one shared 64–256 MiB application heap preset and JVM-default GC/metaspace; keep build/test JVM settings independent
- [x] 3.2 Use one 640 MiB no-swap Compose backend profile; remove the nano override and experiment-only runner/data files
- [x] 3.3 Verify the consolidated settings against local fixtures and update runtime/architecture/AWS documentation

Historical local comparisons were completed. The 352 MiB container failed startup;
that experimental profile is retired. Task 2.3 remains pending for AWS micro total-host
validation, with small fallback. See [verification.md](verification.md).
