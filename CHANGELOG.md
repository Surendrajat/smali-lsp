# Changelog

## [1.3.0] - 2026-04-06

### Added

- **Type Hierarchy** provider — supertypes/subtypes navigation (LSP + MCP)
- **Code Lens** provider — inline reference counts on methods and fields
- **Completion** provider — class names, method/field members, opcodes
- **Indexing progress** — reports progress to client via `WorkDoneProgress` notifications
- `--verbose` flag for file logging (off by default to avoid log files in CWD)

### Changed

- CLI uses subcommands (`lsp`, `mcp`) instead of flags (`--lsp`, `--mcp`)
- Skip redundant parsing and diagnostics on `textDocument/didOpen`

## [1.2.0] - 2026-04-04

### Added

- **Call Hierarchy** provider — incoming/outgoing call graphs
- **String Literal Search** — indexed `const-string` instructions for fast text search
- MCP tools: `smali_search_strings`, `smali_call_graph`, `smali_xref_summary`
- CLI: `--help` usage text and explicit `--lsp` mode flag
- DescriptorParser utility for type descriptor handling

### Changed

- Drop `-all` suffix from artifact name
- Clean up deprecated Gradle API usage

### Fixed

- MCP: better class search, remove count ambiguity
- CI: run all test categories, update MCP tool count assertions

## [1.1.0] - 2026-04-02

### Added

- `--version` flag to print build info
- **MCP Server** — 9 tools for AI agent integration (index, definition, search, stats, references, hover, diagnostics, symbols)

### Fixed

- Find references average performance threshold relaxed for real APKs
- Search symbols diagnostic test count for package-path matches
- Capped parallel test forks at 4, set 1g heap per worker
- Scanner: explicit UTF-8 charset when reading smali files
- MCP: fix OOM on re-index, missing line ranges in document symbols, package path search
- Limit coroutines in WorkspaceScanner

### Removed

- Dead InstructionParser and no-op `parseInstructionsInMethods`

## [1.0.0] - 2026-04-01

### Added

- **LSP Server** — go to definition, find references, hover, document/workspace symbols, diagnostics, label navigation
- **ANTLR4 Grammar** — full smali parser with SLL prediction mode
- **WorkspaceIndex** — thread-safe concurrent index with bidirectional class-URI maps, string interning
- **WorkspaceScanner** — parallel file processing with APKTool project detection
- Position-aware symbol extraction for all 41 Dalvik instruction types
- Inheritance-aware polymorphic reference matching
- SDK class detection and filtering
- CI/CD with GitHub Actions (build, test, release)
- Shadow JAR build (13MB, excluding build-time ANTLR4 tool)
- GPLv3 license
