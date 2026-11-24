## Context

`QueryAnalyzer` combines safety, apparel relevance, conversational rewrite, price extraction and negative examples in one chat completion. Qdrant applies the price constraint during retrieval. When there are candidates, the backend sends a second chat request to select SKUs and returns the matching catalog records in retrieval order. The retriever currently embeds the positive query and negative examples in separate calls. Catalog sizes currently sit in a comma-separated payload string.

The existing [migrate-to-openai-luna](../archive/2026-11-17-migrate-to-openai-luna/proposal.md) implementation supplies the provider configuration; separate migration verification is not a prerequisite. This change adds a Responses integration with one provider-managed MCP tool for the MCP search flow. OpenAI can execute the tool and resume generation inside that request. [OpenAI remote MCP](https://developers.openai.com/api/docs/guides/tools-connectors-mcp)

## Goals

Implement storefront search through one outbound chat-model request per admitted search, with provider-managed MCP retrieval and candidate selection, validated hard constraints, and reusable catalog retrieval.

## Decisions

### Keep one retrieval implementation and one provider request

Reuse one typed retrieval service and expose it through the read-only `search_catalog` MCP tool. Use authenticated Streamable HTTP with provider connectivity verified before rollout. Configure the OpenAI request to allow automatic execution only for this tool. The model owns the safety and apparel-domain decision, rewrite, structured constraint extraction, tool invocation, and candidate selection. The backend owns admission and the final deterministic checks.

```text
Customer → Backend input and admission checks
                  ↓ one POST /v1/responses
           GPT-6 Luna safety, relevance, rewrite and constraints
                  ↓ on allow
           OpenAI → search_catalog MCP → embeddings + filtered Qdrant
                  ↓ candidates returned to the provider
           GPT-6 Luna selects SKUs → final structured response
                  ↓
           Backend validates evidence and maps products → Customer
```

A rejected request terminates inside the same provider request with a refusal or clarification and no search. The application makes zero follow-up model requests, including on malformed output or timeout.

### Apply exact constraints before ranking

Keep the existing strict/inclusive price operators and lowest-price ordering. Add an optional exact size token. Use normalized keyword-array payload values derived from the catalog while retaining the CSV field used by existing product mapping. Match array elements exactly and combine size and price with AND in both the ordinary query and the lowest-price prefetch. Accept case-insensitive alphabetic sizes and numeric sizes expressed in the catalog convention. Reject ambiguous sizes that require an unverified conversion using the existing clarification response. A size match means the size is listed for the product. Keep negative examples as ranking signals and verify that selected products satisfy material exclusions using catalog facts. Batch the positive query and all negatives using the same embedding model and bind vectors by their input positions. [Qdrant payload filters](https://qdrant.tech/documentation/search/filtering/)

### Validate at the MCP and backend boundaries

The prompt instructs the model to reject unsafe, off-topic or ambiguous requests before issuing a tool call. That semantic judgment is model-owned. Independently authenticate the MCP caller and validate typed arguments, admission limits and deadlines before embedding or database work. Bind storefront tool credentials to the current search and enforce one retrieval at the MCP boundary, even if the provider requests it again. Tool discovery does not consume the retrieval allowance. Give the model only the bounded catalog facts required for selection and treat product descriptions as untrusted data.

Return either a structured refusal or a successful interpretation with selected SKUs. Verify with the live provider that its MCP result trace exposes the actual arguments and retrieved candidates. Use that evidence, or a server-owned result record bound to the same search, to verify success, interpretation, SKU membership and uniqueness, exact price and size, and catalog facts before responding. Reject inconsistent traces and dependency errors. Keep the retrieval order, including explicit price ordering. A successful tool call with zero candidates produces a successful empty result. Store the existing validated conversation summary and exclude tool traffic and candidate content from chat history.

## Migration Plan

1. Build on the existing GPT-6 Luna configuration without requiring separate migration verification. Verify the Responses API and authenticated MCP tool workflow with an opt-in smoke test before rollout; this live check does not block local implementation.
2. Add the normalized size payload and index. Change the catalog revision calculation to include the payload schema version, verify the new fields across the active catalog, and enable size filtering after readiness passes. Requests that require size filtering fail explicitly while the new payload is unavailable. Legacy records remain readable by existing product mapping.
3. Enable provider connectivity to MCP with scoped authentication and verify the one-request flow and candidate evidence. Verify the complete search workflow in an isolated deployment, including rejection before retrieval, conversation refinements, exact constraints, candidate selection and dependency failures. Switch storefront traffic after these functional checks pass. Retain the previous direct path during rollout for operator rollback. A failed MCP request surfaces an error during normal operation.

## Open Questions

Choose the deployment connectivity and authentication mechanism during implementation. Verify that the selected transport exposes enough trusted tool evidence for final validation.
