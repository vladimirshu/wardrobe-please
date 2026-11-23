## Context

A search performs query analysis, query/optional negative-example embedding, Qdrant retrieval and optional product refinement. Both chat stages can repeat for schema correction. Current configuration has 15-second provider timeouts and independent SDK/Spring retry settings. Multiplying retries across layers can exceed the browser's existing 30-second limit.

## Decisions

1. Start a monotonic 25-second deadline when backend handling of POST search begins. Carry the same budget through every stage; no stage resets it. The frontend retains its 30-second timeout, leaving a nominal 5-second allowance for network and response handling, not a guarantee against arbitrary network delays.
2. For each provider attempt use a total attempt timeout of `min(5 seconds, remaining search budget)`, including connection and response handling. Qdrant calls also respect the remaining search budget. Do not apply a search-only limit to startup catalog import.
3. Share a maximum of three extra downstream attempts across the whole request. Transient transport retries and model schema-correction repeats consume this same allowance; initial calls for distinct stages do not. Preserve existing per-stage schema repeat limits. Disable independent hidden retry loops or route them through this budget, including SDK, Spring AI and schema advisors. Retry only eligible transient errors or existing schema-correction cases, never validation/admission rejection. Backoff and Retry-After must fit the remaining budget; otherwise fail without waiting past the deadline. Admission is charged once per search.
4. Expiry cancels locally controlled HTTP/gRPC calls and pending retry waits, prevents later stages and answers HTTP 504 with a generic error message and no payload details. Returning a timed-out Future while its worker continues is insufficient. Existing conversation and admission semantics stay unchanged; this change does not add rollback of already completed stages. Cancelling a local call cannot guarantee that a remote provider stops processing or charging for an already accepted request.
5. Keep existing frontend generic 5xx handling and availability recovery. A search timeout alone does not prove the backend is stopped; a subsequent successful readiness check keeps the availability gate open. No automatic replay of a timed-out search.

## Verification and deployment

Use local provider/Qdrant doubles and a controllable clock where possible to verify delayed responses, shared retries, schema repeats and cancellation without live provider charges. Verify effective timeout/retry values with Spring AI 2.0.2 and its actual request options; changing YAML alone is not proof. The CloudFront response timeout must allow the backend's 25-second deadline response to reach the browser within its 30-second window under normal network conditions. Keep fast origin connection failure and uncached API errors. Deploy backend behavior before verifying the complete edge flow.

This is planned behavior; runtime settings and cancellation are not implemented by this specification update.
