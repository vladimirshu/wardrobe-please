## MODIFIED Requirements

### Requirement: Bounded LLM cost per accepted search
The backend SHALL initiate exactly one chat-provider request per admitted storefront search and SHALL configure a maximum output token budget and deadline for that request. It SHALL disable automatic retries and follow-up chat requests. The MCP server SHALL execute at most one catalog retrieval per storefront request. Embedding calls SHALL execute only within the permitted catalog retrieval and remain subject to its deadline.

#### Scenario: Invalid or incomplete provider response
- **WHEN** the Responses request times out or returns an incomplete or invalid response
- **THEN** the backend returns the corresponding failure without sending a second provider request

#### Scenario: Full search workflow
- **WHEN** the model analyzes the search, invokes MCP, receives candidates and selects products
- **THEN** the backend makes one actual outbound chat request with the configured token and time bounds
