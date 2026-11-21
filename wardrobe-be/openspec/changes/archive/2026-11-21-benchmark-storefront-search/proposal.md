## Status

Deferred and unimplemented as of 2026-11-21 at the user's request. The implementation changes were reverted. All nine tasks remain incomplete, and no real-provider baseline or paired comparison was run. This archived change preserves the proposal for possible future work. Its delta requirements have not been synced to the active specifications.

## Why

Wardrobe, Please has judged search fixtures and a planned MCP search flow. We need a reproducible way to measure how the current two-call backend flow and the planned provider-managed MCP flow perform at the same load, with the same catalog and retrieval settings. We also need to measure which injection attempts succeed and how often the guardrail rejects legitimate searches.

## What changes

- Add a headless JMeter plan for the public search endpoint and a report that relates latency, throughput, failures and provider usage.
- Extend the existing judged evaluation to cover each turn through the HTTP endpoint and record where eligible products are lost.
- Add a versioned guardrail suite with benign controls, direct and multi-turn injection, and indirect injection through catalog descriptions.
- Reuse the seeded catalog. Add or correct catalog facts only when a required case lacks a fact-verified match, near miss or empty combination.
- Support measuring the current flow immediately. Add the paired comparison when the [MCP search flow](../../latency-first-mcp-catalog-search/proposal.md) and shared retrieval settings are available.

## Capabilities

### New capabilities

- `search-latency-evaluation` defines reproducible HTTP load measurement and cross-flow reporting.
- `search-guardrail-evaluation` defines adversarial and benign fixtures, observable security outcomes, and negative controls.

### Modified capabilities

- `search-relevance-evaluation` extends the existing judged suite and report with HTTP outcomes, per-turn judgments and paired stage diagnostics.

## Impact

The benchmark will use an isolated deployment and catalog collection. Planned artifacts include a JMeter plan, fixtures, a bounded evaluation report, and instructions for reproducing a run.
