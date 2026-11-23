## Context

The previous backend compiled/tested with Java 17 and used Temurin 17 JDK/JRE Docker stages. Running the Gradle launcher on the locally installed JDK 26 did not change that toolchain. The wrapper is Gradle 9.8.2; Spring Boot is 4.1.2. The memory cleanup records a separate packaged Java 17 baseline.

Vladimir selected Java 25 LTS on 2026-11-23, replacing the initial Java 26 target. [Temurin's roadmap](https://adoptium.net/support/) lists 25 as maintained LTS and 26 as EOSL. This change has been renamed accordingly; it does not implement Java 26.

## Decisions

1. Use Java 25 for compilation and tests, with Temurin 25 JDK/JRE Docker stages. Verify compiler/test launcher versions and container `java -version`; no preview features or unrelated concurrency/GC changes. Keep the default OpenAI-only artifact and optional DeepSeek path.
2. Keep Gradle 9.8.2 and the existing dependency versions unless compatibility checks show a problem. [Gradle's matrix](https://docs.gradle.org/current/userguide/compatibility.html) supports Java 25 from 9.1.0. [Lombok](https://projectlombok.org/changelog) added JDK 25 support in 1.18.40; check the resolved annotation processor and test instrumentation, not just application startup. Validate the exact Spring Boot/Spring AI/Qdrant dependency set with the offline suite.
3. Verify Temurin 25 Linux arm64 builder/runtime tags and record their tested digests. Continue using major-version tags so rebuilds can receive maintained patch releases; preserve the previous application artifact/image for rollback. Any later digest pin needs a patch-refresh process.
4. The Java 17/25 local comparison was completed without establishing lower total RSS or nano viability. Its experiment-only runner/data have since been removed at the owner's request. Retain concise verification findings and the ordinary bounded application/container settings; do not infer AWS capacity from local checks.
5. Update runtime/backend and planned AWS documentation to Java 25. Roll back by redeploying the retained Java 17 artifact/image; Java 25 bytecode is not runnable on Java 17. Existing AWS micro/small candidates and unverified JVM caps remain until total-host measurements justify a change.

## Risks and non-goals

Annotation processors, Mockito/Byte Buddy instrumentation, native transport and container ergonomics can differ across JDKs; resolve actual failures without disabling checks or adding blanket compatibility flags. macOS measurements with synthetic AI do not prove Linux host capacity or real-provider latency. No preview APIs, virtual-thread migration, native-image/framework upgrade or production rollout is included.

## Verification

Implementation results, image digests and memory evidence are recorded in [verification.md](verification.md).
