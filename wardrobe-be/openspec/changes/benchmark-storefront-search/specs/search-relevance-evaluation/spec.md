## ADDED Requirements

### Requirement: Paired HTTP evaluation and turn-level judgments

The evaluation SHALL reuse the current judged suite and exercise the actual `GET /api/v1/search` endpoint. Every evaluated turn SHALL have a frozen expected outcome, constraints and relevant SKU judgments where applicable. The report SHALL include HTTP status, interpretation, ordered candidate and final SKU evidence where available, and actual versus expected constraints for each turn. Existing relevance gates SHALL apply, and candidate Hit@K SHALL use the configured K. The report SHALL include eligible-item coverage, recall where judgments are exhaustive, and the outcome changes across predeclared repeat runs.

#### Scenario: Follow-up changes one constraint
- **WHEN** a shopper changes the requested size while retaining the existing budget and material exclusion
- **THEN** the report checks all three constraints on that turn and identifies any dropped, retained incorrectly or violated constraint

#### Scenario: Search result is missing
- **WHEN** a judged eligible SKU is absent from the response
- **THEN** the evidence distinguishes an interpretation error, a retrieval miss, a selection drop or a dependency failure, and labels cases with insufficient evidence

### Requirement: Seed catalog coverage audit

The evaluation SHALL start with the existing seeded catalog and authored facts. It SHALL audit coverage for price boundary, exact size, lowest-price ordering, exclusions, localized and follow-up queries, and successful empty results. A catalog change SHALL identify the specific missing coverage and regenerate the seed from consistent authoring facts. Each compared flow SHALL use the same frozen catalog and judged suite.

#### Scenario: Existing catalog covers a new boundary case
- **WHEN** an existing product costs exactly the requested threshold and the catalog contains suitable nearby alternatives
- **THEN** the suite adds paired strict and inclusive price queries with pre-judged outcomes using those existing products
