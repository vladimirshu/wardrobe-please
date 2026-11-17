## Context

The backend uses DeepSeek for structured query analysis. GPT-6 Luna supports low reasoning effort and structured outputs through Chat Completions for this tool-free query analysis. [GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna)

## Goals

Switch storefront chat to GPT-6 Luna and verify structured query analysis, verdict and suggestion handling, and conversation context through the existing search route.

## Decisions

### Select the chat provider in YAML

`spring.ai.model.chat` selects the built-in Spring AI chat model and the matching query-analysis strategy injected into `AiConfig`. OpenAI uses strict schema output. DeepSeek uses JSON mode and receives the schema in its system prompt. Both use the same local schema validation, bounded schema retry, and conversation memory. OpenAI continues to supply embeddings for either chat provider.

Configure `model`, `reasoning-effort`, `store`, `timeout`, `max-retries`, and `max-completion-tokens` in `application.yml` under `spring.ai.openai.chat`. The query-analysis client supplies the strict schema derived from its Java response type. Carry conversation context from application memory and keep provider errors, truncated completions, and refusals on the existing error path.

The completion-token cap includes reasoning tokens. Verify typical structured results fit the configured cap and keep the cap and existing retry bounds explicit. Measure token usage and latency before and after the switch. [OpenAI reasoning guide](https://developers.openai.com/api/docs/guides/reasoning)

### Configure chat credentials

Use the runtime `OPENAI_API_KEY` for embeddings and OpenAI chat. Supply `DEEPSEEK_API_KEY` when selecting DeepSeek chat. Avoid logging credentials and raw request or response bodies.

## Migration Plan

1. Capture direct-search timing on DeepSeek for later comparison.
2. Configure Spring AI Chat Completions and verify its request shape, structured output handling, conversation behavior, and failure paths with offline tests.
3. Verify actual GPT-6 Luna availability and a structured-response smoke test using the existing opt-in evaluation policy before switching deployed traffic.
