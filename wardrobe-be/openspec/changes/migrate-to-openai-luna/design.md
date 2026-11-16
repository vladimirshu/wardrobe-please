## Context

The backend uses DeepSeek for structured query analysis and OpenAI for embeddings. OpenAI documents that `gpt-6-luna` supports low reasoning effort, structured outputs, and remote MCP through the Responses API. [GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna)

## Goals

Switch chat to OpenAI Responses and verify it with the existing direct search before adding MCP. Preserve the search route, model verdict and suggestion handling, per-conversation context, and embedding configuration.

## Decisions

### Use the Responses API

Send `model: "gpt-6-luna"` and `reasoning: {"effort": "low"}` explicitly on the wire. Use a Responses-capable client compatible with the backend. Map the existing query-analysis JSON schema and validation behavior to structured Responses output. Ordinary search supplies no tools in this change. Carry conversation context from application memory and request `store: false`. Keep provider errors and incomplete outputs on the existing error path.

Responses output limits include reasoning tokens. Verify typical structured results fit the configured cap and keep the cap and existing retry bounds explicit. Measure token usage and latency before and after the switch. [OpenAI reasoning guide](https://developers.openai.com/api/docs/guides/reasoning)

### Keep provider configuration separate from embeddings

Use OpenAI chat credentials supplied via deployment configuration and keep the existing embedding model, dimensions, and Qdrant collection compatible. The two integrations may use separate scoped OpenAI credentials where needed. Update local examples and deployment documentation for the selected credential wiring. Avoid logging credentials and raw request or response bodies.

## Migration Plan

1. Capture direct-search timing on DeepSeek for later comparison.
2. Add the Responses integration and verify its request shape, structured output handling, conversation behavior, and failure paths with offline tests.
3. Verify actual GPT-6 Luna availability and a structured-response smoke test using the existing opt-in evaluation policy before switching runtime configuration. Compare direct search on GPT-6 Luna with the DeepSeek baseline.
4. Check deployment secret references and cost controls, including the repository-level AWS backend hosting plan if that change is still active. Prepare rollback to the previous deployment revision.
5. Complete and verify this change before implementing storefront MCP orchestration.
