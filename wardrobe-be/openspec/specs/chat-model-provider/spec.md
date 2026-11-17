# chat-model-provider Specification

## Purpose
Define how the storefront selects a chat provider and preserves structured query analysis across supported providers.

## Requirements

### Requirement: Storefront chat provider configuration
The backend SHALL use `spring.ai.model.chat` in application YAML to select the built-in Spring AI chat model and query-analysis strategy for `openai` or `deepseek`. OpenAI SHALL be the default. Each default OpenAI request SHALL send `model: "gpt-6-luna"` and `reasoning_effort: "low"` to `POST /v1/chat/completions`. The backend SHALL obtain credentials through runtime configuration and SHALL NOT automatically fall back to another model or provider.

#### Scenario: Inspect outbound request
- **WHEN** an admitted storefront request requires query analysis with the default OpenAI configuration
- **THEN** its chat request targets the Chat Completions API with GPT-6 Luna and low reasoning effort

#### Scenario: Select DeepSeek
- **WHEN** the backend starts with `spring.ai.model.chat: deepseek` and DeepSeek credentials
- **THEN** query analysis uses DeepSeek Chat Completions with JSON output and receives the schema in its prompt
- **AND** embeddings still use OpenAI

### Requirement: Structured query analysis compatibility
Both providers SHALL preserve the existing analysis schema, verdict handling, application conversation history, validation behavior, and configured output and retry bounds. OpenAI SHALL send a strict `response_format` JSON schema and `store: false`. Both providers SHALL reject truncated completions, refusals, and invalid results.

#### Scenario: Conversational refinement
- **WHEN** a customer updates their budget in a follow-up
- **THEN** the request includes the relevant conversation context and the validated interpretation reflects the updated budget

#### Scenario: Incomplete provider response
- **WHEN** the provider terminates without a valid analysis
- **THEN** the backend follows the bounded failure handling for query analysis and does not retrieve using partial output
