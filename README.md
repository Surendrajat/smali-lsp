# smali-lsp

A Language Server Protocol (LSP) server with built-in MCP server for [Smali](https://github.com/google/smali) — the bytecode language of Android apps.

## Features

### LSP Server (for IDEs)

- **Go to Definition** — jump from any instruction to its target (classes, methods, fields, labels)
- **Find References** — cross-file xrefs with inheritance-aware polymorphic matching
- **Hover** — method signatures, field types, class info, SDK class detection
- **Call Hierarchy** — incoming/outgoing call graphs for any method
- **Type Hierarchy** — supertypes/subtypes navigation for classes
- **Code Lens** — inline reference counts on methods and fields
- **Completion** — class names, method/field members, opcodes
- **Rename** — rename methods, fields, and labels across workspace
- **Document Symbols** — class outline with methods and fields
- **Workspace Symbols** — fuzzy search across the entire codebase
- **Diagnostics** — syntax errors and unresolved class references
- **Label Navigation** — goto/if-\*/switch targets within methods
- **All 41 Dalvik instruction types** supported (invoke-\*, iget/iput/sget/sput, new-instance, check-cast, etc.)

### MCP Server (for AI agents)

Built-in [MCP](https://modelcontextprotocol.io) server for full semantic understanding of decompiled APKs by AI agents — various tools covering indexing, navigation, search, call graphs, and cross-references.

<details>
<summary>Available tools</summary>

| Tool                     | Description                                                                      |
| ------------------------ | -------------------------------------------------------------------------------- |
| `smali_index`            | Index a directory of smali files                                                 |
| `smali_find_definition`  | Go to definition for a symbol at a position                                      |
| `smali_search_symbols`   | Fuzzy search across classes, methods, fields                                     |
| `smali_get_stats`        | Index statistics (class/method/field/string counts)                              |
| `smali_find_references`  | Find all references to a symbol                                                  |
| `smali_hover`            | Get hover info (signatures, types, class details)                                |
| `smali_diagnostics`      | Compute diagnostics for a file                                                   |
| `smali_document_symbols` | Get document outline (classes, methods, fields)                                  |
| `smali_search_strings`   | Search const-string literals by substring                                        |
| `smali_call_graph`       | Incoming/outgoing call graph for a method                                        |
| `smali_xref_summary`     | Full cross-reference report (subclasses, implementors, callers, field accessors) |
| `smali_type_hierarchy`   | Supertypes and subtypes for a class (inheritance chain)                          |

</details>


## Usage

### LSP Server

The server communicates over **stdio** in standard LSP protocol — no daemon, no port, just stdio:

```bash
java -jar smali-lsp.jar lsp
```

Configure your editor's LSP client to launch this command for `.smali` files. The server will automatically index the workspace on startup.

<details>
<summary>VS Code (via APKLab)</summary>

[APKLab](https://github.com/APKLab/APKLab) integrates smali-lsp automatically. Install APKLab -> Run update tools command and the LSP starts when you open a `.smali` file.
Alternatively, you can set `apklab.smaliLspPath` to your `smali-lsp.jar`, 

</details>

<details>
<summary>Neovim (via nvim-lspconfig)</summary>

```lua
local lspconfig = require('lspconfig')
local configs = require('lspconfig.configs')

if not configs.smali_lsp then
  configs.smali_lsp = {
    default_config = {
      cmd = { 'java', '-jar', '/path/to/smali-lsp.jar', 'lsp' },
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
args = ["-jar", "/path/to/smali-lsp.jar", "lsp"]
```

</details>

<details>
<summary>Emacs (eglot)</summary>

```elisp
(with-eval-after-load 'eglot
  (add-to-list 'eglot-server-programs
               '(smali-mode . ("java" "-jar" "/path/to/smali-lsp.jar" "lsp"))))
```

</details>

### MCP Server

```bash
java -jar smali-lsp.jar mcp
```

Runs as an [MCP](https://modelcontextprotocol.io) server over stdio, exposing smali analysis tools to AI agents (Claude, Cursor, etc.).

Add to your MCP config (`.vscode/mcp.json`,`claude_desktop_config.json`, Cursor settings, etc.):

```json
{
  "mcpServers": {
    "smali-mcp": {
      "command": "java",
      "args": ["-jar", "/path/to/smali-lsp.jar", "mcp"]
    }
  }
}
```

## Performance

Tested on real-world APKs (119K smali files, 118K classes, 560K methods):

| Operation          | Avg Latency | Throughput       |
| ------------------ | ----------- | ---------------- |
| Goto Definition    | <1ms        | ~4,000 ops/sec   |
| Find References    | <1ms        | O(1) reverse index |
| Hover              | <1ms        | —                |
| Parsing / Indexing | ~0.3ms/file | ~3,300 files/sec |
| Symbol Search      | <1s         | —                |

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

Output: `build/libs/smali-lsp.jar`

## Architecture

```
ANTLR4 Grammar → SmaliParser → ASTBuilder → WorkspaceIndex → LSP/MCP Providers
                                                ↑
                                    WorkspaceScanner (parallel)
```

- **Parser**: ANTLR4 with SLL prediction mode (no ambiguities in smali grammar)
- **Index**: Thread-safe `ConcurrentHashMap` with O(1) lookups, bidirectional class-URI maps, string literal index
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
./gradlew test --tests "*.stress.*"
```

## License

Copyright (C) 2026 Surendrajat

This project is licensed under the [GNU General Public License v3.0](LICENSE), except files under `src/main/antlr/` which are MIT licensed (see header in each file).

## Credits

- @psygate for https://github.com/psygate/smali-antlr4-grammar
