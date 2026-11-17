## Why

Wardrobe is moving its storefront chat from DeepSeek to GPT-6 Luna with low reasoning effort.

## What Changes

- Replace DeepSeek for storefront chat with the OpenAI Responses API using `gpt-6-luna` and `reasoning.effort: "low"`.
- Preserve the current structured query analysis, conversation handling, direct retrieval, and storefront API through the provider migration.
- Update local and deployment chat credentials and verify the migration.

## Capabilities

### New Capabilities

- `chat-model-provider` defines the chat provider, model, reasoning effort, and request contract for structured query analysis.

### Modified Capabilities

None.

## Impact

Backend chat integration, runtime configuration, local and deployment chat credentials, and provider tests.
