# Search timeout budget verification

Date: 2026-11-23. Local implementation verified; deployment and live providers were not used.

## Results

- Default backend suite: **272 tests, zero failures/errors, four skipped** for optional/environment conditions.
- Optional DeepSeek runtime: **five tests passed**, using the cached starter on the test runtime classpath only, WireMock and local Qdrant. The production dependency declaration remains unchanged.
- Frontend: **117 tests passed**; production TypeScript/Vite build passed.
- `openspec validate search-timeout-budget --strict` and scoped `git diff --check` passed.

The initial broad backend run inherited cloud Qdrant TLS settings and was stopped after
local-fixture connection failures. The successful run explicitly used the README's local
Qdrant environment below. Regression checks also caught and fixed duplicate timer
cancellation, MockMvc path matching, and malformed SDK JSON being incorrectly considered
retryable because its parser exception contained an `IOException`.

## Coverage and effective behavior

- A controllable monotonic clock checks the 25-second deadline across multiple stages;
  a late stage receives only remaining time. Short real deadlines exercise cancellation.
- An actual local delayed chat response stops at the five-second attempt cap while
  request time remains. Tests exhaust the shared retry allowance first so this measures
  one attempt, rather than several attempts combined.
- Local OpenAI SDK chat and embedding tests verify actual wire request counts, dynamic
  deadlines, delayed headers and delayed body cancellation. A local JDK HTTP test verifies
  body cancellation for the DeepSeek transport.
- Schema correction shares the same allowance as transport retries, keeps its one-repeat
  stage limit, and cannot send after exhaustion. Distinct stages retain their initial
  call even after earlier retries use all three extras.
- Native OpenAI chat/embedding SDK retries and Spring retry retries are zero, with a
  startup check rejecting overrides. Spring AI 2.0.2 maps `spring.ai.retry.max-attempts`
  to Spring's `RetryPolicy.maxRetries`, so **0**, not 1, disables its extra sends.
  OpenAI model request options alone do not change the SDK client's retry count.
- Search HTTP disables automatic connection recovery and redirects. HTTP 503 yields
  exactly four sends from a fresh search; 401 and malformed provider JSON remain
  non-retryable. Optional DeepSeek integration confirms the same four-send bound.
- A `Retry-After` longer than the remaining budget fails immediately without a second
  send. Normal retry delay is 100ms. Timeouts do not add admission charges.
- A real local gRPC server observes cancellation when its deadline expires. Qdrant
  service retries are disabled; search-only channel hooks supply the remaining deadline.
- MVC tests assert one admission and one service invocation across downstream retries,
  a generic 504, cleared request context, and discarded partial response content.
- Conversation-lock contention expires without running the queued search/reset and
  releases its lock bookkeeping. Completed requests cannot later interrupt a reused
  request thread.
- Health routes do not acquire the search context. Existing real-Qdrant startup,
  readiness, browsing, validation, admission, conversation and search tests pass.
- Frontend fake-timer verification aborts at exactly 30 seconds with one send. A 504
  maps to the ordinary server error; draft preservation, readiness recovery and absence
  of automatic replay are tested. A successful readiness check retains a healthy gate.

The implementation cancels locally controlled exchanges and waits; it cannot guarantee
that a remote provider stops processing or charging a previously accepted request.
Previously completed memory/admission work is not rolled back. Startup import and probes
keep their independent timeouts, while shared SDK/Spring native retry defaults are now
disabled outside search too.

## Commands

From `wardrobe-be`:

```sh
QDRANT_USE_TLS=false QDRANT_API_KEY= \
  JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  ./gradlew test --offline
```

Optional provider verification used an external Gradle init script containing:

```groovy
allprojects {
    afterEvaluate {
        dependencies {
            testRuntimeOnly 'org.springframework.ai:spring-ai-starter-model-deepseek'
        }
    }
}
```

Run the same local environment with
`./gradlew test --offline -I <init-script> --tests 'com.ecomassistant.queryanalysis.DeepSeekQueryAnalysisIntegrationTest'`.
This changes only the test runtime classpath and needs cached dependencies for offline use.

From `wardrobe-fe`: `npm test -- --reporter=dot` and `npm run build`.
From the repository root: `openspec validate search-timeout-budget --strict`.
