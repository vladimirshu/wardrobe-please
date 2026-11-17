# Wardrobe, Please backend

Spring Boot service behind the storefront search API (`GET /api/v1/search`). See the
repository root `Readme.md` for the overall project and how to run it locally.

## Cost controls and operations

An admitted search calls the configured chat provider for query analysis. If analysis allows the request and Qdrant returns candidates, a second call selects matching SKUs. Both calls use the same provider; OpenAI GPT-6 Luna is the default.
Each call can retry invalid structured output once. The selected client also applies its configured transport retry limit.
Truncated or refused completions fail the search.
An allowed search uses the existing OpenAI `text-embedding-3-small` model for Qdrant.

To switch, set `spring.ai.model.chat` in `application.yml` to `openai` or `deepseek` and restart the backend. With Compose, rebuild the backend image after editing the YAML. Set `OPENAI_API_KEY` for embeddings and OpenAI chat. Set `DEEPSEEK_API_KEY` when using DeepSeek chat.

Configure the limits in `application.yml`:

| Property | Default | Meaning |
|---|---|---|
| `ecom.limits.per-minute` | 5 | accepted searches per client per minute |
| `ecom.limits.per-hour` | 20 | accepted searches per client per hour |
| `ecom.limits.daily-budget` | 300 | accepted searches per UTC day across all clients |
| `ecom.limits.trusted-proxy-hops` | 1 | trusted `X-Forwarded-For` entries, counted from the right |
| `spring.ai.openai.chat.max-completion-tokens` | 500 | maximum query-analysis completion tokens per attempt, including reasoning |
| `spring.ai.openai.chat.timeout` | 15s | chat request timeout |
| `spring.ai.openai.chat.max-retries` | 10 | SDK retries per attempt (up to 11 sends, including the initial send) |
| `spring.ai.deepseek.chat.max-tokens` | 500 | DeepSeek query-analysis completion token cap |
| `ecom.search.product-filter-max-output-tokens` | 300 | SKU selection completion token cap for either provider |
| `ecom.search.max-results` | 10 | maximum number of selected products returned |
| `spring.ai.retry.max-attempts` | 10 | DeepSeek transport retries per attempt (up to 11 sends, including the initial send) |

A rejected search returns HTTP 429 with a `Retry-After` header and a body
`{"code":"RATE_LIMITED"|"DAILY_BUDGET_EXHAUSTED","message":"..."}` (see `openapi.yaml`).

### In-memory limitation

The per-client rate-limit buckets and the daily-budget counter live in process memory
only. They reset to zero whenever the application restarts, and they are not shared
between instances. Repeated restarts can therefore raise the day's effective ceiling; the
per-client limits still apply after every restart. See the provider spending controls below.

### Required before the first public link (runbook step)

Configure spending controls for the dedicated OpenAI project used by the demo.
See [OpenAI project limits](https://help.openai.com/en/articles/9186755-managing-projects-in-the-api-platform)
and [prepaid billing](https://help.openai.com/en/articles/8264644-setting-up-and-managing-prepaid-api-billing).

Use the same `products` collection when changing chat providers. For the live checks,
see [provider migration verification](openspec/changes/archive/2026-11-17-migrate-to-openai-luna/verification.md).
