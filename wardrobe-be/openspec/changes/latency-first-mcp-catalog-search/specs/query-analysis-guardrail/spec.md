## MODIFIED Requirements

### Requirement: Single query-analysis LLM call
For an admitted request that passes input validation, the backend SHALL make exactly one chat-provider request. Within that request the model SHALL check safety and apparel relevance, rewrite allowed searches, extract retrieval constraints, request the MCP search when allowed, and use its results to produce the final response. The backend SHALL NOT initiate a follow-up request or retry.

#### Scenario: Allowed search
- **WHEN** a valid apparel request is allowed
- **THEN** one provider request covers analysis, the MCP invocation, and the final product selection

#### Scenario: Rejected search
- **WHEN** the model rejects a request as unsafe, out of scope, or unclear
- **THEN** it returns a structured rejection within the same provider request and no MCP retrieval occurs

### Requirement: Search parameters mapped to retrieval
The model SHALL supply typed MCP arguments containing the rewritten search text, optional price and exact size constraints, negative examples and lowest-price ordering. The MCP server SHALL validate the arguments before retrieval and the backend SHALL check the final interpretation against the actual tool arguments.

#### Scenario: Price and size constraints
- **WHEN** the analysis extracts size M and price `LESS_THAN_OR_EQUAL 150`
- **THEN** the MCP retriever applies both constraints and the returned interpretation agrees with the tool arguments

#### Scenario: No filters supplied
- **WHEN** the analysis provides only a rewritten query
- **THEN** MCP retrieval applies no price or size constraint, no negatives and relevance ordering

### Requirement: Query analysis output validation
The system SHALL validate the final provider result and the actual retrieval arguments and SHALL respond with HTTP 500 and error `Illegal assistant response` when an allowed result has a blank or over-long `searchQuery`, any text field that is not plain text, more than 5 suggested prompts or more than 10 negative examples, or a non-positive price value.

#### Scenario: Blank search query
- **WHEN** the analysis returns `allowed = true` and `searchQuery = ""`
- **THEN** the response is HTTP 500 with error `Illegal assistant response`

#### Scenario: Negative price
- **WHEN** the analysis returns price constraint `LESS_THAN -5`
- **THEN** the response is HTTP 500 with error `Illegal assistant response`

#### Scenario: Invalid final provider result
- **WHEN** the final provider response fails schema validation or contains inconsistent retrieval evidence
- **THEN** the response is HTTP 500 with error `Illegal assistant response` and the backend sends no follow-up model request

## REMOVED Requirements

### Requirement: No tool calls for query analysis
**Reason**: The provider executes the catalog tool when the model permits the search.
**Migration**: Allow only the provider-managed catalog tool and validate invocation at the MCP boundary.

## ADDED Requirements

### Requirement: Bounded model-requested retrieval
The provider request SHALL expose only the read-only catalog search tool. The model SHALL assess whether to permit retrieval before invoking the tool. The MCP server SHALL execute at most one retrieval for a storefront request and reject invalid, unauthorized, expired, or repeated invocations before embedding or Qdrant calls. The backend SHALL reject a final response whose verdict conflicts with the observed tool activity. Existing input validation, admission, rejected-turn handling, and conversation isolation SHALL apply. Tool traffic and candidates SHALL be excluded from stored conversation turns.

#### Scenario: Repeated tool invocation
- **WHEN** the provider requests a second catalog retrieval for the same admitted search
- **THEN** the MCP server rejects the repeat before retrieval and the backend does not return products from an inconsistent response

### Requirement: Explicit catalog size extraction
The model SHALL extract one explicit size in the catalog convention when present, retain it across applicable conversational refinements, and ask for clarification through the existing rejection response if conversion or disambiguation is needed. The successful response SHALL include the validated size in the interpretation when present.

#### Scenario: Size persists across a refinement
- **WHEN** a customer searches for size M jackets and then narrows their budget
- **THEN** the next retrieval and returned interpretation include size M and the updated price constraint
