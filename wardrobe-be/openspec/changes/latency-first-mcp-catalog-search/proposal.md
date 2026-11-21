## Why

Storefront search uses one chat request to analyze the query and a second to filter the retrieved candidates when there are matches. This planned change moves orchestration to the provider, exposes catalog retrieval to MCP clients, and improves hard-constraint matching.

## Prerequisite

Complete and verify [migrate-to-openai-luna](../archive/2026-11-17-migrate-to-openai-luna/proposal.md) before implementing this change. It supplies the GPT-6 Luna configuration used by storefront search. This change adds the Responses integration for provider-managed MCP.

## What Changes

- Expose an authenticated, read-only MCP catalog search tool that reuses the storefront retriever.
- Extract explicit catalog sizes alongside price and apply both as Qdrant filters before ranking. Batch positive and negative embedding inputs into one request where supported.
- Use one backend request to GPT-6 Luna for the entire storefront workflow. OpenAI executes the model-requested MCP search and feeds the results back to the same workflow for selection before returning.
- Align the archived filtering and model-budget requirements with the new storefront flow.

## Capabilities

### New Capabilities

- `mcp-catalog-search` provides typed, read-only candidate retrieval to authenticated clients.

### Modified Capabilities

- `query-analysis-guardrail` adds catalog size extraction and defines the model verdict before retrieval and deterministic validation at the MCP boundary.
- `product-vector-retrieval` adds exact size filtering and batches embeddings.
- `rag-product-filtering` defines candidate selection within the provider-managed workflow and deterministic response validation.
- `llm-cost-budget` bounds provider requests, output tokens and retrieval calls for storefront search.

## Impact

Wardrobe backend search, Qdrant catalog payload and index, and deployment access controls. The existing storefront route and response fields remain compatible. Search interpretation can include an optional size field.
