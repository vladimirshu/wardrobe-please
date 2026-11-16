## 1. Integrate the provider

- [ ] 1.1 Record the current DeepSeek direct-search baseline with the versioned workload and opt-in run policy.
- [ ] 1.2 Implement the OpenAI Responses client and configure GPT-6 Luna, low reasoning effort, structured output, and bounded timeouts and output tokens.
- [ ] 1.3 Preserve analysis validation, existing bounded retries, per-conversation context, and error mapping. Verify the outbound request and exceptional responses with offline fixtures.
- [ ] 1.4 Update local and deployment credential wiring and cost-control documentation. Verify the existing OpenAI embedding configuration and reconcile active deployment planning that still requires DeepSeek credentials.

## 2. Verify the provider migration

- [ ] 2.1 Run an opt-in GPT-6 Luna smoke test for structured query analysis at low reasoning effort and record actual account access and token usage.
- [ ] 2.2 Run direct-search regression and relevance checks and record GPT-6 Luna direct-search timing for the dependent MCP change.
- [ ] 2.3 Verify deployment configuration and the rollback path. Mark the provider change complete before starting the dependent MCP work.
