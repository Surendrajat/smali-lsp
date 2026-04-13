package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import xyz.surendrajat.smalilsp.data.DalvikOpcodeDatabase
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
            trimmed.contains("->") -> completeMembers(trimmed, position)

            // Directive context: .super, .implements, .field type, method descriptor
            trimmed.startsWith(".super ") -> completeClassNames(trimmed.removePrefix(".super ").trim(), position)
            trimmed.startsWith(".implements ") -> completeClassNames(trimmed.removePrefix(".implements ").trim(), position)

            // L-prefix type being typed anywhere (method descriptors, field types, instructions)
            else -> extractPartialClassName(trimmed)?.let { partial ->
                // partial includes the 'L' prefix, replace range covers "Lpartial..."
                completeClassNames(partial, position)
            } ?: if (!trimmed.startsWith(".") && !trimmed.startsWith("#")) {
                // Instruction opcode at start of line (inside method body)
                completeOpcodes(trimmed, position)
            } else {
                CompletionList(false, emptyList())
            }
        }
    }

    private fun completeMembers(textBeforeCursor: String, position: Position): CompletionList {
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
        val replaceRange = Range(
            Position(position.line, position.character - prefix.length),
            position
        )

        // Complete methods
        for (method in file.methods) {
            if (method.name.startsWith(prefix, ignoreCase = true)) {
                val text = "${method.name}${method.descriptor}"
                val item = CompletionItem(text)
                item.kind = CompletionItemKind.Method
                item.detail = className
                item.textEdit = Either.forLeft(TextEdit(replaceRange, text))
                items.add(item)
            }
        }

        // Complete fields
        for (field in file.fields) {
            if (field.name.startsWith(prefix, ignoreCase = true)) {
                val text = "${field.name}:${field.type}"
                val item = CompletionItem(text)
                item.kind = CompletionItemKind.Field
                item.detail = className
                item.textEdit = Either.forLeft(TextEdit(replaceRange, text))
                items.add(item)
            }
        }

        return CompletionList(false, items)
    }

    private fun completeClassNames(partial: String, position: Position): CompletionList {
        val items = mutableListOf<CompletionItem>()
        // Determine if partial includes the 'L' prefix (from extractPartialClassName)
        val hasLPrefix = partial.startsWith("L")
        val normalizedPartial = partial.lowercase()
        val replaceRange = Range(
            Position(position.line, position.character - partial.length),
            position
        )

        for (className in index.getClassNames()) {
            // Match: partial against full class name or simple name
            val classNameLower = className.lowercase()
            val simpleName = ClassUtils.extractSimpleName(className)
            val simpleNameLower = simpleName.lowercase()

            val matches = if (hasLPrefix) {
                // L-prefix: match against full class name (e.g., "Lcom/" matches "Lcom/example/Foo;")
                classNameLower.startsWith(normalizedPartial)
            } else {
                // Directive context (.super etc): match partial against simple name or path
                simpleNameLower.startsWith(normalizedPartial) ||
                    classNameLower.contains(normalizedPartial)
            }

            if (matches) {
                // Strip L prefix and ; suffix for display: "com/example/MyClass"
                val readablePath = className.removePrefix("L").removeSuffix(";")
                val item = CompletionItem(readablePath)
                item.kind = CompletionItemKind.Class
                item.detail = simpleName
                // Insert the full class name; replaceRange covers the partial already typed
                item.textEdit = Either.forLeft(TextEdit(replaceRange, className))
                item.filterText = "$readablePath $simpleName"
                items.add(item)
                if (items.size >= 50) break
            }
        }

        return CompletionList(items.size >= 50, items)
    }

    private fun completeOpcodes(partial: String, position: Position): CompletionList {
        if (partial.isEmpty()) return CompletionList(false, emptyList())
        val replaceRange = Range(
            Position(position.line, position.character - partial.length),
            position
        )
        val items = DalvikOpcodeDatabase.allInstructionNames()
            .filter { it.startsWith(partial, ignoreCase = true) }
            .take(30)
            .mapNotNull { opcodeName ->
                val info = DalvikOpcodeDatabase.lookup(opcodeName) ?: return@mapNotNull null
                val item = CompletionItem(opcodeName)
                item.kind = CompletionItemKind.Keyword
                item.detail = "0x${info.opcode} ${info.formatId}"
                item.documentation = Either.forRight(
                    MarkupContent(MarkupKind.MARKDOWN, info.toCompletionDoc())
                )
                item.textEdit = Either.forLeft(TextEdit(replaceRange, opcodeName))
                item
            }
        return CompletionList(false, items)
    }

    /**
     * Extract partial class name being typed, including the leading 'L'.
     * Looks for an unclosed L-prefix type: "Lcom/example/My" (no trailing semicolon).
     * Returns the full partial including 'L', e.g., "Lcom/example/My".
     */
    private fun extractPartialClassName(text: String): String? {
        // Find last 'L' that starts a type reference and isn't closed with ';'
        val lastL = text.lastIndexOf('L')
        if (lastL < 0) return null
        val afterL = text.substring(lastL + 1)
        // If there's a semicolon, the type is complete — no completion needed
        if (afterL.contains(';')) return null
        // Must look like a partial class path (letters, digits, /, $)
        if (afterL.isEmpty() || afterL.all { it.isLetterOrDigit() || it == '/' || it == '$' || it == '_' }) {
            return text.substring(lastL) // Include the 'L' prefix
        }
        return null
    }

}
