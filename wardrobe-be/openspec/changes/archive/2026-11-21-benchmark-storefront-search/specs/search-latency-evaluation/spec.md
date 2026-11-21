## ADDED Requirements

### Requirement: Reproducible HTTP load profile

The benchmark SHALL provide a CLI JMeter plan for `GET /api/v1/search`. Each run SHALL record the code, catalog, index and workload revisions, client and backend locations, provider and model settings, candidate limits, deadlines, retries, warm-up policy, load steps, duration and repetitions. Each compared flow SHALL receive the same declared traffic profile and query mix. Sessions SHALL use independent conversation IDs and ordered turns. Real-provider runs SHALL follow the opt-in policy in `search-relevance-evaluation`.

#### Scenario: First baseline run
- **WHEN** the benchmark runs before an agreed load target or p95 budget exists
- **THEN** it measures the current backend flow at a declared serial and bounded concurrent profile, reports the baseline and leaves release targets to be agreed using those results

#### Scenario: Matched comparison
- **WHEN** both GPT-6 Luna flows can run with the same catalog, embedding model, filters, batching, candidate and output limits
- **THEN** the benchmark runs both in alternating matched blocks and records any remaining differences in chat API, retries or provider settings

### Requirement: End-to-end and stage measurements

The report SHALL include sent and completed requests per second, p50 and p95 full HTTP response time, sample count, timeout and error rates, and rejected and empty-result counts per flow and load level. It SHALL report p99 when the predeclared sample rule is met. It SHALL break out comparable outcomes, including searches that return products, successful empty results, application admission rejection, guardrail rejection, provider throttle and dependency failure. It SHALL report size and exclusion cohorts, available request-correlated stage timings and actual outbound chat, embedding and tool sends, including retries. Usage SHALL include available input and output tokens and estimated provider cost per 1,000 successful searches, with the cost scope, rate revision and outcome mix recorded.

#### Scenario: Upstream rate limit during load
- **WHEN** the offered load exceeds provider quota
- **THEN** the report identifies provider throttling, shows the offered and completed rates, and distinguishes backend admission limits from provider limits

#### Scenario: Provider does not expose internal model timing
- **WHEN** only total Responses API time and server-side tool timing can be observed
- **THEN** the benchmark reports those measurements and marks internal model stage timings unavailable
