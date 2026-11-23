## 1. Backend

- [x] 1.1 Replace search GET/model-attribute binding with POST/validated JSON-body binding using the existing request DTO; preserve normalization, optional clearMemory default, responses, admission and conversation logic
- [x] 1.2 Adapt existing integration tests to POST; cover missing/invalid JSON and fields (400), unsupported content type (415), GET rejection (405), body-only field binding, unchanged search outcomes and quota behavior without downstream calls on invalid requests

## 2. Frontend and edge

- [x] 2.1 Send the existing search request fields as JSON to the bare `/api/v1/search` path; adapt API tests, mocks and end-to-end request interceptors while preserving response handling, timeouts and cancellation
- [x] 2.2 Update the AWS edge proposal/design/tasks to require POST forwarding, Content-Type and body passthrough, caching disabled, and search WAF coverage; verify these when the infrastructure is deployed

## 3. Documentation and checks

- [x] 3.1 Update OpenAPI, current API examples, affected active backend/frontend specs and pending designs from GET to POST search; preserve archived history and mark remaining planned work clearly
- [x] 3.2 Update the architecture diagram when implemented; run affected backend/frontend tests and OpenSpec validation

## Verification

- Backend: `QDRANT_USE_TLS=false ./gradlew test --offline` passes. The local Qdrant test containers require TLS disabled; the inherited TLS setting caused the earlier startup failures.
- Frontend: 113 unit tests and 33 Playwright tests pass; production build and ESLint pass. Fixed a stale catalog-test import from the former backend directory so the complete frontend suite runs.
- Strict OpenSpec validation passes for this change, AWS edge hosting, the pending MCP change, and root/backend/frontend canonical specs. OpenAPI parses and exposes POST-only JSON search.
- AWS infrastructure is still planned. POST/body/Content-Type forwarding, uncached responses and WAF coverage are tracked in the AWS edge deployment checks and have not been verified against a deployed distribution.
