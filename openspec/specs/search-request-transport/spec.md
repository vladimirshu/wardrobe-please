# search-request-transport Specification

## Purpose
Define JSON-body transport for storefront search, preserving existing backend behavior and specifying forwarding requirements for the planned AWS edge.

## Requirements

### Requirement: JSON search request
The system SHALL expose `POST /api/v1/search` consuming `application/json` with required `message` and `conversationId` fields and optional boolean `clearMemory`, defaulting to false. The storefront SHALL send these fields only in the JSON body, without search query parameters. The backend SHALL bind search fields only from that body.

#### Scenario: Search submission
- **WHEN** a visitor submits a search
- **THEN** the storefront sends POST to `/api/v1/search` with a JSON body containing the message, conversation ID and clear-memory flag

#### Scenario: Default clear-memory flag
- **WHEN** a valid JSON body omits `clearMemory`
- **THEN** the existing conversation is not cleared before search

#### Scenario: Query parameters do not supply search fields
- **WHEN** the request contains a message or conversation ID only in query parameters, missing from the JSON body
- **THEN** the response is 400 and no search is performed

### Requirement: Existing search behavior preserved
The POST endpoint SHALL preserve existing message normalization, field validation, conversation handling, response representations and search outcome status codes, client rate limits and daily budget. Invalid JSON, missing bodies and field validation failures SHALL return 400 without consuming search quota or invoking downstream services. Unsupported request content types SHALL return 415.

#### Scenario: Valid search
- **WHEN** a valid POST request is accepted
- **THEN** it follows the existing search pipeline and returns the existing success or rejection representation

#### Scenario: Invalid request
- **WHEN** the JSON body is absent, malformed or fails existing field validation
- **THEN** the response is 400 with no quota consumed and no LLM, embedding or vector-store call

#### Scenario: Rate limit reached
- **WHEN** the client exceeds its search limit and submits a valid POST search
- **THEN** the response remains 429 with the existing code and Retry-After behavior

#### Scenario: Unsupported content type
- **WHEN** the client submits a search as `text/plain`
- **THEN** the response is 415 and no search is performed

### Requirement: No GET search compatibility
The backend SHALL NOT process searches through GET or redirect GET search requests to POST.

#### Scenario: Old method
- **WHEN** a client calls `GET /api/v1/search`
- **THEN** the response is 405 and no search is performed

### Requirement: Edge supports JSON POST search
When the AWS edge is implemented, its backend behavior SHALL allow POST search requests, forward their JSON body and Content-Type unchanged, and SHALL NOT cache search responses. Existing search WAF protection, if enabled, SHALL apply to POST search. This change SHALL NOT introduce request-body logging or analytics tracking; traffic visibility SHALL follow the built-in-only AWS edge specification.

#### Scenario: Search through CloudFront
- **WHEN** the storefront sends a valid JSON POST search through the deployed distribution
- **THEN** the backend receives its body and Content-Type and the response is not cached

#### Scenario: No analytics dependency
- **WHEN** POST search is deployed through CloudFront
- **THEN** it works without access-log delivery, campaign tracking or custom analytics infrastructure

