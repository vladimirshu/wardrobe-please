## ADDED Requirements

### Requirement: GPT-6 Luna chat configuration
The backend SHALL use OpenAI `POST /v1/responses` for normal storefront chat. Each request SHALL explicitly send `model: "gpt-6-luna"` and `reasoning.effort: "low"`. The backend SHALL obtain credentials through runtime configuration and SHALL NOT automatically fall back to another model or provider.

#### Scenario: Inspect outbound request
- **WHEN** an admitted storefront request requires query analysis
- **THEN** its chat request targets the Responses API with GPT-6 Luna and low reasoning effort

### Requirement: Structured query analysis compatibility
The provider integration SHALL preserve the existing analysis schema, verdict handling, application conversation history, validation behavior, and configured output and retry bounds. It SHALL use `store: false` and SHALL distinguish a completed structured response from an incomplete, failed, or malformed result.

#### Scenario: Conversational refinement
- **WHEN** a customer updates their budget in a follow-up
- **THEN** the request includes the relevant conversation context and the validated interpretation reflects the updated budget

#### Scenario: Incomplete provider response
- **WHEN** the provider terminates without a valid analysis
- **THEN** the backend follows the bounded failure handling for query analysis and does not retrieve using partial output
