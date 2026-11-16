## Why

Wardrobe needs a chat provider that supports low reasoning effort and can later execute remote MCP tools within a single provider request. GPT-6 Luna on the OpenAI Responses API supports both capabilities.

## What Changes

- Replace DeepSeek for storefront chat with the OpenAI Responses API using `gpt-6-luna` and `reasoning.effort: "low"`.
- Preserve the current structured query analysis, conversation handling, direct retrieval, and storefront API through the provider migration.
- Update local and deployment credential wiring and validate the provider change independently before implementing model-driven search.

## Capabilities

### New Capabilities

- `chat-model-provider` defines the chat provider, model, reasoning effort, and request contract for structured query analysis.

### Modified Capabilities

None.

## Impact

Backend chat integration, runtime configuration, local and deployment credential wiring, cost controls, and provider tests. OpenAI embeddings continue to use `text-embedding-3-small`. This change provides the prerequisite for [latency-first-mcp-catalog-search](../latency-first-mcp-catalog-search/proposal.md).
