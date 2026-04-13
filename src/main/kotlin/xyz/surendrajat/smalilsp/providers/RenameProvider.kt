package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.ClassUtils
import org.slf4j.LoggerFactory

/**
 * Provides rename functionality for Smali symbols.
 *
 * Supported rename targets:
 * - Methods: renames declaration + all invoke call sites (polymorphic-aware)
 * - Fields: renames declaration + all iget/iput/sget/sput access sites
 * - Labels: renames definition + all jumps/catch references within the same method
 *
 * Not supported:
 * - Class rename: Would require file rename + package path changes across all files
 * - <init>/<clinit>: Special methods that cannot be renamed
 */
class RenameProvider(
    private val index: WorkspaceIndex
) {
    private val logger = LoggerFactory.getLogger(RenameProvider::class.java)

    /**
     * Validate that the symbol at cursor can be renamed, and return its current range + name.
     * LSP calls this before rename() to show the user what will be renamed.
     */
    fun prepareRename(uri: String, position: Position): Either<Range, PrepareRenameResult>? {
        val file = index.findFileByUri(uri) ?: return null
        val target = resolveRenameTarget(file, uri, position) ?: return null

        return Either.forRight(PrepareRenameResult(target.range, target.currentName))
    }

    /**
     * Perform rename: collect all locations and produce TextEdits.
     */
    fun rename(uri: String, position: Position, newName: String): WorkspaceEdit? {
        val file = index.findFileByUri(uri) ?: return null
        val target = resolveRenameTarget(file, uri, position) ?: return null

        // Validate new name
        if (newName.isBlank()) return null

        val edits = mutableMapOf<String, MutableList<TextEdit>>()

        when (target) {
            is RenameTarget.Method -> {
                collectMethodRenameEdits(target, newName, edits)
            }
            is RenameTarget.Field -> {
                collectFieldRenameEdits(target, newName, edits)
            }
            is RenameTarget.Label -> {
                collectLabelRenameEdits(target, newName, edits)
            }
        }

        return if (edits.isNotEmpty()) WorkspaceEdit(edits) else null
    }

    // --- Rename target resolution ---

    private sealed class RenameTarget(val currentName: String, val range: Range) {
        class Method(
            name: String,
            range: Range,
            val className: String,
            val descriptor: String
        ) : RenameTarget(name, range)

        class Field(
            name: String,
            range: Range,
            val className: String
        ) : RenameTarget(name, range)

        class Label(
            name: String,
            range: Range,
            val uri: String,
            val method: MethodDefinition
        ) : RenameTarget(name, range)
    }

    /**
     * Determine what symbol is at the cursor and whether it can be renamed.
     */
    private fun resolveRenameTarget(file: SmaliFile, uri: String, position: Position): RenameTarget? {
        val node = file.findNodeAt(position) ?: return null

        return when (node.first) {
            NodeType.METHOD -> {
                val method = node.second as MethodDefinition
                if (method.name == "<init>" || method.name == "<clinit>") return null
                val nameRange = findMethodNameRange(uri, method) ?: return null
                RenameTarget.Method(method.name, nameRange, file.classDefinition.name, method.descriptor)
            }

            NodeType.FIELD -> {
                val field = node.second as FieldDefinition
                val nameRange = findFieldNameRange(uri, field) ?: return null
                RenameTarget.Field(field.name, nameRange, file.classDefinition.name)
            }

            NodeType.LABEL -> {
                val label = node.second as LabelDefinition
                val containingMethod = file.methods.find { m ->
                    m.labels.values.any { it.name == label.name }
                } ?: return null
                // Label range covers ":labelName" — for rename we want just "labelName" (no colon)
                val nameRange = Range(
                    Position(label.range.start.line, label.range.start.character + 1),
                    label.range.end
                )
                RenameTarget.Label(label.name, nameRange, uri, containingMethod)
            }

            NodeType.INSTRUCTION -> {
                resolveInstructionRenameTarget(node.second as Instruction, file, uri, position)
            }

            NodeType.CLASS -> null // Class rename not supported
        }
    }

    /**
     * When cursor is on an instruction, determine which symbol (method/field) to rename.
     */
    private fun resolveInstructionRenameTarget(
        instruction: Instruction,
        file: SmaliFile,
        uri: String,
        position: Position
    ): RenameTarget? {
        val lineContent = index.getLineContent(uri, position.line) ?: return null

        return when (instruction) {
            is InvokeInstruction -> {
                if (instruction.methodName == "<init>" || instruction.methodName == "<clinit>") return null
                // Check if cursor is on the method name (after ->)
                val arrowIdx = lineContent.indexOf("->")
                if (arrowIdx < 0) return null
                val methodNameIdx = lineContent.indexOf(instruction.methodName, arrowIdx + 2)
                if (methodNameIdx < 0) return null
                val methodNameEnd = methodNameIdx + instruction.methodName.length
                if (position.character in methodNameIdx until methodNameEnd) {
                    val nameRange = Range(
                        Position(position.line, methodNameIdx),
                        Position(position.line, methodNameEnd)
                    )
                    RenameTarget.Method(
                        instruction.methodName, nameRange,
                        instruction.className, instruction.descriptor
                    )
                } else null
            }

            is FieldAccessInstruction -> {
                val arrowIdx = lineContent.indexOf("->")
                if (arrowIdx < 0) return null
                val fieldNameIdx = lineContent.indexOf(instruction.fieldName, arrowIdx + 2)
                if (fieldNameIdx < 0) return null
                val fieldNameEnd = fieldNameIdx + instruction.fieldName.length
                if (position.character in fieldNameIdx until fieldNameEnd) {
                    val nameRange = Range(
                        Position(position.line, fieldNameIdx),
                        Position(position.line, fieldNameEnd)
                    )
                    RenameTarget.Field(instruction.fieldName, nameRange, instruction.className)
                } else null
            }

            is JumpInstruction -> {
                // Find the label reference range within this instruction line
                val labelName = instruction.targetLabel
                val containingMethod = file.methods.find { m ->
                    m.labels.containsKey(labelName)
                } ?: return null
                // Use the labelRange which points to just the label reference
                val colonIdx = lineContent.indexOf(":$labelName")
                if (colonIdx < 0) return null
                val nameStart = colonIdx + 1 // skip the colon
                val nameEnd = nameStart + labelName.length
                if (position.character in colonIdx until nameEnd) {
                    val nameRange = Range(
                        Position(position.line, nameStart),
                        Position(position.line, nameEnd)
                    )
                    RenameTarget.Label(labelName, nameRange, uri, containingMethod)
                } else null
            }

            else -> null
        }
    }

    // --- Edit collection ---

    private fun collectMethodRenameEdits(
        target: RenameTarget.Method,
        newName: String,
        edits: MutableMap<String, MutableList<TextEdit>>
    ) {
        val className = target.className
        val methodName = target.currentName
        val descriptor = target.descriptor

        // 1. Rename declaration(s) — the method may exist in the target class + overrides in subclasses
        renameMethodDeclaration(className, methodName, descriptor, newName, edits)

        // 2. Also rename in all subclasses (polymorphic rename)
        if (!ClassUtils.isSDKClass(className)) {
            for (subClass in index.getAllSubclasses(className)) {
                renameMethodDeclaration(subClass, methodName, descriptor, newName, edits)
            }
        }

        // 3. Rename all call sites
        renameMethodCallSites(className, methodName, descriptor, newName, edits)
        if (!ClassUtils.isSDKClass(className)) {
            for (subClass in index.getAllSubclasses(className)) {
                renameMethodCallSites(subClass, methodName, descriptor, newName, edits)
            }
        }
    }

    private fun renameMethodDeclaration(
        className: String,
        methodName: String,
        descriptor: String,
        newName: String,
        edits: MutableMap<String, MutableList<TextEdit>>
    ) {
        val classFile = index.findClass(className) ?: return
        val method = classFile.methods.find { it.name == methodName && it.descriptor == descriptor } ?: return
        val nameRange = findMethodNameRange(classFile.uri, method) ?: return
        edits.getOrPut(classFile.uri) { mutableListOf() }.add(TextEdit(nameRange, newName))
    }

    private fun renameMethodCallSites(
        className: String,
        methodName: String,
        descriptor: String,
        newName: String,
        edits: MutableMap<String, MutableList<TextEdit>>
    ) {
        val usages = index.findMethodUsages(className, methodName, descriptor)
        for (usage in usages) {
            val lineContent = index.getLineContent(usage.uri, usage.range.start.line) ?: continue
            val arrowIdx = lineContent.indexOf("->")
            if (arrowIdx < 0) continue
            val nameIdx = lineContent.indexOf(methodName, arrowIdx + 2)
            if (nameIdx < 0) continue
            val nameRange = Range(
                Position(usage.range.start.line, nameIdx),
                Position(usage.range.start.line, nameIdx + methodName.length)
            )
            edits.getOrPut(usage.uri) { mutableListOf() }.add(TextEdit(nameRange, newName))
        }
    }

    private fun collectFieldRenameEdits(
        target: RenameTarget.Field,
        newName: String,
        edits: MutableMap<String, MutableList<TextEdit>>
    ) {
        val className = target.className
        val fieldName = target.currentName

        // 1. Rename declaration
        val classFile = index.findClass(className)
        if (classFile != null) {
            val field = classFile.fields.find { it.name == fieldName }
            if (field != null) {
                val nameRange = findFieldNameRange(classFile.uri, field)
                if (nameRange != null) {
                    edits.getOrPut(classFile.uri) { mutableListOf() }.add(TextEdit(nameRange, newName))
                }
            }
        }

        // 2. Rename all access sites
        val usages = index.findFieldUsages(className, fieldName)
        for (usage in usages) {
            val lineContent = index.getLineContent(usage.uri, usage.range.start.line) ?: continue
            val arrowIdx = lineContent.indexOf("->")
            if (arrowIdx < 0) continue
            val nameIdx = lineContent.indexOf(fieldName, arrowIdx + 2)
            if (nameIdx < 0) continue
            // Field name ends at the colon before the type descriptor
            val nameRange = Range(
                Position(usage.range.start.line, nameIdx),
                Position(usage.range.start.line, nameIdx + fieldName.length)
            )
            edits.getOrPut(usage.uri) { mutableListOf() }.add(TextEdit(nameRange, newName))
        }
    }

    private fun collectLabelRenameEdits(
        target: RenameTarget.Label,
        newName: String,
        edits: MutableMap<String, MutableList<TextEdit>>
    ) {
        val uri = target.uri
        val method = target.method
        val labelName = target.currentName
        val fileEdits = edits.getOrPut(uri) { mutableListOf() }

        // 1. Rename label definition (:labelName)
        val labelDef = method.labels[labelName]
        if (labelDef != null) {
            // Range covers ":labelName" — we want just "labelName" (after the colon)
            val nameRange = Range(
                Position(labelDef.range.start.line, labelDef.range.start.character + 1),
                labelDef.range.end
            )
            fileEdits.add(TextEdit(nameRange, newName))
        }

        // 2. Rename all label references in jump instructions
        for (instr in method.instructions) {
            if (instr is JumpInstruction && instr.targetLabel == labelName) {
                // Read the line to find exact position of :labelName
                val lineContent = index.getLineContent(uri, instr.range.start.line) ?: continue
                val colonIdx = lineContent.indexOf(":$labelName")
                if (colonIdx < 0) continue
                val nameStart = colonIdx + 1
                val nameEnd = nameStart + labelName.length
                val nameRange = Range(
                    Position(instr.range.start.line, nameStart),
                    Position(instr.range.start.line, nameEnd)
                )
                fileEdits.add(TextEdit(nameRange, newName))
            }
        }

        // 3. Rename label references in .catch directives
        // .catch Lxxx; {:try_start .. :try_end} :catch_label
        // Labels appear as :labelName in .catch — scan all lines in method range
        val startLine = method.range.start.line
        val endLine = method.range.end.line
        for (line in startLine..endLine) {
            val lineContent = index.getLineContent(uri, line) ?: continue
            if (!lineContent.contains(".catch")) continue
            // Find all occurrences of :labelName in this line
            var searchFrom = 0
            while (true) {
                val colonIdx = lineContent.indexOf(":$labelName", searchFrom)
                if (colonIdx < 0) break
                // Verify it's a complete match (next char is non-alphanumeric or end of string)
                val afterIdx = colonIdx + 1 + labelName.length
                if (afterIdx < lineContent.length && lineContent[afterIdx].isLetterOrDigit()) {
                    searchFrom = colonIdx + 1
                    continue
                }
                val nameStart = colonIdx + 1
                val nameEnd = nameStart + labelName.length
                fileEdits.add(TextEdit(
                    Range(Position(line, nameStart), Position(line, nameEnd)),
                    newName
                ))
                searchFrom = afterIdx
            }
        }
    }

    // --- Helper: find exact name range within declaration lines ---

    /**
     * Find the range of just the method NAME within its declaration line.
     * Line format: `.method [modifiers] methodName(descriptor)`
     */
    private fun findMethodNameRange(uri: String, method: MethodDefinition): Range? {
        val lineContent = index.getLineContent(uri, method.range.start.line) ?: return null
        // Method name is immediately followed by its descriptor
        val nameWithDesc = "${method.name}${method.descriptor}"
        val idx = lineContent.indexOf(nameWithDesc)
        if (idx < 0) return null
        return Range(
            Position(method.range.start.line, idx),
            Position(method.range.start.line, idx + method.name.length)
        )
    }

    /**
     * Find the range of just the field NAME within its declaration line.
     * Line format: `.field [modifiers] fieldName:type [= value]`
     */
    private fun findFieldNameRange(uri: String, field: FieldDefinition): Range? {
        val lineContent = index.getLineContent(uri, field.range.start.line) ?: return null
        // Field name is followed by ":type"
        val nameWithType = "${field.name}:${field.type}"
        val idx = lineContent.indexOf(nameWithType)
        if (idx < 0) return null
        return Range(
            Position(field.range.start.line, idx),
            Position(field.range.start.line, idx + field.name.length)
        )
    }
}
