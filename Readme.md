# Wardrobe, Please

## Project Overview
An AI-powered clothing discovery demo. Shoppers can describe what they need in their own words, even if they have no specific garment in mind. For example, “I need something warm for a rainy walk, under €100.” They can then refine the results with a follow-up like “Something lighter, please,” or browse the catalog directly.

The assistant interprets the request, retrieves catalog candidates from Qdrant using semantic search and price constraints, and asks the model to select the matches. The storefront shows the interpreted search and product details. The catalog is seeded from CSV.

## User workflow

1. The customer submits a natural-language search. The backend normalizes and validates the input.
2. The configured chat model checks and interprets the request, using conversation history for follow-ups. GPT-6 Luna is the default. Rejected requests receive suggested prompts.
3. Qdrant retrieves relevant catalog candidates using the interpreted query, price constraints, and negative examples.
4. If there are candidates, the chat model selects matching SKUs. The backend returns the selected catalog records in retrieval order, along with the validated search interpretation.
5. The customer reviews the results and product details, then can refine the search or start a new one.

## Security guardrails

- Search input and conversation IDs are validated before processing.
- An LLM-based guardrail checks whether requests are relevant to apparel search and rejects requests it identifies as irrelevant, contradictory, or seeking internal information. Its structured output is also validated.
- Per-client rate limits, a daily search budget, and LLM output-token limits help control abuse and costs. The rate limits and budget are in memory and reset on restart; see the [backend README](wardrobe-be/README.md) for limitations and OpenAI spending controls.

The demo does not include customer accounts, authentication, cart, checkout, or order management. LLM-based guardrails can make mistakes and do not guarantee security.

## Repository Structure

See the [search request flow](docs/architecture.md) for validation and calls to external services.

| Directory | Project | Specifications |
|---|---|---|
| `wardrobe-be/` | Spring Boot API (Gradle, Java): natural language search, guardrails, vector search | `wardrobe-be/openspec/` |
| `wardrobe-fe/` | Storefront web app (Vite, React, TypeScript) | `wardrobe-fe/openspec/` |

`docker-compose.yml` in the repository root starts the full stack: the Spring Boot
backend, the frontend (served by nginx), and Qdrant.

## Running Locally

### Full stack with Docker Compose

```bash
cp .env.example .env   # then set OPENAI_API_KEY
docker compose up --build
```

Compose uses `wardrobe-please` as its project name. If you previously ran the stack as `ecom-assistant`, use `docker compose -p ecom-assistant up --build` to reuse its existing Qdrant volume.

| Service  | URL                          |
|----------|------------------------------|
| frontend | http://localhost:3000        |
| backend  | http://localhost:8080        |
| Qdrant   | http://localhost:6333 (REST) / :6334 (gRPC) |

### Individual services (development)

```bash
docker compose up -d qdrant                     # vector DB only
cd wardrobe-be && OPENAI_API_KEY=... ./gradlew bootRun   # API on http://localhost:8080
cd wardrobe-fe && npm install && npm run dev   # storefront on http://localhost:3000
```

### Using a Qdrant Cloud cluster instead of local Qdrant

The backend connects to local Qdrant by default. For a managed Qdrant Cloud
cluster, pass these variables to the backend process or container. The local
Docker Compose stack uses its bundled Qdrant service.

| Variable          | Default     | Meaning                                          |
|-------------------|-------------|--------------------------------------------------|
| `QDRANT_HOST`     | `localhost` | Cluster hostname only, without a scheme or port |
| `QDRANT_PORT`     | `6334`      | gRPC port (Qdrant Cloud serves gRPC on 6334)     |
| `QDRANT_USE_TLS`  | `false`     | set `true` for Qdrant Cloud                      |
| `QDRANT_API_KEY`  | *(empty)*   | Cluster database API key with catalog write access |

For example, running the backend directly against Qdrant Cloud:

```bash
cd wardrobe-be
QDRANT_HOST=cluster-id.region.provider.cloud.qdrant.io \
QDRANT_PORT=6334 \
QDRANT_USE_TLS=true \
QDRANT_API_KEY=... \
OPENAI_API_KEY=... \
./gradlew bootRun
```

The backend checks the catalog on every startup and skips re-embedding when
the current revision is already present. Qdrant suspends free clusters after
one week of inactivity and deletes them after four weeks if they stay inactive.
Reactivate a suspended cluster in the Qdrant Cloud console. For a deleted
cluster, configure a replacement and restart the backend to recreate the
collection and rebuild the catalog from the bundled CSV.
[Qdrant Cloud free cluster lifecycle](https://qdrant.tech/documentation/cloud/create-cluster/#free-clusters)
