## Status

Deferred and unimplemented as of 2026-11-21 at the user's request. The implementation changes were reverted. All nine tasks remain incomplete, and no real-provider baseline or paired comparison was run. This archived change preserves the proposal for possible future work. Its delta requirements have not been synced to the active specifications.

## Comparison

The primary comparison is the backend-orchestrated GPT-6 Luna flow (analysis, shared retrieval, selection) versus the GPT-6 Luna Responses workflow (analysis, provider-managed MCP retrieval, selection). Use the same model and reasoning effort, catalog and index revision, embedding model and batching, exact size and price semantics, candidate and output limits, workload, deployment region and load profile. Record endpoint and prompt revisions, token caps, retry policies and other differences that the APIs require. Run modes alternately in matched blocks with fresh conversation IDs and the same predeclared warm-up policy. If retrieval improvements are unavailable in the two-call flow, report the current baseline separately and withhold the matched comparison.

Use the previous direct retrieval without selection as a control when available, to measure the effect of selection. Keep historical provider runs as context.

## Load measurement

Run JMeter in CLI mode against `GET /api/v1/search` using fixture IDs and stable request labels. A profile covers warm-up, serial baseline, steady load and bounded load steps. Record offered and completed requests per second, concurrency, timeouts, rate limits, and latency per load level and outcome. Set the initial rates and durations before running, based on the allowed budget. Measure the baseline before agreeing on a storefront p95 budget and target load. Record both app admission throttling and upstream throttling. Increase test deployment quotas as needed for the declared traffic profile.

Use request-correlated timings and aggregate usage for analysis, embeddings, Qdrant, refinement and MCP where observable. Count actual outbound sends, including retries, and estimate provider cost per 1,000 successful searches from measured usage and a dated rate sheet. If provider timings or billed usage are unavailable, mark the missing data. If throughput flattens, use queue depth, worker and connection pool utilization, CPU and memory where available to locate saturation. A stubbed dependency run can help diagnose backend capacity independently of provider limits or measure recovery after an injected timeout. Live warm-search timings start after readiness. Report cold startup separately if measured.

## Relevance and catalog

Use the existing 200-product catalog, authoring facts and 44-case suite. Audit the coverage before editing data. Prioritize exact price boundaries, size matches and near misses, lowest-price ordering, material exclusions, paraphrases, multilingual cases, true empty results, and cases where a follow-up retains or replaces constraints. Keep the catalog and judgments fixed across compared runs. If coverage requires a catalog change, update authoring facts, regenerate the seed and recheck affected judgments as one revision before measuring.

Judge each turn from known product facts and explicit intent. Use the HTTP contract for statuses and interpretation and capture candidate evidence from the actual retriever. Compare candidate Hit@K at the configured K with final Hit@3, precision and coverage. Use recall where the eligible set is exhaustive. Diagnose misses by checking whether the relevant product was missing from candidates or dropped at selection. Use nDCG@5 only where graded judgments exist and ranking is a meaningful signal. Check requested price order directly. Repeat a fixed sample of cases to measure outcome stability.

## Guardrail evaluation

Submit all guardrail cases through `GET /api/v1/search`. For syntactically valid direct attacks that the policy rejects, expect HTTP 422 with safe suggested prompts and verify that the request caused no embeddings or catalog retrieval. Exercise ordinary benign, ambiguous and invalid-input cases as controls. A benign search that reaches a poisoned product description can return HTTP 200 if it ignores the injected instruction and preserves the legitimate search constraints. Reuse an existing SKU in a separate isolated catalog variant for this test and record whether the poisoned candidate actually reached the model.

Each attack needs an observable objective such as revealing a synthetic canary, forcing a prohibited SKU, broadening a filter or triggering retrieval for a rejected search. Judge response fields, selected products and downstream effects from the storefront request trace. Count a failed HTTP request without enough evidence as inconclusive. Test direct and indirect variants with repeated real-model runs, and report observed successes and sample counts for the fixed suite.

Sources: [JMeter CLI and dashboard](https://jmeter.apache.org/usermanual/generating-dashboard.html), [OWASP LLM01 Prompt Injection](https://genai.owasp.org/llmrisk/llm01-prompt-injection/).
