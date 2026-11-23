# Repository instructions

## Use IntelliJ IDEA MCP when available

Prefer the IntelliJ IDEA / JetBrains MCP server for IDE-aware navigation, symbol and usage lookup, refactoring, inspections, and running project configurations when its tools are available. Confirm that the IDE has this repository open before using it. If the server is unavailable in the current session, state that limitation and use filesystem and CLI tools as needed.

## Keep architecture documentation current

For every project change, check whether it affects documented architecture. If it changes system boundaries, component responsibilities, request flows, validation or error paths, external integrations, or deployment, update all affected diagrams and architecture docs in the same change. Start with [the architecture documentation](docs/architecture.md) and review the relevant OpenSpec design documents. Before finishing, verify that the docs reflect the resulting behavior and clearly label any planned work.
