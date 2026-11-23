## Why

The storefront stops waiting after 30 seconds, while provider timeouts and independent retry layers can keep a single backend search running much longer. A shared execution budget must bound the complete search and its downstream work.

## What Changes

- Keep the frontend search timeout at 30 seconds.
- Bound one backend search to 25 seconds total, including downstream calls, retry waits and schema correction.
- Limit each provider attempt to 5 seconds or the remaining deadline, whichever is shorter.
- Allow at most three extra downstream attempts across the entire search, not three per stage or per SDK layer.
- Cancel local in-flight work on expiry, stop further calls/retries and return a generic HTTP 504 without logging prompt or provider payloads.

## Capabilities

### New Capabilities
- `search-execution-budget`: a shared deadline, attempt limits and retry budget for POST search.

### Modified Capabilities
<!-- Existing search validation, admission, ranking and frontend availability behavior are preserved. -->

## Impact

Backend orchestration, provider/Qdrant calls, exception handling and planned CloudFront timeout verification. The frontend already has a 30-second timeout. Startup catalog import, health/wake probes and browsing are outside this change. No new service or monitoring infrastructure.
