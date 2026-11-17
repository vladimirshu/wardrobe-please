## Context

The backend uses DeepSeek for structured query analysis. GPT-6 Luna supports low reasoning effort and structured outputs through the Responses API. [GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna)

## Goals

Switch storefront chat to GPT-6 Luna and verify structured query analysis, verdict and suggestion handling, and conversation context through the existing search route.

## Decisions

### Use the Responses API

Send `model: "gpt-6-luna"` and `reasoning: {"effort": "low"}` explicitly on the wire. Use a Responses-capable client compatible with the backend. Map the existing query-analysis JSON schema and validation behavior to structured Responses output. Carry conversation context from application memory and request `store: false`. Keep provider errors and incomplete outputs on the existing error path.

Responses output limits include reasoning tokens. Verify typical structured results fit the configured cap and keep the cap and existing retry bounds explicit. Measure token usage and latency before and after the switch. [OpenAI reasoning guide](https://developers.openai.com/api/docs/guides/reasoning)

### Configure chat credentials

Supply OpenAI chat credentials through runtime configuration. Update local examples and deployment documentation for the chat credentials. Avoid logging credentials and raw request or response bodies.

## Migration Plan

1. Capture direct-search timing on DeepSeek for later comparison.
2. Add the Responses integration and verify its request shape, structured output handling, conversation behavior, and failure paths with offline tests.
3. Verify actual GPT-6 Luna availability and a structured-response smoke test using the existing opt-in evaluation policy before switching runtime configuration. 
