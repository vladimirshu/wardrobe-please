# MCP search verification and rollout

## Status

Implementation uses the actual Spring backend for both storefront search and MCP.
There is no separate MCP fixture service. Separate Luna migration verification is not
required. Normal traffic remains on direct search because both MCP flags default false.

Local tests use the actual HTTP endpoints, isolated Testcontainers Qdrant, deterministic
embeddings and a simulated OpenAI Responses server that calls the backend over HTTP.
No real provider requests or deployment have been made for this change.

Run from `wardrobe-be` with Java 25 and Docker:

```sh
env -u QDRANT_HOST -u QDRANT_PORT -u QDRANT_USE_TLS -u QDRANT_API_KEY \
  ./gradlew test
```

The environment cleanup prevents inherited cloud TLS/credential settings affecting
local fixtures. Do not point the tests at a production catalog.

Coverage includes authenticated discovery without consuming retrieval; argument/schema
bounds; one batch with indexed embedding responses; exact size and price before
ranking and cheapest-first prefetch; catalog payload upgrade/repair and index creation;
one actual outbound Responses send; refusal before retrieval; unique retrieved SKU
membership; ordering; conversation refinements; empty success versus dependency failure;
repeated callbacks; injected candidate instructions; provider errors, invalid results,
redirect prevention and cancellation. Conversation/log assertions exclude callback
credentials, candidate facts, selection and raw tool traffic.

The provider in these tests is simulated. They cannot establish how the real provider
interprets instructions, serializes MCP traces, forwards bearer authorization or chooses
tools. Live acceptance below remains required before switching traffic.

## Local verification result (2026-11-24)

The full backend Gradle suite passed: 312 tests, 4 skipped, zero failures/errors.
The skipped tests require the optional DeepSeek runtime. IntelliJ build passed and
IDE inspections of MCP transport, orchestration and capability storage found no errors.
Strict OpenSpec validation and `git diff --check` passed. No live provider calls,
deployment or production traffic switch occurred.

## Isolated real-provider acceptance (pending)

1. Use an isolated, single-process backend deployment and Qdrant collection with the test catalog;
   retain the previous direct-search artifact. Keep ordinary storefront traffic on
   direct mode. Use the actual backend MCP endpoint, not a separate test server.
2. Publish it through the existing HTTPS CloudFront `/api/v1/*` behavior. Verify POST,
   Authorization, Accept, Content-Type and MCP-Protocol-Version reach the private
   backend unchanged and responses are uncached. Keep provider and request-body debug
   logging off. Do not enable a wake/traffic path that changes the operating policy.
3. Set `ECOM_MCP_ENABLED=true`, `ECOM_MCP_STOREFRONT=true`,
   `ECOM_MCP_PUBLICURL=https://<isolated-distribution>/api/v1/mcp`,
   `SPRING_AI_MODEL_CHAT=openai` and the existing OpenAI credential. Optional external
   client secrets are unnecessary for storefront callbacks. Confirm the configured
   GPT-6 Luna model, low reasoning and 1200-token cap. Host preparation currently
   injects the existing provider credentials; inject these non-secret MCP settings
   through the isolated deployment configuration before restart.
4. Wait for catalog readiness. Confirm a keyword `sizeTokens` index and payload values
   on every expected SKU, with the revision including `size-tokens-v1`. Interrupted
   imports must recover before size-filtered traffic is admitted.
5. Send `POST /api/v1/search` with an application/json body, for example
   `{"message":"waterproof jacket size M under 200 euro, no wool, cheapest first","conversationId":"mcp-smoke-unique-id"}`.
   Then refine the same conversation with `{"message":"only under 100 euro","conversationId":"mcp-smoke-unique-id"}`.
   Also check an unrelated request, an unavailable size, and a controlled dependency
   failure. Use a fresh conversation ID for independent cases.
6. Record timestamps, deployment/model/catalog revisions, sanitized HTTP outcomes,
   ordered SKUs and interpretation. Verify from an isolated test HTTP counter (or
   provider-side request accounting) exactly one actual outbound Responses request per
   admitted search. Discovery must not charge another search; callback failure must
   not trigger retries or direct fallback. No raw authorization, candidate descriptions,
   prompts or provider bodies should be retained in diagnostic logs.
7. Confirm the live provider calls this backend successfully with its search token,
   final interpretation agrees with recorded arguments, and final products come from
   the same successful retrieval. The server-owned capability record supplies trusted
   evidence; emitted `mcp_call` arguments must agree. Validate material exclusions,
   price/size, order and token/deadline limits. A timeout returns 504; malformed or
   inconsistent final output returns 500; a valid empty retrieval returns 200.
8. Record results here and mark tasks 1.1/4.2 only after successful live checks. Task
   2.4's selected-provider verification also remains pending until this run passes.

## Rollout and rollback

Enable normal storefront MCP mode only after deterministic and live checks pass.
Do not enable it merely because compilation/local tests pass. Reuse the existing
backend CloudFront route; do not provision a separate MCP server.

For operator rollback set `ECOM_MCP_STOREFRONT=false` and restart with the retained
direct-search provider configuration. Set `ECOM_MCP_ENABLED=false` too to remove the
endpoint, or leave it enabled for separately authorized clients. Size payloads/index
are additive, so direct search and catalog mapping remain compatible. No request is
automatically replayed during rollback; failed customer input remains available to retry.

Material checking deliberately favors omission: a recognized excluded material mentioned
anywhere in title/description rejects selection, even if negated. A selected record
must state material facts for these exclusions. The check is not a composition parser
or a substitute for accurate catalog facts; live quality acceptance must assess recall.
