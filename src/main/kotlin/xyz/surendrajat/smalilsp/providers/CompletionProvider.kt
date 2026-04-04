package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.ClassUtils

/**
 * Provides completion suggestions for smali files.
 *
 * Completion contexts:
 * - Class names (L-prefix types in any context)
 * - Method names (after ->)
 * - Field names (after ->)
 * - Opcodes (at instruction start)
 *
 * LSP Request: textDocument/completion
 */
class CompletionProvider(
    private val index: WorkspaceIndex
) {

    /**
     * Provide completion items at the given position.
     */
    fun provideCompletions(uri: String, position: Position, lineText: String): CompletionList {
        val textBeforeCursor = if (position.character <= lineText.length) {
            lineText.substring(0, position.character)
        } else {
            lineText
        }
        val trimmed = textBeforeCursor.trimStart()

        return when {
            // After -> : complete method or field names on a class
            trimmed.contains("->") -> completeMembers(trimmed)

            // Directive context: .super, .implements, .field type, method descriptor
            trimmed.startsWith(".super ") -> completeClassNames(trimmed.removePrefix(".super ").trim())
            trimmed.startsWith(".implements ") -> completeClassNames(trimmed.removePrefix(".implements ").trim())

            // L-prefix type being typed anywhere (method descriptors, field types, instructions)
            extractPartialClassName(trimmed) != null -> completeClassNames(extractPartialClassName(trimmed)!!)

            // Instruction opcode at start of line (inside method body)
            !trimmed.startsWith(".") && !trimmed.startsWith("#") -> completeOpcodes(trimmed)

            else -> CompletionList(false, emptyList())
        }
    }

    private fun completeMembers(textBeforeCursor: String): CompletionList {
        // Parse: "invoke-virtual {p0}, Lcom/example/MyClass;->meth"
        // or:    "iget v0, p0, Lcom/example/MyClass;->fie"
        val arrowIndex = textBeforeCursor.lastIndexOf("->")
        if (arrowIndex < 0) return CompletionList(false, emptyList())

        val prefix = textBeforeCursor.substring(arrowIndex + 2) // partial member name
        val beforeArrow = textBeforeCursor.substring(0, arrowIndex)

        // Extract class name (last L...;  before ->)
        val classNameMatch = Regex("""(L[\w/$]+;)""").findAll(beforeArrow).lastOrNull()
            ?: return CompletionList(false, emptyList())
        val className = classNameMatch.value

        val file = index.findClass(className) ?: return CompletionList(false, emptyList())
        val items = mutableListOf<CompletionItem>()

        // Complete methods
        for (method in file.methods) {
            if (method.name.startsWith(prefix, ignoreCase = true)) {
                val item = CompletionItem("${method.name}${method.descriptor}")
                item.kind = CompletionItemKind.Method
                item.detail = className
                item.insertText = "${method.name}${method.descriptor}"
                items.add(item)
            }
        }

        // Complete fields
        for (field in file.fields) {
            if (field.name.startsWith(prefix, ignoreCase = true)) {
                val item = CompletionItem("${field.name}:${field.type}")
                item.kind = CompletionItemKind.Field
                item.detail = className
                item.insertText = "${field.name}:${field.type}"
                items.add(item)
            }
        }

        return CompletionList(false, items)
    }

    private fun completeClassNames(partial: String): CompletionList {
        val items = mutableListOf<CompletionItem>()
        val normalizedPartial = partial.lowercase()

        for (className in index.getAllClassNames()) {
            val simpleName = ClassUtils.extractSimpleName(className)
            // Match against simple name or full path
            if (simpleName.lowercase().startsWith(normalizedPartial) ||
                className.lowercase().contains(normalizedPartial)
            ) {
                val item = CompletionItem(simpleName)
                item.kind = CompletionItemKind.Class
                item.detail = className
                item.insertText = className
                item.filterText = "$simpleName $className"
                items.add(item)
                if (items.size >= 50) break
            }
        }

        return CompletionList(items.size >= 50, items)
    }

    private fun completeOpcodes(partial: String): CompletionList {
        if (partial.isEmpty()) return CompletionList(false, emptyList())
        val items = OPCODES.filter { it.startsWith(partial, ignoreCase = true) }
            .take(30)
            .map { opcode ->
                val item = CompletionItem(opcode)
                item.kind = CompletionItemKind.Keyword
                item
            }
        return CompletionList(false, items)
    }

    /**
     * Extract partial class name being typed.
     * Looks for an unclosed L-prefix type: "Lcom/example/My" (no trailing semicolon).
     */
    private fun extractPartialClassName(text: String): String? {
        // Find last 'L' that starts a type reference and isn't closed with ';'
        val lastL = text.lastIndexOf('L')
        if (lastL < 0) return null
        val afterL = text.substring(lastL + 1)
        // If there's a semicolon, the type is complete — no completion needed
        if (afterL.contains(';')) return null
        // Must look like a partial class path (letters, digits, /, $)
        if (afterL.isNotEmpty() && afterL.all { it.isLetterOrDigit() || it == '/' || it == '$' || it == '_' }) {
            return afterL
        }
        return null
    }

    companion object {
        private val OPCODES = listOf(
            // Invoke
            "invoke-virtual", "invoke-super", "invoke-direct", "invoke-static", "invoke-interface",
            "invoke-virtual/range", "invoke-super/range", "invoke-direct/range", "invoke-static/range", "invoke-interface/range",
            // Move
            "move", "move/from16", "move/16", "move-wide", "move-wide/from16", "move-wide/16",
            "move-object", "move-object/from16", "move-object/16", "move-result", "move-result-wide",
            "move-result-object", "move-exception",
            // Return
            "return-void", "return", "return-wide", "return-object",
            // Const
            "const/4", "const/16", "const", "const/high16",
            "const-wide/16", "const-wide/32", "const-wide", "const-wide/high16",
            "const-string", "const-string/jumbo", "const-class",
            // Field access
            "iget", "iget-wide", "iget-object", "iget-boolean", "iget-byte", "iget-char", "iget-short",
            "iput", "iput-wide", "iput-object", "iput-boolean", "iput-byte", "iput-char", "iput-short",
            "sget", "sget-wide", "sget-object", "sget-boolean", "sget-byte", "sget-char", "sget-short",
            "sput", "sput-wide", "sput-object", "sput-boolean", "sput-byte", "sput-char", "sput-short",
            // Object
            "new-instance", "check-cast", "instance-of", "new-array",
            // Control flow
            "goto", "goto/16", "goto/32",
            "if-eq", "if-ne", "if-lt", "if-ge", "if-gt", "if-le",
            "if-eqz", "if-nez", "if-ltz", "if-gez", "if-gtz", "if-lez",
            "packed-switch", "sparse-switch",
            // Misc
            "nop", "throw", "monitor-enter", "monitor-exit",
            "fill-array-data", "filled-new-array", "filled-new-array/range",
            // Array
            "aget", "aget-wide", "aget-object", "aget-boolean", "aget-byte", "aget-char", "aget-short",
            "aput", "aput-wide", "aput-object", "aput-boolean", "aput-byte", "aput-char", "aput-short",
            "array-length",
            // Math
            "add-int", "sub-int", "mul-int", "div-int", "rem-int",
            "add-long", "sub-long", "mul-long", "div-long", "rem-long",
            "add-float", "sub-float", "mul-float", "div-float", "rem-float",
            "add-double", "sub-double", "mul-double", "div-double", "rem-double",
            "add-int/lit8", "add-int/lit16",
            // Conversion
            "int-to-long", "int-to-float", "int-to-double",
            "long-to-int", "long-to-float", "long-to-double",
            "float-to-int", "float-to-long", "float-to-double",
            "double-to-int", "double-to-long", "double-to-float",
            "int-to-byte", "int-to-char", "int-to-short"
        )
    }
}
