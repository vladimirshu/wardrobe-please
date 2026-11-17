## ADDED Requirements

### Requirement: Reproducible latency comparison
The main comparison SHALL use GPT-6 Luna and the same optimized retriever for the backend-orchestrated two-call flow and the provider-managed MCP flow. Controls SHALL include the provider migration baselines, optimized direct retrieval without selection, and deterministic MCP invocation with equivalent prepared inputs. The evaluation SHALL record catalog and workload revisions, provider and model, configuration, warm-up and repetition policy, concurrency, and the sample count per mode. Paired transport comparisons SHALL use the same prepared inputs and retrieval settings. Real-model runs SHALL follow the existing opt-in evaluation requirements.

#### Scenario: Compare transport
- **WHEN** identical validated queries pass through direct retrieval and MCP retrieval in the same environment
- **THEN** the report records transport overhead and compares ordered candidates

### Requirement: Stage and end-to-end measurement
The report SHALL include p50 and p95 end-to-end duration, error and timeout rates, stage durations for embedding, Qdrant, and MCP, and provider processing timings where exposed. It SHALL count actual outbound chat-provider, tool and embedding requests and distinguish unavailable timing fields from measured values. It SHALL report exclusions and size-filter requests separately, and report rejected and successful requests separately. It SHALL record both the latency of the ordinary storefront path and the proportion of requests that reach retrieval and produce non-empty results. Duration comparisons SHALL use comparable outcomes and show failures separately.

#### Scenario: Benchmark exclusions
- **WHEN** the workload includes requests with negative examples
- **THEN** the report shows whether request batching reduced embedding calls and retrieval latency in that cohort

### Requirement: Storefront release criteria
The model-driven MCP flow SHALL serve storefront search after rollout. Before switching traffic, the team SHALL record an acceptable p95 budget, demonstrate one actual outbound OpenAI request for the end-to-end MCP flow, and verify the existing search relevance acceptance criteria and the hard constraints in this change. The benchmark SHALL record p95 and error rates against the pre-change baseline and the accepted budget. A release SHALL meet the budget. A latency improvement claim SHALL require measured p95 below the baseline under equivalent load and verified relevance.

#### Scenario: MCP workflow exceeds the latency budget
- **WHEN** the bounded MCP workflow passes relevance criteria but exceeds the agreed p95 budget
- **THEN** the report records the regression and its stage breakdown and the MCP workflow requires further optimization before storefront rollout
