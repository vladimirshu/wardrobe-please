# search-execution-budget Specification

## Purpose
Bound storefront search execution with a shared deadline, provider-attempt limits, shared retries and cancellation.

## Requirements

### Requirement: Shared search deadline
The frontend SHALL retain a total search timeout of 30 seconds. Backend POST search handling SHALL use one monotonic deadline of 25 seconds shared by validation/admission, analysis, embeddings, retrieval, refinement, retries and schema repeats. Individual stages SHALL NOT reset it. Startup indexing, health/wake probes and browsing SHALL NOT inherit this search deadline.

#### Scenario: Several slow stages
- **WHEN** earlier stages consume most of the backend budget
- **THEN** later stages receive only the remaining time and the complete search does not receive a fresh 25-second allowance

### Requirement: Bounded attempts and shared retries
Each provider attempt SHALL have a total timeout of at most 5 seconds and no more than the remaining search budget. Qdrant operations SHALL respect the remaining deadline. At most three additional downstream attempts SHALL be permitted across the complete search, including transport retries and schema-correction repeats. Independent retry layers SHALL NOT multiply this allowance. Existing stricter per-stage repeat limits SHALL remain. Retry delays SHALL fit within the deadline and validation/admission rejection SHALL NOT be retried.

#### Scenario: Retry budget exhausted across stages
- **WHEN** three additional attempts have already occurred in earlier stages
- **THEN** no later stage, SDK or schema advisor starts another retry

#### Scenario: Less than five seconds remain
- **WHEN** a provider attempt starts with two seconds left
- **THEN** its timeout is at most two seconds and its retries cannot extend the overall deadline

### Requirement: Cancellation and timeout response
On search deadline expiry the backend SHALL cancel locally controlled in-flight work and retry waits, SHALL start no additional downstream call, and SHALL return HTTP 504 with a generic error response without prompt or provider payloads. A timeout SHALL NOT automatically replay the search or charge admission again.

#### Scenario: Slow or stuck dependency
- **WHEN** a dependency does not finish within the remaining search budget
- **THEN** local work is cancelled, no later pipeline stage starts and the backend returns 504 within its request budget

#### Scenario: Search timeout with a healthy backend
- **WHEN** the frontend receives 504 and its existing availability recovery confirms readiness
- **THEN** it shows the ordinary search error, preserves the draft and does not replay the request automatically
