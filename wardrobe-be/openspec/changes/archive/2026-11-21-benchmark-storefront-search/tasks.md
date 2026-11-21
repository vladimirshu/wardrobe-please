## Status

Deferred and unimplemented as of 2026-11-21 at the user's request. The implementation changes were reverted. All nine tasks remain incomplete, and no real-provider baseline or paired comparison was run. This archived change preserves the proposal for possible future work. Its delta requirements have not been synced to the active specifications.

## 1. Workload and expected results

- [ ] 1.1 Audit the existing catalog and 44 judged cases. Add paired boundaries, exact-size and price-order cases, and per-turn judgments wherever coverage is missing.
- [ ] 1.2 Extend catalog facts and regenerate seed data only for coverage gaps. Freeze the catalog revision and review all affected judgments.
- [ ] 1.3 Build the versioned direct and indirect injection fixtures, benign controls and isolated poisoned variant reusing an existing SKU.

## 2. Harness and verification

- [ ] 2.1 Implement the CLI JMeter plan with serial, warm steady and bounded stepped-load profiles. Document fixture selection, outcome labels, budgets and JTL output.
- [ ] 2.2 Extend the judged evaluation to use actual HTTP results and correlate bounded stage evidence, call counts and model usage in the isolated deployment.
- [ ] 2.3 Implement security assertions for response fields, synthetic canaries, selected SKUs and permitted downstream actions. Verify report denominators and inconclusive outcomes with deterministic cases.

## 3. Baseline and paired comparison

- [ ] 3.1 Run the opt-in current-flow baseline and publish the internal run report. Use it to agree the storefront p95 budget and target load.
- [ ] 3.2 When the planned MCP flow and common retrieval settings are available, run matched load, relevance and guardrail suites. Include the direct retrieval control where available.
- [ ] 3.3 Produce a comparison report with sample counts, observed regressions, representative queries and unresolved gaps.
