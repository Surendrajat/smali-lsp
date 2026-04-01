# smali-lsp

A Language Server Protocol implementation for [Smali](https://github.com/google/smali).


## Features

- **Go to Definition** — jump from any instruction to its target (classes, methods, fields, labels)
- **Find References** — cross-file xrefs with inheritance-aware polymorphic matching
- **Hover** — method signatures, field types, class info, SDK class detection
- **Document Symbols** — class outline with methods and fields
- **Workspace Symbols** — fuzzy search across the entire codebase
- **Diagnostics** — syntax errors and unresolved class references
- **Label Navigation** — goto/if-*/switch targets within methods
- **All 41 Dalvik instruction types** supported (invoke-*, iget/iput/sget/sput, new-instance, check-cast, etc.)

## Performance

Tested on real-world APKs (18K+ smali files):

| Operation | Avg Latency | Throughput |
|-----------|-------------|------------|
| Goto Definition | 0.26ms | 4,011 ops/sec |
| Parsing | 0.53ms/file | 926 files/sec |
| Find References | <100ms | — |

- 99.7% goto-definition coverage (tested on 300K methods)
- 100% parse success rate on 88K+ real smali files
- 18 KB memory per indexed file (string-interned)

## Building

Requires Java 17+ and Gradle 8.5+.

```bash
./gradlew shadowJar
```

Output: `build/libs/smali-lsp-all.jar`

## Usage

### VS Code

Install the [smali-lsp VS Code extension](vscode-extension/) and open a decompiled APK directory.

The extension auto-detects APKTool projects (looks for `AndroidManifest.xml` or `apktool.yml`) and indexes only the relevant `smali*/` directories.

### Daemon Mode (for tooling/agents)

```bash
java -jar smali-lsp-all.jar --daemon
```

Accepts JSON commands over stdin for programmatic access. See [mcp-wrapper/](mcp-wrapper/) for MCP server integration.

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
