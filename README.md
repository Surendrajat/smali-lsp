# smali-lsp

A Language Server Protocol (LSP) server and built-in MCP server for [Smali](https://github.com/google/smali) — the bytecode language of Android apps.


## Features

- **Go to Definition** — jump from any instruction to its target (classes, methods, fields, labels)
- **Find References** — cross-file xrefs with inheritance-aware polymorphic matching
- **Hover** — method signatures, field types, class info, SDK class detection
- **Document Symbols** — class outline with methods and fields
- **Workspace Symbols** — fuzzy search across the entire codebase
- **Diagnostics** — syntax errors and unresolved class references
- **Label Navigation** — goto/if-*/switch targets within methods
- **All 41 Dalvik instruction types** supported (invoke-*, iget/iput/sget/sput, new-instance, check-cast, etc.)
- **MCP Server** - built in MCP Server for full semantic understanding of decompiled APKs for AI agents.

## Performance

Tested on real-world APKs (119K smali files, 118K classes, 560K methods):

| Operation | Avg Latency | Throughput |
|-----------|-------------|------------|
| Goto Definition | 0.26ms | 4,011 ops/sec |
| Parsing / Indexing | ~0.3ms/file | ~3,300 files/sec |
| Find References | <1s | — |
| Symbol Search | <1s | — |

- All queries (definition, references, symbols, hover) return in <1s after indexing
- 99.7% goto-definition coverage (tested on 300K+ methods)
- 100% parse success rate on 100K+ real smali files
- 18 KB memory per indexed file (string-interned)
- Indexed 119K smali files in ~30–36s (warm), ~40s (cold)
- Indexed 27K smali files in ~6s

## Building

Requires Java 17+ and Gradle 8.8+.

```bash
./gradlew shadowJar
```

Output: `build/libs/smali-lsp-all.jar`

## Usage

### LSP Server (for IDEs)

The server communicates over **stdio** in standard LSP protocol — no daemon, no port, just stdio:

```bash
java -jar smali-lsp-all.jar
```

Configure your editor's LSP client to launch this command for `.smali` files. The server will automatically index the workspace on startup.

<details>
<summary>Neovim (via nvim-lspconfig)</summary>

```lua
local lspconfig = require('lspconfig')
local configs = require('lspconfig.configs')

if not configs.smali_lsp then
  configs.smali_lsp = {
    default_config = {
      cmd = { 'java', '-jar', '/path/to/smali-lsp-all.jar' },
      filetypes = { 'smali' },
      root_dir = function(fname)
        return lspconfig.util.root_pattern('AndroidManifest.xml', 'apktool.yml', '.git')(fname)
      end,
    },
  }
end

lspconfig.smali_lsp.setup {}
```
</details>

<details>
<summary>Helix</summary>

```toml
# ~/.config/helix/languages.toml
[[language]]
name = "smali"
scope = "source.smali"
file-types = ["smali"]
language-servers = ["smali-lsp"]

[language-server.smali-lsp]
command = "java"
args = ["-jar", "/path/to/smali-lsp-all.jar"]
```
</details>

<details>
<summary>Emacs (eglot)</summary>

```elisp
(with-eval-after-load 'eglot
  (add-to-list 'eglot-server-programs
               '(smali-mode . ("java" "-jar" "/path/to/smali-lsp-all.jar"))))
```
</details>

<details>
<summary>VS Code (WIP)</summary>

Extension not yet published. Use the MCP server (below) for VS Code + AI agent workflows.
</details>

### MCP Server (for AI agents)

```bash
java -jar smali-lsp-all.jar --mcp
```

Runs as an [MCP](https://modelcontextprotocol.io/) server over stdio, exposing smali analysis tools to AI agents (Claude, Cursor, etc.).

Add to your MCP config (`.vscode/mcp.json`,`claude_desktop_config.json`, Cursor settings, etc.):

```json
{
  "mcpServers": {
    "smali-mcp": {
      "command": "java",
      "args": ["-jar", "/path/to/smali-lsp-all.jar", "--mcp"]
    }
  }
}
```

<details>
<summary>Available tools</summary>
`smali_index`, `smali_find_definition`, `smali_search_symbols`, `smali_get_stats`, `smali_find_references`, `smali_hover`, `smali_diagnostics`, `smali_document_symbols`
</details>

## Architecture

```
ANTLR4 Grammar → SmaliParser → ASTBuilder → WorkspaceIndex → LSP Providers
                                                ↑
                                    WorkspaceScanner (parallel)
```

- **Parser**: ANTLR4 with SLL prediction mode (no ambiguities in smali grammar)
- **Index**: Thread-safe `ConcurrentHashMap` with O(1) lookups and bidirectional class-URI maps
- **Providers**: Position-aware symbol extraction — correctly resolves which of multiple symbols on a line the cursor targets
- **Scanner**: Parallel file processing with smart APKTool project detection

## Running Tests

```bash
# All tests
./gradlew test

# By category
./gradlew test --tests "*.unit.*"
./gradlew test --tests "*.integration.*"
./gradlew test --tests "*.regression.*"
./gradlew test --tests "*.performance.*"
```

## License

Copyright (C) 2026 Surendrajat

This project is licensed under the [GNU General Public License v3.0](LICENSE), except files under `src/main/antlr/` which are MIT licensed (see header in each file).

## Credits

- @psygate for https://github.com/psygate/smali-antlr4-grammar
