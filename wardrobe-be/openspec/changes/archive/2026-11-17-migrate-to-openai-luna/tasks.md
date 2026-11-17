## 1. Integrate the provider

- [ ] 1.1 Record the current DeepSeek direct-search baseline with the versioned workload and opt-in run policy.
- [x] 1.2 Configure Spring AI’s OpenAI chat model and GPT-6 Luna, low reasoning effort, structured output, and bounded timeouts and output tokens.
- [x] 1.3 Preserve analysis validation, existing bounded retries, per-conversation context, and error mapping. Verify the outbound request and exceptional responses with offline fixtures.
- [x] 1.4 Update local and deployment chat credentials.
- [x] 1.5 Select OpenAI or DeepSeek through `spring.ai.model.chat`. Verify provider-specific request formats and shared analysis behavior offline.

## 2. Verify the provider migration

- [ ] 2.1 Run an opt-in GPT-6 Luna smoke test for structured query analysis at low reasoning effort and record actual account access and token usage.

Live run instructions and the pinned workload are in [verification.md](verification.md).
