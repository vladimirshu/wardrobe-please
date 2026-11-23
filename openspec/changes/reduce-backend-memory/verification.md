# Backend runtime verification — 2026-11-23

Dependency cleanup is retained: OAuth2/AspectJ removed, programmatic
`product.search` timing and optional DeepSeek. The default artifact has 161
runtime libraries (178 before cleanup); Security, readiness, validation, OpenAI
and Qdrant remain. Default and explicitly enabled DeepSeek offline checks passed
in the original implementation.

The experimental 352 MiB container was OOM-killed before readiness. Its 640 MiB
fallback passed catalog rebuild/reuse and 100 local-fixture searches. This did
not establish total-host capacity. Nano is no longer a deployment candidate.
Experiment-only runner, alternate Compose file and detailed JSON reports were
removed from the project at the owner's request; temporary copies remain outside
the project under `/tmp/wardrobe-memory-experiments-rollback`.

The current single profile uses `-Xms64m -Xmx256m`, JVM-default GC/metaspace and a
640 MiB no-swap backend container. The consolidated settings passed the rebuilt Linux arm64 image check:
startup catalog rebuild and revision reuse reached readiness in about 3.99 s
and 2.74 s. Both phases completed 50 synthetic local searches, with four concurrent
workers, no HTTP errors and no OOM kills. Sampled container memory peaked around
480 MiB (cgroup usage, including cache/native memory; short peaks may be missed).
Effective bootRun arguments are `[-Xms64m, -Xmx256m]`; test arguments are unchanged.
Compose resolves memory and memory-plus-swap to 671,088,640 bytes each.
Raw verification artifacts remain outside the project at
`/tmp/wardrobe-runtime-verification`; no paid provider calls were made.
AWS micro OS/Docker/SSM headroom, real-provider load/latency and swap checks remain
pending; small is the fallback. No AWS deployment is claimed.
