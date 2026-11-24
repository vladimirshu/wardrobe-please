## 1. Verify provider-managed MCP workflow

Separate verification of the existing Luna migration is not required. The live MCP check below is required before rollout and does not block local implementation.

- [ ] 1.1 Verify with an opt-in smoke test that one GPT-6 Luna Responses request at low reasoning effort can execute the actual backend’s authenticated MCP endpoint using an isolated test catalog and return both structured output and sufficient evidence to validate selected SKUs.

## 2. Shared retrieval and MCP interface

- [x] 2.1 Extract a shared typed retrieval interface and batch positive and negative embedding inputs. Verify vector mapping, query ordering and provider error handling.
- [x] 2.2 Write and verify normalized size payload arrays and their index. Include the payload schema version in the catalog revision calculation and cover legacy records and interrupted imports.
- [x] 2.3 Apply combined price and size filters before similarity and the lowest-price prefetch. Cover exact membership, missing payload and a valid search with no eligible candidates.
- [ ] 2.4 Expose the Streamable HTTP tool with scoped authentication, per-client admission and retrieval deadlines. Verify discovery, typed errors, and the per-search retrieval limit through the selected provider transport. Local deterministic HTTP checks pass; live provider verification remains pending under 4.2.

## 3. Storefront orchestration

- [x] 3.1 Implement the Responses request with the single allowed MCP tool and instructions for safety, apparel relevance, query preparation and SKU selection. Preserve conversational refinements and interpretation.
- [x] 3.2 Validate the final verdict, tool evidence, interpretation, matched SKU membership, uniqueness and exact constraints. Build product cards from verified catalog data in retrieval order.
- [x] 3.3 Enforce one outbound chat request and its deadline even on malformed output or provider/tool failures. Verify refused requests skip retrieval and that tool failure is distinct from a successful empty result.
- [x] 3.4 Verify injected instructions in candidate text cannot trigger extra retrieval and that raw tool traffic and candidate facts stay out of conversation history and diagnostic logs.

## 4. Functional verification and rollout

- [x] 4.1 Verify the complete HTTP search workflow with deterministic integration tests covering allowed and rejected requests, conversation refinements, exact size and price filters, material exclusions, lowest-price ordering, empty results and dependency failures.
- [ ] 4.2 Run an opt-in real-provider smoke test in an isolated deployment to verify authenticated MCP execution, one outbound chat request, trusted candidate evidence and final response validation.
- [x] 4.3 Document catalog readiness, rollout to the single-request MCP flow, and operator rollback to direct search.

See [verification.md](verification.md) for local evidence and the remaining live rollout gate.
