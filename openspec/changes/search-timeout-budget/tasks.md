## 1. Backend budget

- [ ] 1.1 Introduce a monotonic 25-second search budget and propagate it through every search stage without changing startup import or health/wake timeouts
- [ ] 1.2 Enforce a total provider-attempt timeout of at most 5 seconds/remaining budget, including effective model options and embedding calls; make Qdrant obey the remaining deadline
- [ ] 1.3 Share at most three additional attempts across transport and schema retries, preserving existing per-stage schema limits; prevent independent SDK/Spring/advisor retries from multiplying attempts and include backoff in the deadline
- [ ] 1.4 Cancel local HTTP/gRPC work and retry waits on expiry, prevent further stages and return a generic 504; do not merely detach a still-running worker

## 2. Verification and documentation

- [ ] 2.1 Use local dependency doubles to check the 25-second deadline, 5-second attempts, shared retry exhaustion, schema correction, cancellation and unchanged single admission accounting
- [ ] 2.2 Verify the existing frontend 30-second timeout and generic error/recovery flow preserve input and do not replay searches; confirm a healthy service is not presented as stopped after a search timeout
- [ ] 2.3 Verify CloudFront permits the backend deadline response without caching it; record effective retry/deadline configuration in the backend README and update architecture documentation when implementation lands
