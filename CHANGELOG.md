# Changelog

## [1.4.0] - 2026-04-13

### Added

- **Rename** provider — rename methods, fields, and labels across workspace
  - Polymorphic-aware: renaming a method cascades to subclass overrides
  - Supports rename from both declaration and usage sites
  - `prepareRename` validation rejects `<init>`, `<clinit>`, and class names
- **Instruction hover** — opcode documentation with format, description, and operands (218 Dalvik opcodes)
- **Directive navigation** — hover and go-to-definition for directives inside method body (`.catch`, `.annotation`, `.implements`)
- MCP: validate `direction` parameter in type hierarchy tools

### Changed

- **Find References**: O(1) reverse index lookups (was O(n) full scan) — <1ms instead of ~5s for large codebases
- **Parsing**: micro-optimizations — SLL-only mode, fast-reject for non-smali files, bounded string pool
- **Completion**: class names now show full path as label (e.g., `com/example/MyActivity`) with simple name as detail
- Deduplicated document content storage (removed redundant buffers)

### Fixed

- Class completion double-L insertion bug (`LLcom/...` instead of `Lcom/...`)
- Class names with digit-starting path components (e.g., `Lcom/1foo/Bar;`) now parse correctly
- Field type class references extracted properly (e.g., hover on `.field` type works)
- `extractClassRefFromFieldType` no longer returns `null` for primitive arrays
- Cycle protection in `WorkspaceIndex` hierarchy traversal (prevents infinite loops)
- `Range.contains` off-by-one error for end-of-range positions
- BOM handling in parser (UTF-8 BOM stripped before parsing)
- HoverProvider position bugs and pattern matching (correct cursor resolution)
- Negative line positions in diagnostics (clamp to 0)
- Diagnostics now published on `didOpen`, not just `didChange`
- Dynamic project version in `ServerInfo` and shadow JAR (was hardcoded)
- Defensive null handling in MCP hover content extraction
- Parse failure reporting (errors propagated instead of silently swallowed)
- Eliminated double-parse on `didOpen` (parse once, reuse)

### Internal

- Dead code removal, unified helpers, fixed stale comments
- Comprehensive regression test suite for audit bug findings
- Architecture and design documentation (`DESIGN.md`)

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
