## ADDED Requirements

### Requirement: Authenticated catalog search tool
The backend SHALL expose one read-only `search_catalog` MCP tool through an explicitly enabled Streamable HTTP endpoint. Tool listing and invocation SHALL require an authorized client identity. Invocations SHALL be subject to per-client admission limits and a bounded deadline.

#### Scenario: Unauthorized client
- **WHEN** a client without valid authorization attempts to list or call the tool
- **THEN** access is denied before embedding or Qdrant work

#### Scenario: Tool disabled
- **WHEN** the endpoint is disabled in deployment configuration
- **THEN** the tool is unavailable and the backend rejects enabling storefront MCP mode

### Requirement: Typed retrieval contract
The tool SHALL accept a normalized search query, optional typed price constraint, optional exact catalog size, bounded negative examples, and a lowest-price flag. It SHALL validate schema and semantic bounds before retrieval, reject arbitrary filter expressions and unknown fields, and use the shared retrieval implementation and server-configured result cap.

#### Scenario: Valid filtered tool call
- **WHEN** a permitted client requests a jacket under €150 in size M
- **THEN** the tool returns an ordered, bounded candidate list whose products satisfy both filters

#### Scenario: Caller attempts to control retrieval
- **WHEN** the payload supplies a collection name, raw filter, or caller-selected result limit
- **THEN** the invocation fails before embedding or database work

### Requirement: Grounded tool response
The tool SHALL return structured catalog facts with candidate SKUs in retrieval order and SHALL distinguish a successful empty list from validation errors, admission rejection and dependency failures. The tool SHALL exclude secrets, internal diagnostics and conversation history.

#### Scenario: Vector store timeout
- **WHEN** Qdrant exceeds the retrieval deadline
- **THEN** the tool returns a dependency failure and the host does not interpret it as an empty catalog match

### Requirement: Storefront release criteria
The model-driven MCP flow SHALL serve storefront search after rollout. Before switching traffic, the team SHALL verify the workflow through deterministic integration tests and an opt-in real-provider smoke test in an isolated deployment. Verification SHALL cover authenticated MCP execution, exactly one actual outbound OpenAI request per admitted search, bounded retrieval, trusted candidate evidence, final response validation, conversation refinements, exact price and size constraints, material exclusions, lowest-price ordering, successful empty results and dependency failures.

#### Scenario: Verified MCP workflow
- **WHEN** the integration tests and real-provider smoke test confirm the complete workflow and its validation paths
- **THEN** the MCP workflow is ready for storefront rollout with documented catalog readiness and operator rollback

#### Scenario: Inconsistent tool evidence
- **WHEN** the real-provider smoke test cannot establish that selected SKUs came from the successful MCP retrieval for the same search
- **THEN** final response verification fails and storefront rollout remains pending until trusted evidence is available
