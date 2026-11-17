# Provider migration verification

The DeepSeek baseline and Luna smoke test are pending live opt-in. Run these checks against an isolated local test catalog.

## Fixed workload

| Setting | Value |
| --- | --- |
| DeepSeek baseline | Commit `4375b7377a213f065e457ae64a615b1a480cc4ac`, `deepseek-flash`, thinking disabled |
| OpenAI comparison | Record the migration commit, `gpt-6-luna`, low reasoning effort |
| Versioned workload | [seed-search-cases.json](../../../src/test/resources/relevance/seed-search-cases.json), schema v1, 44 cases and 51 turns |
| Workload SHA-256 | `804fc13fe56e6f6b7d971bf1e084c5b107585bb7fe6357e7e3b5e1a60f8f4959` |
| Catalog | [apparel_catalog.csv](../../../src/main/resources/sampledata/apparel_catalog.csv), 200 rows |
| Catalog SHA-256 | `eeca785fccea5301a9830213043b01727a8ecd2746e813f3408b60ba9fb455f6` |
| Retrieval | `text-embedding-3-small`, 1536 dimensions, top K 10, threshold 0.15 |
| Run policy | Two serial passes per provider, fresh conversation ID per case and pass, zero search warm-ups, same seeded catalog |

Disable provider transport retries for these runs and leave the existing schema retry enabled. Budget up to 204 chat sends per provider (102 turns plus up to one schema retry per turn), with a configured cap of 500 generated tokens per send. Budget up to 408 search embedding requests across both providers, plus the initial 200 catalog embedding inputs. Estimate input tokens before opting in, using the system prompt, workload and conversation window. The separate one-turn smoke test adds up to two chat sends and two search embedding requests.

## Run the DeepSeek baseline first

1. Create a separate checkout of the baseline revision. From the current repository root, start an isolated Qdrant instance with `docker compose -p wardrobe-provider-eval up -d qdrant`. Use free local ports 8080, 6333 and 6334 and a fresh evaluation volume.
2. With `OPENAI_API_KEY` and `DEEPSEEK_API_KEY` exported, run the following from the baseline checkout's `wardrobe-be`. Wait for `GET /api/v1/health` to report `UP` before timing requests.

   ```sh
   env -u QDRANT_HOST -u QDRANT_PORT -u QDRANT_USE_TLS -u QDRANT_API_KEY \
     SERVER_ADDRESS=127.0.0.1 SPRING_AI_RETRY_MAXATTEMPTS=0 \
     SPRING_AI_OPENAI_CHAT_MAXRETRIES=0 SPRING_AI_OPENAI_EMBEDDING_MAXRETRIES=0 \
     ECOM_LIMITS_PERMINUTE=120 ECOM_LIMITS_PERHOUR=120 ECOM_LIMITS_DAILYBUDGET=150 \
     ./gradlew bootRun
   ```

3. Send each turn to `GET /api/v1/search` as `message` and `conversationId`. Use the same ID through a case's turns. Record per-turn HTTP status, normalized interpretation, ordered SKUs and end-to-end time. Record timestamps, configuration, provider/model, catalog/workload revisions, p50/p95 and sample counts for successful requests, and error, timeout and empty-result counts. Record the query-analysis time and provider usage when available. Use the [relevance evaluation spec](../../specs/search-relevance-evaluation/spec.md) for result judgments.

## Run the Luna smoke test

Stop the baseline backend. From the migration checkout, use the same startup command with `SPRING_AI_MODEL_CHAT=openai` and `OPENAI_API_KEY` authorized for Chat Completions and Embeddings. Keep the same isolated Qdrant. Send one request after readiness.

```sh
curl -sS -G http://127.0.0.1:8080/api/v1/search \
  --data-urlencode 'message=waterproof hiking jacket under 150 euro, no wool or down, cheapest first' \
  --data-urlencode "conversationId=$(uuidgen)" \
  -w '\nHTTP %{http_code}\n'
```

Record whether the account can use `gpt-6-luna`, the HTTP status, whether the validated interpretation retained the €150 boundary, exclusions and cheapest-first ordering, and input, output and total tokens from the `Query analysis token usage` log. If the completion is truncated, record the outcome and any available usage before changing the cap. Repeat the fixed workload on Luna for the direct-search comparison when running the dependent latency evaluation.

## Deployment credentials

The [AWS backend hosting plan](../../../../openspec/changes/aws-backend-host/design.md) supplies `OPENAI_API_KEY` from `/ecom/prod/openai-api-key`. Verify the deployed key's Chat Completions and Embeddings access before switching traffic. For rollback, retain the previous backend image and its DeepSeek secret reference.
