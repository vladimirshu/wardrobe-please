# Wardrobe, Please search request flow

This Mermaid sequence diagram uses C4 container boundaries to show a search request from the storefront to the Spring Boot API and its dependencies.

**Catalog indexing happens at startup.** If the current catalog revision needs import or repair, the backend embeds the products and stores their vectors in Qdrant before reporting ready. A complete catalog revision reuses its stored vectors. Each accepted search embeds the interpreted query and any negative examples at request time.

```mermaid
sequenceDiagram
    box rgb(225, 237, 252) Wardrobe, Please
        participant Shop as React storefront
        participant API as Spring Boot API
    end
    box rgb(233, 237, 241) Backend dependencies
        participant Chat as Chat provider
        participant Embed as OpenAI Embeddings
        participant DB as Qdrant
    end

    Shop->>API: GET /api/v1/search
    API->>API: Normalize and validate the message and conversation ID
    break Invalid input
        API-->>Shop: 400 Bad Request
    end

    API->>API: Check per-client rate limits and daily search budget
    break Search limit reached
        API-->>Shop: 429 Too Many Requests
    end

    Note over Chat: GPT-6 Luna by default<br/>DeepSeek Flash when configured
    API->>Chat: Analyze apparel intent using the message and recent conversation
    Chat-->>API: Structured decision, search terms and constraints

    API->>API: Validate the response schema
    break Invalid schema after retry
        API-->>Shop: 500 Analysis error
    end

    break Query analysis rejects the request
        API-->>Shop: 422 Suggested prompts
    end

    API->>API: Validate search terms and constraints
    break Invalid search terms or constraints
        API-->>Shop: 500 Analysis error
    end

    API->>Embed: Create query and optional negative-example embeddings
    Embed-->>API: Query and optional negative-example vectors
    API->>DB: Compare query vectors with stored product vectors and apply price filters
    DB-->>API: Ranked product matches
    opt Matches found
        API->>Chat: Refine candidates using the interpreted query and constraints
        Chat-->>API: Structured selection of SKUs
        break Invalid or incomplete selection
            API-->>Shop: 500 Illegal assistant response
        end
        API->>API: Keep selected catalog records in retrieval order and cap results
    end
    API-->>Shop: 200 Products and search interpretation
```

Qdrant can run locally or in Qdrant Cloud. OpenAI supplies embeddings with either chat provider. Catalog browsing and readiness checks use Qdrant directly.

Product refinement logs the candidate count, response time across any schema retry, model, and finish reason at INFO. DEBUG adds token usage and the final validated SKUs.

The planned [provider-managed MCP search flow](../wardrobe-be/openspec/changes/latency-first-mcp-catalog-search/design.md), [AWS edge](../openspec/changes/aws-edge-hosting/design.md), and [wake-up flow](../openspec/changes/aws-control-plane/design.md) are documented in OpenSpec.

The planned [storefront search benchmark](../wardrobe-be/openspec/changes/benchmark-storefront-search/design.md) uses this endpoint to evaluate the current flow and compare it with MCP once both are available.
