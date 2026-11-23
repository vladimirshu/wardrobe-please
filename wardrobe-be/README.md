# Wardrobe, Please backend

Spring Boot service behind the storefront search API (`POST /api/v1/search`). See the
repository root `Readme.md` for the overall project and how to run it locally.
The compiler and tests use Java 25 LTS; the Docker builder/runtime use Temurin 25.
Build with `./gradlew bootJar`; run the packaged artifact on Java 25 (see runtime limits below).
For offline verification against local Testcontainers Qdrant, use
`QDRANT_USE_TLS=false QDRANT_API_KEY= ./gradlew test --offline` so an inherited
cloud TLS setting cannot affect local fixtures.

## Cost controls and operations

An admitted search calls the configured chat provider for query analysis. If analysis allows the request and Qdrant returns candidates, a second call selects matching SKUs. Both calls use the same provider; OpenAI GPT-6 Luna is the default.
Each call can retry invalid structured output once. The selected client also applies its configured transport retry limit.
Truncated or refused completions fail the search.
An allowed search uses the existing OpenAI `text-embedding-3-small` model for Qdrant.

The default build packages OpenAI only. To enable DeepSeek, uncomment
`implementation("org.springframework.ai:spring-ai-starter-model-deepseek")` in
`build.gradle.kts`, rebuild the JAR (or Compose image), select
`spring.ai.model.chat=deepseek`, and supply `DEEPSEEK_API_KEY`. Keep `OPENAI_API_KEY`
for embeddings with either provider. Selecting DeepSeek without rebuilding fails
at startup with these instructions. Its adapter API is compile-only in the default
build; its provider integration tests run only when the runtime is present.
To return to the default, comment the starter, rebuild, and select `openai`.

`product.search` uses programmatic Micrometer timing on both successful and failed
service calls, retaining the `class`, `method` and `exception` tags. It adds no
message or conversation tags. OAuth2 login and AspectJ dependencies are removed;
Spring Security, validation, Qdrant and health/readiness remain.

Configure the limits in `application.yml`:

| Property | Default | Meaning |
|---|---|---|
| `ecom.limits.per-minute` | 5 | accepted searches per client per minute |
| `ecom.limits.per-hour` | 20 | accepted searches per client per hour |
| `ecom.limits.daily-budget` | 300 | accepted searches per UTC day across all clients |
| `ecom.limits.trusted-proxy-hops` | 1 | trusted `X-Forwarded-For` entries, counted from the right |
| `spring.ai.openai.chat.max-completion-tokens` | 500 | maximum query-analysis completion tokens per attempt, including reasoning |
| `spring.ai.openai.chat.timeout` | 15s | chat request timeout |
| `spring.ai.openai.chat.max-retries` | 10 | SDK retries per attempt (up to 11 sends, including the initial send) |
| `spring.ai.deepseek.chat.max-tokens` | 500 | DeepSeek query-analysis completion token cap |
| `ecom.search.product-filter-max-output-tokens` | 300 | SKU selection completion token cap for either provider |
| `ecom.search.max-results` | 10 | maximum number of selected products returned |
| `spring.ai.retry.max-attempts` | 10 | DeepSeek transport retries per attempt (up to 11 sends, including the initial send) |

A rejected search returns HTTP 429 with a `Retry-After` header and a body
`{"code":"RATE_LIMITED"|"DAILY_BUDGET_EXHAUSTED","message":"..."}` (see `openapi.yaml`).

### In-memory limitation

The per-client rate-limit buckets and the daily-budget counter live in process memory
only. They reset to zero whenever the application restarts, and they are not shared
between instances. Repeated restarts can therefore raise the day's effective ceiling; the
per-client limits still apply after every restart. See the provider spending controls below.

### Required before the first public link (runbook step)

Configure spending controls for the dedicated OpenAI project used by the demo.
See [OpenAI project limits](https://help.openai.com/en/articles/9186755-managing-projects-in-the-api-platform)
and [prepaid billing](https://help.openai.com/en/articles/8264644-setting-up-and-managing-prepaid-api-billing).

Use the same `products` collection when changing chat providers. For the live checks,
see [provider migration verification](openspec/changes/archive/2026-11-17-migrate-to-openai-luna/verification.md).

Search accepts JSON only; deploy frontend and backend together. Example:

```sh
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{"message":"blue cotton jacket","conversationId":"session_42","clearMemory":false}'
```

`message` and `conversationId` are required; omitted `clearMemory` defaults to false.
Search fields bind only from the body. GET returns 405; missing or malformed JSON
and invalid fields return 400, and unsupported content types return 415, before
quota admission or downstream calls.


## Runtime memory

`runtime-jvm.args` supplies **`-Xms64m -Xmx256m`** to the Docker entrypoint and
Gradle `bootRun`. GC selection and metaspace sizing use JVM defaults. Gradle and
test JVMs keep independent settings. Compose caps the backend at **640 MiB**,
with equal `mem_limit`/`memswap_limit` values to disable backend swap.

Start the stack from the repository root:

```sh
docker compose up --build
```

For a packaged run from this directory:

```sh
./gradlew bootJar
java @runtime-jvm.args -jar build/libs/wardrobe-please-0.0.1-SNAPSHOT.jar
```

Supply the existing provider/Qdrant environment settings. Plain `java -jar` and
IDE launches do not automatically load this file; add
`@/absolute/path/to/wardrobe-be/runtime-jvm.args` to IDE VM options. Avoid inherited
heap/GC overrides when comparing runs. Edit the shared file and rebuild the image
to change JVM limits; a container-limit change alone does not change the heap cap.

Use `docker stats` to inspect container memory. The 640 MiB cap covers the whole
backend container, not just its heap. Local frontend/Qdrant use additional memory.
AWS starts with a 1 GiB micro candidate (remote Qdrant), with small as fallback;
OS/Docker/SSM headroom and real-provider load still need validation. Nano experiments
and the separate memory-profile override/benchmark runner were removed.

Rollback requires the retained Java 17 artifact/image and its runtime together.
The Java 25 artifact cannot run on Java 17. No AWS deployment or instance resize
is part of the Java upgrade.
