## ADDED Requirements

### Requirement: Exact catalog size filter
The retriever SHALL apply a requested size by exact membership in a normalized keyword-array payload and combine it with price before similarity ranking and before any lowest-price ordering. The catalog revision and readiness checks SHALL cover the new payload and its index. A request that requires size SHALL fail explicitly when exact filtering is unavailable.

#### Scenario: Size and price on ordinary and cheapest-first paths
- **WHEN** a request asks for size M under €150 with either relevance or lowest-price ordering
- **THEN** every returned product has an exact M payload token and costs less than €150
- **AND** lowest-price ordering applies only to the relevant eligible candidates

#### Scenario: Missing exact size metadata
- **WHEN** the verified catalog revision is unavailable and the request includes a size
- **THEN** retrieval reports that the filtered search is unavailable

### Requirement: Batch query embeddings
The retriever SHALL submit the positive query and validated negative examples in a single batch when the configured embedding provider supports it and SHALL preserve the mapping from each input to its vector. A batch error or incomplete vector response SHALL be treated as a retrieval failure.

#### Scenario: Search with exclusions
- **WHEN** a search contains a positive query and two negative examples and provider batching is enabled
- **THEN** a single logical embedding request contains all three inputs and Qdrant receives the corresponding positive and negative vectors
