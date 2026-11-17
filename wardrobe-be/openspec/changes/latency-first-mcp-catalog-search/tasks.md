## 1. Verify prerequisite and provider workflow

- [ ] 1.1 Confirm that [migrate-to-openai-luna](../archive/2026-11-17-migrate-to-openai-luna/proposal.md) is implemented and verified. Use its DeepSeek and GPT-6 Luna direct-search baselines.
- [ ] 1.2 Verify with an opt-in smoke test that one GPT-6 Luna Responses request at low reasoning effort can execute an authenticated MCP fixture and return both structured output and sufficient evidence to validate selected SKUs.

## 2. Shared retrieval and MCP interface

- [ ] 2.1 Extract a shared typed retrieval interface and batch positive and negative embedding inputs. Verify vector mapping, query ordering and provider error handling.
- [ ] 2.2 Write and verify normalized size payload arrays and their index. Include the payload schema version in the catalog revision calculation and cover legacy records and interrupted imports.
- [ ] 2.3 Apply combined price and size filters before similarity and the lowest-price prefetch. Cover exact membership, missing payload and a valid search with no eligible candidates.
- [ ] 2.4 Expose the Streamable HTTP tool with scoped authentication, per-client admission and retrieval deadlines. Verify discovery, typed errors, and the per-search retrieval limit through the selected provider transport.

## 3. Storefront orchestration

- [ ] 3.1 Implement the Responses request with the single allowed MCP tool and instructions for safety, apparel relevance, query preparation and SKU selection. Preserve conversational refinements and interpretation.
- [ ] 3.2 Validate the final verdict, tool evidence, interpretation, matched SKU membership, uniqueness and exact constraints. Build product cards from verified catalog data in retrieval order.
- [ ] 3.3 Enforce one outbound chat request and its deadline even on malformed output or provider/tool failures. Verify refused requests skip retrieval and that tool failure is distinct from a successful empty result.
- [ ] 3.4 Verify injected instructions in candidate text cannot trigger extra retrieval and that raw tool traffic and candidate facts stay out of conversation history and diagnostic logs.

## 4. Evaluation and rollout

- [ ] 4.1 Compare the existing direct-search baselines, optimized direct retrieval, equivalent direct and MCP retrieval, and the complete OpenAI-managed MCP flow. Record latency, errors, relevance, and actual outbound request counts.
- [ ] 4.2 Record the agreed storefront p95 budget and verify latency, security, and relevance acceptance in an isolated deployment.
- [ ] 4.3 Document catalog readiness, rollout to the single-request MCP flow, and operator rollback to direct search.
