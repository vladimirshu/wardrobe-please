## Why

Storefront search already uses one chat-model round trip before retrieval. It can reduce avoidable retrieval latency and improve hard-constraint matching while exposing the same catalog search to MCP clients. The storefront will use model-based candidate filtering. Its latency and relevance need to be measured on the actual catalog.

## Prerequisite

Complete and verify [migrate-to-openai-luna](../migrate-to-openai-luna/proposal.md) before implementing this change. It supplies the OpenAI Responses integration with GPT-6 Luna and low reasoning effort.

## What Changes

- Expose an authenticated, read-only MCP catalog search tool that reuses the storefront retriever.
- Extract explicit catalog sizes alongside price and apply both as Qdrant filters before ranking. Batch positive and negative embedding inputs into one request where supported.
- Use one backend request to GPT-6 Luna for the entire storefront workflow. OpenAI executes the model-requested MCP search and feeds the results back to the same workflow for selection before returning. Compare latency with the direct-search baselines.
- Align the archived filtering and model-budget requirements with the new storefront flow.

## Capabilities

### New Capabilities

- `mcp-catalog-search` provides typed, read-only candidate retrieval to authenticated clients.
- `search-latency-evaluation` establishes how to measure the direct and MCP paths.

### Modified Capabilities

- `query-analysis-guardrail` adds catalog size extraction and defines the model verdict before retrieval and deterministic validation at the MCP boundary.
- `product-vector-retrieval` adds exact size filtering and batches embeddings.
- `rag-product-filtering` defines direct response handling and optional model selection.
- `llm-cost-budget` bounds model calls by search mode.

## Impact

Wardrobe backend search, Qdrant catalog payload and index, deployment access controls, and the opt-in evaluation harness. The existing storefront route and response fields remain compatible. Search interpretation can include an optional size field.
