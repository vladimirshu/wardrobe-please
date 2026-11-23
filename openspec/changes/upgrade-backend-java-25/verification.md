# Java 25 LTS upgrade verification — 2026-11-23

The Java 26 proposal was replaced by Java 25 LTS at Vladimir's request. Gradle
compilation/tests now select Java 25 and the Docker stages use Temurin 25 JDK/JRE.
Gradle 9.8.2, Spring Boot 4.1.2 and the existing dependency versions are retained.

## Build and compatibility

- Clean default offline suite/package passed with local Qdrant/WireMock:
  `QDRANT_USE_TLS=false QDRANT_API_KEY= ./gradlew clean test bootJar --offline`.
- Clean default XML reports 252 cases, zero failures/errors and four skips
  (including DeepSeek when its runtime is absent). Explicitly adding the starter
  only to the test runtime and rerunning DeepSeek/configuration checks passed
  seven cases with no skips/failures.
- The default production library set is unchanged from cleaned Java 17: 161
  libraries, excluding DeepSeek/OAuth/OIDC/Nimbus/AspectJ/DevTools.
- Local Java 25 toolchain: Homebrew 25.0.4.1; generated application class major 69.
  Resolved Lombok 1.18.48 needs no version override. The actual test launcher is
  `/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home/bin/java`.
- Lombok Unsafe and Netty/gRPC native-access warnings are emitted on this JDK,
  but the checked paths run successfully. No broad JVM access flags, disabled
  tests or experimental compatibility switches were added.
- An optional test scan initially found stale generated `Test 2.class` files
  whose names did not match their bytecode. Clearing generated build outputs
  resolved that unrelated workspace artifact issue.

## Linux arm64 container

`docker build --platform linux/arm64 -t wardrobe-java25:verification .` completed
using the repository Dockerfile, including its clean in-container Gradle build.
Verified runtime: **Temurin 25.0.5.1+2 LTS**, Linux arm64.

Tested base image index digests:

- `eclipse-temurin:25-jdk`: `sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3`
- `eclipse-temurin:25-jre`: `sha256:4ed132f6832e9804b123e89a849a7b4502756ae64a4ee08ebab939d9d5d0a9fa`
- Built application image: `sha256:9ea3802855080e61df1dc1da07e7dff8b6ab735eed665d4ff45acccee46b6242`

A disposable Docker network/Qdrant and local synthetic AI fixtures exercised
container readiness, startup catalog rebuild and revision reuse, and five POST
searches after each startup. Both phases passed without search errors. Combined
container-start/readiness/five-search times were 5.25 s and 6.05 s; these are not
isolated readiness or production latency measurements. All temporary smoke-test
containers/network were removed; no paid provider was called.

The `25-jdk`/`25-jre` tags remain mutable to receive maintained patch releases on
rebuild. The tested local JDK patch differs from the container patch; container
smoke checks verify the actual deployment artifact. Retain/rebuild a known-good
Java 17 application artifact/image for rollback, rather than running Java 25
bytecode on an older JVM. The cleaned pre-upgrade JAR is retained locally at
`/tmp/wardrobe-java25/java17.jar`; no production deployment has been performed.

## Memory and host sizing

The original Java 17/25 comparison used a 192 MiB heap/SerialGC experiment and
completed 200 synthetic local searches without errors. It showed no dependable
total-memory improvement from the JDK upgrade. Those settings are historical,
not the current runtime defaults. The owner requested removal of the experiment
runner/data and nano profiles; only these concise findings are retained here.

Current ordinary limits are a 64–256 MiB heap, default GC/metaspace and a 640 MiB
no-swap backend container. AWS micro host acceptance remains pending, with small
fallback. See [current runtime verification](../reduce-backend-memory/verification.md).
