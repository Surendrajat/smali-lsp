# Smali-LSP Architecture

Deep-dive into how smali-lsp works, from startup to every LSP feature,
with memory/CPU estimates and design rationale.

**Codebase**: 26 Kotlin source files (~8,900 lines) + 2 ANTLR grammars (1,595 lines) + 95 test files (~28,000 lines).

---

## 1. Startup & Initialization

### 1.1 Entry Point (`Main.kt`)

```
main() → parseArgs → startLsp() | startMcp()
```

- **LSP mode**: Creates `SmaliLanguageServer`, wires it to stdin/stdout via LSP4J's
  `Launcher`, calls `launcher.startListening()`. The JVM blocks on this call.
- **MCP mode**: Creates `McpMode`, calls `run()` which sets up a JSON-RPC server
  over stdio using the MCP Kotlin SDK.

### 1.2 LSP Handshake

| Step | Who | What | Time |
|------|-----|------|------|
| `initialize` | Client→Server | Server returns capabilities, stores workspace folders | <1ms |
| `initialized` | Client→Server | Server launches `indexWorkspace()` on `CompletableFuture.runAsync` | <1ms |
| Indexing | Server (background) | `WorkspaceScanner.scanDirectory()` — parallel parse & index | 30s for 119K files |

**Design decision: Deferred indexing.**
Indexing runs in a background thread after `initialized`, not during `initialize`.
This lets the client display the editor immediately while indexing runs.
Progress is reported via LSP `WorkDoneProgress` notifications.

**Trade-off**: LSP requests during indexing return empty/partial results.
Alternative: Queue requests until indexing completes. We chose the simpler approach
because most users open a file (triggering `didOpen` which indexes that file)
before making queries.

---

## 2. Parsing Pipeline

### 2.1 ANTLR Grammar (SmaliLexer.g4 + SmaliParser.g4)

**Coverage**: 206/208 Dalvik instructions (99%), all 29 directives.

| Component | Tokens/Rules | Status |
|-----------|-------------|--------|
| Move/return/const | 24 instructions | Complete |
| Field access (iget/iput/sget/sput) | 28 instructions | Complete |
| Invoke (virtual/super/direct/static/interface) | 10 + 10 range variants | Complete |
| Arithmetic (binary/2addr/literal) | 70 instructions | Complete |
| Type ops (new-instance/check-cast/etc.) | 7 instructions | Complete |
| Control flow (goto/if-*/switch) | 12 instructions | Complete |
| invoke-polymorphic, invoke-custom | 4 instructions | **Stub only** (no operands) |

**Known gap**: `invoke-polymorphic` and `invoke-custom` rules are stubs — they
accept the opcode token but not operands. Files using these instructions (rare, API 26+)
will produce parse errors on those lines. These instructions are almost never seen in
decompiled APKs. The rest of the file still parses correctly due to ANTLR error recovery.

**Design decision: SLL-only parsing.**
We use ANTLR's SLL prediction mode with no LL fallback. Verified: 0 fallbacks on 88,688
real-world smali files. SLL is 4-10x faster than LL. If a file hypothetically needed LL,
it would silently produce a parse error.

### 2.2 SmaliParser.kt

Wraps ANTLR parsing:

```
content → BOM strip → blank check → CharStream → Lexer → TokenStream → Parser → ParseTree
```

- **BOM handling**: Strips UTF-8 BOM (`\uFEFF`) before parsing.
- **Fast-reject**: If content doesn't contain `.class`, returns null immediately
  (saves ANTLR setup for non-smali files).
- **Error collection**: Custom `ANTLRErrorListener` collects `SyntaxError` objects
  (1-based line, column, message). Exception-path errors use `line=1` so downstream
  `line-1` conversion yields `0`, not `-1`.

| Step | CPU cost | Memory |
|------|----------|--------|
| BOM strip | O(1) | None (substring or same reference) |
| CharStream creation | O(n) | Copy of input (~1x file size) |
| Lexer tokenization | O(n) | All tokens buffered (~2-3x file size) |
| Parser (SLL) | O(n) | Parse tree (~3-5x file size) |
| **Total per file** | **~5ms for avg smali file** | **~10x file size peak** |

### 2.3 ASTBuilder.kt

Walks the ANTLR parse tree (listener pattern) and builds our clean data model:

```
ParseTree → ASTBuilder (listener walk) → SmaliFile
```

Extracts:
- **ClassDefinition**: name, modifiers, super, interfaces, ranges
- **MethodDefinition**: name, descriptor, parameters, return type, modifiers, range
- **FieldDefinition**: name, type, modifiers, range
- **Instructions**: Only navigation-relevant instructions (invoke, field access, type ops,
  jump, const-string). Arithmetic/move/return instructions are NOT captured — they have
  no cross-file navigation value.
- **Labels**: name → range mapping for jump target navigation

**Design decision: Selective instruction capture.**
We only build AST nodes for instructions that reference symbols (classes, methods, fields,
labels, strings). This covers ~40% of instruction types but captures 100% of navigable
references. Capturing all 208 instruction types would increase memory ~2.5x with zero
navigation benefit.

| Step | CPU cost | Memory |
|------|----------|--------|
| Tree walk | O(nodes) | SmaliFile object (~1-5KB per class) |
| String interning | O(1) per string | StringPool bounded at 500K entries |

### 2.4 StringPool.kt

Bounded string deduplication pool using ConcurrentHashMap.

**Why**: Class names like `Ljava/lang/Object;` appear in thousands of files.
Without interning, 119K files would store 119K copies of common strings.

**Bound**: 500K entries (~20MB). Normal workspaces use ~50K entries.
If the pool fills, new strings pass through without interning (safety valve).

**Pre-interned**: Common type descriptors (`V`, `Z`, `I`, `J`, `F`, `D`),
modifiers (`public`, `private`, `static`, `final`), and `Ljava/lang/Object;`.

---

## 3. Indexing (`WorkspaceIndex.kt`)

The central data store. 701 lines, 16 concurrent maps + 2 caches.

### 3.1 Data Structures

| Map | Key | Value | Purpose | Lookup |
|-----|-----|-------|---------|--------|
| `files` | `Lcom/Foo;` | `SmaliFile` | Full AST per class | O(1) |
| `classToUri` | `Lcom/Foo;` | `file:///path` | Class → file URI | O(1) |
| `uriToClass` | `file:///path` | `Lcom/Foo;` | URI → class (reverse) | O(1) |
| `methodLocations` | `Lcom/Foo;->bar()V` | `Set<Location>` | Method declaration sites | O(1) |
| `fieldLocations` | `Lcom/Foo;->baz` | `Location` | Field declaration site | O(1) |
| `classUsages` | `Lcom/Foo;` | `Set<URI>` | Files that reference class | O(1) |
| `subclassIndex` | `Lcom/Foo;` | `Set<className>` | Direct subclasses | O(1) |
| `implementorsIndex` | `Lcom/Foo;` | `Set<className>` | Direct implementors | O(1) |
| `methodUsages` | `Lcom/Foo;->bar()V` | `Set<Location>` | All call sites | O(1) |
| `fieldUsages` | `Lcom/Foo;->baz` | `Set<Location>` | All access sites | O(1) |
| `classRefLocations` | `Lcom/Foo;` | `Set<Location>` | All reference locations | O(1) |
| `simpleNameIndex` | `Foo` | `Set<className>` | Simple name → full names | O(1) |
| `stringIndex` | `"hello"` | `Set<Location>` | String literal locations | O(1) (lazy) |
| `documentContents` | URI | content string | Open editor buffers | O(1) |
| `documentLines` | URI | `List<String>` | Cached split lines | O(1) |

### 3.2 Memory Estimate (119K classes, Truecaller APK)

**Per-class breakdown** (average: 2 methods, 1 field, 5 instructions):

| Component | Bytes/class | Notes |
|-----------|-------------|-------|
| SmaliFile object | ~3,000 | Class + method + field defs + instruction list |
| files entry (CHM) | ~100 | Key + value ref + CHM node overhead |
| classToUri + uriToClass | ~200 | Two entries, key + value strings |
| methodLocations | ~400 | 2 methods × (signature + Location + CHM overhead) |
| fieldLocations | ~200 | 1 field × (signature + Location) |
| classUsages | ~80 | 1 super + 0-1 interfaces |
| subclassIndex + implementorsIndex | ~80 | 1 entry each (shared keys) |
| methodUsages (as target) | ~0 | Only populated when OTHER files call this class |
| fieldUsages (as target) | ~0 | Only populated when OTHER files access this class |
| classRefLocations | ~200 | References from instructions in this file |
| simpleNameIndex | ~60 | Simple name string + set entry |
| **Total per class** | **~4,300** | |

**Aggregate for 119K classes:**

| Component | Size | Notes |
|-----------|------|-------|
| SmaliFile ASTs | ~360 MB | 119K × 3KB average |
| Forward indexes | ~35 MB | classToUri, uriToClass, method/fieldLocations |
| Reverse indexes | ~80 MB | methodUsages, fieldUsages, classRefLocations |
| Hierarchy indexes | ~10 MB | subclass, implementors, simpleNameIndex |
| String index (lazy) | ~15 MB | Built on first string search |
| ConcurrentHashMap overhead | ~40 MB | ~30% overhead per CHM |
| StringPool | ~20 MB | 500K interned strings |
| **Total heap** | **~560 MB** | |

**Observed**: JVM reports ~700-900 MB heap for Truecaller (119K files). The gap is
JVM object headers, alignment padding, ANTLR residual structures, and GC overhead.

### 3.3 Indexing Performance (WorkspaceScanner.kt)

```
findSmaliFiles() → Channel<File> → N workers → parse + indexFile()
```

**Worker pool model**: `maxOf(2, availableProcessors)` coroutines on `Dispatchers.Default`.
Channel capacity: `workers × 4` (one batch ahead per worker for backpressure).

| Phase | CPU | I/O | Time (119K files, M1 Mac) |
|-------|-----|-----|---------------------------|
| findSmaliFiles (walkTopDown) | Low | Sequential directory walk | ~2s |
| File read (readText) | Low | Parallel, SSD-bound | ~5s |
| ANTLR parse (SLL) | High | None | ~15s |
| ASTBuilder walk | Medium | None | ~5s |
| indexFile() | Medium | None | ~3s |
| **Total** | | | **~30s** |

**Bottleneck**: ANTLR parsing is the dominant cost. The SLL prediction mode and
CommonTokenStream buffering are CPU-bound. Improvements would require a faster parser
(hand-written recursive descent would be ~3x faster but much harder to maintain).

### 3.4 Re-indexing (didChange)

When the user edits a file, `didChange` triggers a re-parse and re-index:

```
content → parseWithErrors() → indexFile() → publishDiagnostics()
```

`indexFile()` calls `removeOldEntries(oldFile)` first, which cleans ALL secondary
index maps (method/field locations, usages, hierarchy, classRefLocations, simpleNameIndex)
before adding new entries. This prevents ghost references to renamed/deleted symbols.

**Design decision: Full re-parse on every keystroke.**
We use `TextDocumentSyncKind.Full` (entire document on each change, not incremental diffs).
This is simpler and avoids complex incremental parsing. For typical smali files (<1000 lines),
full re-parse takes <10ms — well within the LSP response budget.

**Trade-off**: Large files (>10K lines, rare in smali) would take ~50ms. Incremental
sync would reduce this to <5ms but adds significant complexity for minimal benefit.

### 3.5 String Index (Lazy Rebuild)

The string literal index is NOT built during initial indexing. It's deferred to
the first `searchStrings()` or `findStringLocations()` call.

**Why**: During parallel indexing, 119K files all contend on the string index CHM.
String searches are rare (only used by the MCP `smali_search_strings` tool), so
deferring the build eliminates a hot contention point.

**Implementation**: Double-checked locking with `@Volatile` dirty flag:
1. `indexFile()` sets `stringIndexDirty = true` (volatile write)
2. `searchStrings()` checks dirty flag (volatile read, fast path)
3. If dirty: acquires lock, rebuilds from all `files.values`, sets dirty=false

**Correctness**: The volatile write to `stringIndexDirty` creates a happens-before
relationship per JMM, ensuring the new `stringIndex` HashMap reference is visible
to subsequent readers. The HashMap itself is safe because it's replaced atomically
(not mutated in place after publication).

---

## 4. LSP Features

### 4.1 Go-to-Definition (`DefinitionProvider.kt`)

```
position → findNodeAt() → dispatch by NodeType
```

| Cursor on | Navigation target | Complexity |
|-----------|-------------------|------------|
| CLASS node | Superclass definition | O(1) index lookup |
| METHOD declaration line | Class refs in signature (params, return type) | O(params) string scan |
| METHOD body (directive) | Class refs in .catch/.annotation/.implements | O(line) regex |
| FIELD declaration | Class ref in field type | O(1) |
| INSTRUCTION (invoke) | Method/class/field definition via InstructionSymbolExtractor | O(1) index + O(hierarchy) for inherited methods |
| INSTRUCTION (jump) | Label definition in same file | O(methods) scan |
| LABEL | Self (already at definition) | O(1) |

**Hierarchy traversal**: `findMethod()` and `findField()` walk the class hierarchy
(super → super → ...) with visited-set cycle protection. For deep hierarchies (10+ levels),
this is O(depth) with one CHM lookup per level.

### 4.2 Find References (`ReferenceProvider.kt`)

Uses reverse indexes for O(1) lookups:

| Reference type | Index used | Complexity |
|----------------|-----------|------------|
| Class references | `classRefLocations[className]` | O(1) |
| Method call sites | `methodUsages[signature]` | O(1) |
| Field access sites | `fieldUsages[signature]` | O(1) |
| Polymorphic (include subclass overrides) | `getAllSubclasses()` + per-class lookup | O(subclasses) |

**Before reverse indexes**: Find References did O(n) full scan of all files,
checking every instruction. For 119K files this took ~5 seconds per query.
**After**: O(1) lookup, <1ms per query.

### 4.3 Hover (`HoverProvider.kt`)

```
position → findNodeAt() → dispatch by NodeType → Markdown content
```

| Cursor on | Hover content |
|-----------|--------------|
| Class | Name, modifiers, super, interfaces, method/field counts |
| Method declaration | Signature, readable types, parameter names |
| Method body (opcode) | DalvikOpcodeDatabase: format, description, operands |
| Method body (directive) | Class info for .catch/.annotation targets |
| Field | Name, type, modifiers |
| Label | Label name, reference count, label type (cond/goto/try/catch) |
| Instruction symbol | Class/method/field info at cursor position |

**DalvikOpcodeDatabase**: 256 opcodes with format strings, descriptions, and operand
info. Loaded once as a lazy val, cached for all subsequent hover requests.

### 4.4 Completion (`CompletionProvider.kt`)

| Context | Completions |
|---------|-------------|
| `.super ` / `.implements ` | Class name completion from all indexed classes |
| `L` prefix anywhere | Class name completion (fuzzy match on simple name) |
| Instruction position | Opcode name completion with documentation |

**Class name completion** uses `getClassNames()` which returns
`ConcurrentHashMap.KeySetView` — zero-copy iteration over the keys.
For 119K classes, filtering by prefix takes ~5ms.

**Design notes**:
- Labels show full class path (e.g., `com/example/MyActivity`) to distinguish
  obfuscated classes with identical simple names. Detail shows simple name.
- Replace range includes the `L` prefix to avoid double-L insertion.
- FilterText includes both path and simple name for flexible matching.

### 4.5 Code Lens (`CodeLensProvider.kt`)

Shows `N references` above each method and field definition.
Uses **lazy resolution**: `provideCodeLenses()` returns unresolved lenses (no counting),
`resolveCodeLens()` computes the count only when the lens scrolls into view.

| Operation | Complexity |
|-----------|-----------|
| provideCodeLenses | O(methods + fields) per file |
| resolveCodeLens (method) | O(1) via `methodUsages` reverse index |
| resolveCodeLens (field) | O(1) via `fieldUsages` reverse index |

### 4.6 Call Hierarchy (`CallHierarchyProvider.kt`)

| Direction | Implementation | Complexity |
|-----------|---------------|------------|
| Incoming calls | `findMethodUsages()` → group by containing method | O(usages) |
| Outgoing calls | Scan method's instructions for invoke instructions | O(instructions) |

### 4.7 Type Hierarchy (`TypeHierarchyProvider.kt`)

| Direction | Implementation | Complexity |
|-----------|---------------|------------|
| Supertypes | Read `superClass` + `interfaces` from ClassDefinition | O(1) |
| Subtypes | `getDirectSubclasses()` + `getDirectImplementors()` | O(1) |

Uses pre-built `subclassIndex` and `implementorsIndex` for O(1) subtypes lookup.
Before these indexes, subtypes required O(n) scan of all classes.

### 4.8 Rename (`RenameProvider.kt`)

Renames symbols across the workspace with `prepareRename()` validation + `rename()` execution.

| Rename target | What gets renamed | Complexity |
|---------------|-------------------|------------|
| Method (at declaration) | Declaration + all invoke call sites + subclass overrides | O(usages + subclasses) |
| Method (at call site) | Same — resolves back to the declaring class | O(usages + subclasses) |
| Field (at declaration) | Declaration + all iget/iput/sget/sput access sites | O(usages) |
| Field (at access site) | Same — resolves back to the declaring class | O(usages) |
| Label (at definition) | `:label` definition + all jump refs + .catch refs in same method | O(instructions in method) |
| Label (at jump) | Same — resolves the label within the containing method | O(instructions in method) |

**Blocked renames**:
- `<init>` / `<clinit>`: Special methods, renaming would break JVM semantics.
- Class names: Would require file rename + cross-file path rewriting (out of scope).

**Polymorphic method rename**: When renaming `Base.run()`, all overrides in subclasses
(`Child.run()`, `GrandChild.run()`) are also renamed, plus all call sites targeting any
class in the hierarchy. Uses `getAllSubclasses()` BFS traversal.

**Obfuscated name safety**: Field/method usages are keyed by `className->name` (fields)
or `className->nameDescriptor` (methods). Renaming `Lcom/a/b;->d` only affects usages
of that exact class+field pair — NOT `Lcom/x/y;->d`. This is safe even for single-char
obfuscated names because the index key always includes the owning class.

**Position finding**: Usage locations in the index store the entire instruction range.
To rename precisely, the provider reads the actual line text and uses `String.indexOf()`
after `->` to find the exact character offset of the name. This handles arbitrary
whitespace/formatting in the instruction.

### 4.9 Diagnostics (`DiagnosticProvider.kt`)

Two categories:
1. **Syntax errors**: From ANTLR parse errors (collected during parsing)
2. **Semantic errors**: Undefined class references in `.super`, `.implements`,
   field types, and instruction targets (only if class starts with `L`, not SDK)

Published on:
- `didOpen`: Always, even if file was already indexed from workspace scan
- `didChange`: After re-parse

### 4.10 Workspace Symbols (`WorkspaceSymbolProvider.kt`)

Fuzzy search across all indexed symbols:
- Classes, methods, fields
- Scoring: exact > prefix > camelCase > contains > fuzzy
- Limit: 500 results

---

## 5. MCP Mode (`McpMode.kt`)

Alternative interface for AI agents. Same index, different transport.

| Tool | Maps to |
|------|---------|
| `smali_index` | WorkspaceScanner.scanDirectory() |
| `smali_find_definition` | DefinitionProvider-like lookup |
| `smali_search_symbols` | WorkspaceSymbolProvider.search() |
| `smali_references` | ReferenceProvider.findReferences() |
| `smali_hover` | HoverProvider.provideHover() |
| `smali_diagnostics` | DiagnosticProvider.computeDiagnostics() |
| `smali_file_outline` | Direct SmaliFile access (methods, fields) |
| `smali_search_strings` | WorkspaceIndex.searchStrings() |
| `smali_call_graph` | CallHierarchyProvider |
| `smali_xref_summary` | Aggregated class usage stats |
| `smali_type_hierarchy` | TypeHierarchyProvider |

---

## 6. Concurrency Model

### 6.1 During Initial Indexing

Multiple `WorkspaceScanner` workers call `indexFile()` concurrently.
Thread safety comes from:
- All index maps are `ConcurrentHashMap`
- All mutable sets are `ConcurrentHashMap.newKeySet()` (thread-safe sets)
- `files[className] = file` is an atomic CHM put

**Accepted race**: Two workers could index different files referencing the same class,
causing concurrent `computeIfAbsent` + `add` on the same set. This is safe because
`ConcurrentHashMap.newKeySet()` returns a concurrent set.

### 6.2 During LSP Operation

LSP4J processes requests on a single-threaded executor by default.
`didOpen`/`didChange`/`didClose` and all query methods run sequentially.
The only concurrent access is between the initial indexing thread and early LSP requests,
which is safe because:
- Queries read from CHMs (safe for concurrent reads)
- `indexFile()` writes to CHMs (safe for concurrent writes)
- Results may be incomplete during indexing (acceptable — we show progress)

### 6.3 Known Theoretical Races (Not Exploitable in Practice)

1. **`indexFile` check-then-act**: `files[className]?.let { removeOldEntries(it) }` followed
   by `files[className] = file`. Two threads indexing the same class could interleave.
   **In practice**: Only happens during initial scan (one file per class name) and `didChange`
   (single-threaded LSP dispatch). Never concurrent on the same class.

2. **`stringIndex` is not `@Volatile`**: The HashMap reference is published through a
   non-volatile field. The happens-before from the `@Volatile` dirty flag write makes this
   safe per JMM, but it's fragile. Adding `@Volatile` to `stringIndex` would be more explicit.

---

## 7. Performance Profile

### 7.1 Initial Indexing (30s for 119K files)

| Phase | % of time | Bottleneck |
|-------|-----------|-----------|
| ANTLR tokenization + parsing | ~50% | CPU-bound, SLL prediction |
| File I/O (readText) | ~15% | SSD sequential reads |
| ASTBuilder walk | ~15% | Object allocation |
| indexFile() CHM operations | ~10% | CHM contention on shared maps |
| findSmaliFiles (walkTopDown) | ~7% | Directory traversal |
| GC pressure | ~3% | Temporary ANTLR objects |

**Most problematic step**: ANTLR parsing. Options to improve:
- Hand-written recursive descent parser: ~3x faster but ~5x more code to maintain
- Incremental parsing: Only re-parse changed regions — complex, marginal gain for initial scan
- Parallel lexer: ANTLR lexer is not parallelizable (stateful)
- **Recommendation**: Accept 30s. It's one-time cost, with progress bar.

### 7.2 didChange Re-indexing

| Step | Time |
|------|------|
| ANTLR re-parse | ~5-10ms |
| removeOldEntries | ~0.1ms |
| indexFile | ~0.5ms |
| Diagnostics | ~1ms |
| **Total** | **~7-12ms** |

Well within LSP's interactive budget. Not a bottleneck.

### 7.3 Query Performance

| Query | Time | Notes |
|-------|------|-------|
| Go-to-definition | <1ms | O(1) index lookup |
| Find references (simple) | <1ms | O(1) reverse index |
| Find references (polymorphic) | 1-5ms | O(subclasses) hierarchy walk |
| Hover | <1ms | O(1) lookup + string formatting |
| Completion | 5-15ms | O(classes) prefix filter |
| Workspace symbols | 10-50ms | O(classes) fuzzy match |
| Code lens resolve | <1ms | O(1) reverse index |

No query bottleneck. All are interactive-speed.

---

## 8. What's Missing

### 8.1 Feature Status

| Feature | Status | Notes |
|---------|--------|-------|
| Rename symbol | Implemented | Methods, fields, labels. Polymorphic-aware. Class rename not supported. |
| Document formatting | Not planned | Smali files are machine-generated, rarely hand-formatted |
| Semantic tokens | Not yet | Medium effort — different colors for user vs SDK classes, register types |
| Incremental text sync | Not planned | Full sync is fast enough (<10ms for typical smali files) |
| File watcher | Not yet | Medium effort — detect disk changes without restart |

**Evaluated but not shipped** (prototyped and rejected — poor value/accuracy):

| Feature | Why removed |
|---------|------------|
| Go-to-implementation | Found too many unrelated methods. The subclass index finds overrides correctly, but for Smali files the results were noisy and indistinguishable from find-references. |
| Folding ranges | VS Code already provides indentation-based folding for free. A dedicated provider added no value for Smali files whose structure is simple (`.method`...`.end method`). |
| Signature help | Smali methods don't have named parameters in the call syntax (`invoke-virtual {v0, v1}, Lfoo;->bar(II)V`). Showing parameter types from the descriptor is trivially visible in the code itself. |

### 8.2 Known Limitations

1. **No cross-dex duplicate handling**: If an APK has duplicate classes across dex splits
   (e.g., `javax.annotation.Nonnull` in both `classes.dex` and `classes2.dex`), the last
   indexed file wins. 4 duplicates seen in Truecaller — acceptable.

2. **No annotation AST**: `.annotation` blocks are parsed by the grammar but not captured
   in the AST. Hover/definition on annotation values requires line-content regex fallback.

6. **No class rename**: Renaming a class would require renaming the file, rewriting
   `Lold/path;` → `Lnew/path;` across ALL files in the workspace, and updating the
   `.class` directive. This is a workspace-wide string replacement, not just a symbol rename.
   Considered too destructive and complex for now.

3. **invoke-polymorphic/invoke-custom stubs**: These rare instructions (API 26+) are
   recognized by the lexer but the parser rules don't consume operands. Files with these
   instructions will have parse errors on those lines, but the rest of the file still parses.

4. **No watch mode**: The server doesn't watch for file changes on disk. Only files opened
   in the editor (via `didOpen`/`didChange`) are re-indexed. Workspace-level changes
   (e.g., extracting a new APK) require restarting the server.

5. **Label search scans all methods**: `findLabelDefinition()` iterates all methods in a
   file looking for a label. Labels are method-local, so ideally we'd only search the
   enclosing method. In practice, label names are unique per file in decompiled smali.

---

## 9. Design Decisions & Rationale

### 9.1 Why ConcurrentHashMap everywhere?

**Decision**: All index maps use ConcurrentHashMap.

**Why**: Initial indexing is parallel (N workers). Using synchronized HashMap would
serialize all index updates, negating parallelism. CHM allows lock-free reads and
segment-level writes, giving us ~8x throughput on 8-core machines.

**Alternative considered**: ReadWriteLock + HashMap. Would be faster for reads but
slower for the write-heavy indexing phase. CHM is simpler and fast enough for both.

**Still justified?** Yes. The 30% memory overhead of CHM (vs HashMap) is
~15 MB for 119K classes. Acceptable for the concurrency benefit.

### 9.2 Why reverse indexes (methodUsages, fieldUsages, classRefLocations)?

**Decision**: Build reverse indexes during indexFile(), trading memory for query speed.

**Why**: Find References was O(n) — scanning all 119K files' instructions to find
references to a symbol. For 119K files, this took ~5 seconds per query.
With reverse indexes: O(1), <1ms.

**Memory cost**: ~80 MB for 119K files. Acceptable.

**Still justified?** Yes. The memory cost is modest and the query speedup
is dramatic. This is the single most impactful optimization in the codebase.

### 9.3 Why lazy string index?

**Decision**: Don't build string index during initial scan. Rebuild lazily on first search.

**Why**: During parallel indexing, the string index was a contention hotspot. Every file's
every const-string instruction contended on the same CHM. Deferring the build to a
single-threaded lazy rebuild eliminated this contention.

**Trade-off**: First string search is slower (~200ms to rebuild from 119K files).
Subsequent searches are O(1). String search is rarely used (only MCP tool), so
the cold-start penalty is acceptable.

**Still justified?** Yes. The indexing speedup outweighs the rare first-search delay.

### 9.4 Why Full text sync instead of Incremental?

**Decision**: `TextDocumentSyncKind.Full` — client sends entire document on every change.

**Why**: Incremental sync requires maintaining a line-column map and applying text edits
to an in-memory buffer. This is error-prone (off-by-one in column handling, Unicode
grapheme boundaries) and adds ~200 lines of code. Full sync re-sends the document, which
is at most ~50KB for a typical smali file. With modern IPC, this adds <1ms of overhead.

**Still justified?** Yes. Smali files are small. The complexity of incremental
sync is not justified for the marginal performance gain.

### 9.5 Why store SmaliFile objects (not just indexes)?

**Decision**: Keep the full SmaliFile AST in memory for every indexed file.

**Why**: Several features need the full AST:
- Hover on method body needs instruction list
- Code lens needs method/field counts
- Diagnostics need field types and method signatures
- Call hierarchy outgoing needs instruction scan

**Memory cost**: ~360 MB for 119K files (the largest single component).

**Alternative**: Store only indexes, re-parse from disk on demand. Would save ~360 MB
but add 5-10ms latency per hover/definition request (disk read + parse). For an LSP
that needs to feel instant, this trade-off favors memory.

**Still justified?** Yes, but this is the area where memory reduction would
have the biggest impact. For very large codebases (500K+ files), consider an LRU cache
that evicts least-recently-used SmaliFile objects and re-parses on demand.

### 9.6 Why ANTLR instead of hand-written parser?

**Decision**: Use ANTLR 4 with separate lexer/parser grammars.

**Why**: Smali has a complex, evolving syntax with 200+ instruction types, each with
different operand formats. A hand-written parser would be faster (~3x) but:
- ~3,000 lines of hand-written parsing code vs ~1,600 lines of grammar
- Every new Dalvik instruction requires manual parser changes
- ANTLR generates robust error recovery for free
- Grammar is self-documenting and matches the smali spec

**Still justified?** Yes. The 30s indexing time is dominated by parsing, but
a hand-written parser would only reduce it to ~10s. The maintenance burden doesn't
justify a 20s improvement on a one-time operation.

### 9.7 Why selective instruction capture in ASTBuilder?

**Decision**: Only capture instructions that reference navigable symbols
(invoke, field access, type ops, jumps, const-string). Skip arithmetic, move, return, etc.

**Why**: Arithmetic instructions like `add-int v0, v1, v2` reference only registers.
There's nothing to navigate to. Capturing them would increase memory by ~2.5x
(they make up ~60% of instructions) with zero user benefit.

**Still justified?** Yes. The opcode hover feature (DalvikOpcodeDatabase) provides hover
info for ALL instruction types via line-content parsing, so users still get documentation
even for uncaptured instructions.

---

## 10. Appendix: File Inventory

| File | Lines | Role |
|------|-------|------|
| SmaliLexer.g4 | 460 | ANTLR lexer — all smali tokens |
| SmaliParser.g4 | 1,135 | ANTLR parser — all smali rules |
| WorkspaceIndex.kt | 701 | Central index with 16 maps |
| ASTBuilder.kt | 698 | ANTLR parse tree → SmaliFile |
| McpMode.kt | 747 | MCP JSON-RPC server |
| HoverProvider.kt | 692 | Hover information |
| ReferenceProvider.kt | 583 | Find references |
| DefinitionProvider.kt | 458 | Go-to-definition |
| CompletionProvider.kt | 241 | Completions |
| DalvikOpcodeDatabase.kt | 1,049 | 256 opcode descriptions |
| RenameProvider.kt | 406 | Symbol rename across workspace |
| SmaliTextDocumentService.kt | 445 | LSP document lifecycle |
| Main.kt | 360 | Entry point, server setup |
| DataStructures.kt | 206 | Core data model |
| SmaliParser.kt | 159 | ANTLR wrapper |
| WorkspaceScanner.kt | 152 | Parallel file scanner |
| InstructionSymbolExtractor.kt | 278 | Cursor → symbol extraction |
| TypeHierarchyProvider.kt | 144 | Type hierarchy |
| WorkspaceSymbolProvider.kt | 213 | Workspace symbol search |
| CodeLensProvider.kt | 85 | Reference counts |
| CallHierarchyProvider.kt | 160 | Call hierarchy |
| DiagnosticProvider.kt | 176 | Syntax + semantic diagnostics |
| ClassUtils.kt | 145 | Class name utilities |
| TypeResolver.kt | 148 | Type → readable name |
| DescriptorParser.kt | 210 | Method descriptor parser |
| StringPool.kt | 90 | Bounded string interning |
