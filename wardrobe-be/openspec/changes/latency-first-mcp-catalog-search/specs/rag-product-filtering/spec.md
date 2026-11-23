## MODIFIED Requirements

### Requirement: Product filtering input
The provider SHALL pass the bounded MCP candidate facts to the model inside the original response workflow. The model SHALL treat catalog text as data, select products supported by the facts and the search intent, and exclude products with unknown facts required to confirm a hard constraint. It SHALL return an empty selection when there are no eligible candidates.

#### Scenario: Catalog contains instructions
- **WHEN** a candidate description contains text asking the selector to ignore search constraints or fetch another product
- **THEN** the model treats the text as untrusted catalog content and the MCP server executes no additional retrieval

### Requirement: Only retrieved SKUs are returned
The model SHALL return a structured list of selected SKUs. The backend SHALL validate that the list contains unique SKUs drawn from the eligible candidate set evidenced by the successful MCP result for the same request and build product responses from retrieved catalog facts. The backend SHALL recheck typed price and size constraints before returning selected products.

#### Scenario: Invented or duplicate SKU
- **WHEN** selection includes an unknown SKU or repeats a SKU
- **THEN** the backend returns an assistant-response failure with no products

### Requirement: Product filtering failure
The backend SHALL return the existing HTTP 500 `Illegal assistant response` for malformed or invalid selection output and SHALL distinguish catalog dependency failures from successful empty results.

#### Scenario: Invalid output
- **WHEN** the provider returns malformed selection output or the selected SKUs cannot be verified against the tool result
- **THEN** the backend returns HTTP 500 with no products and executes no further tool or model calls

### Requirement: End-to-end search response
For a successful search the backend SHALL return HTTP 200 with the existing `products`, `interpretation` and `suggestedPrompts` fields. The products SHALL be exactly the validated selection in retrieval order, or an empty list when a successful tool call yields no eligible selection. The interpretation SHALL come from validated analysis and `suggestedPrompts` SHALL be null.

#### Scenario: Full customer happy path
- **WHEN** a client calls `POST /api/v1/search` for a blue cotton jacket in size M under €200 with no wool
- **THEN** the backend analyzes the request, calls the catalog tool once with size and price constraints and wool as a negative example, validates the selection, and returns the selected catalog products and interpretation

#### Scenario: Legacy route
- **WHEN** a client calls `GET /search`
- **THEN** the response is HTTP 404 and no model or retrieval work occurs


## REMOVED Requirements

### Requirement: Two LLM calls per successful search
**Reason**: Analysis and model selection now execute within a single provider-managed Responses request.
**Migration**: Use the structured result and MCP evidence from that one response to validate the storefront result.
