package xyz.surendrajat.smalilsp.core

import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Position

/**
 * Core data structures for the AST.
 * Simple, immutable, testable.
 */

data class SmaliFile(
    val uri: String,
    val classDefinition: ClassDefinition,
    val fields: List<FieldDefinition>,
    val methods: List<MethodDefinition>
) {
    /**
     * Find AST node at given position.
     * Used by LSP providers to determine context for hover, goto-def, etc.
     * 
     * Strategy: Check most specific nodes first (instructions > methods/fields > class).
     * This is because class range typically encompasses the entire file.
     * 
     * @return Pair of (nodeType, node) or null if not found
     */
    fun findNodeAt(position: Position): Pair<NodeType, Any>? {
        // Check instructions first (most specific)
        for (method in methods) {
            for (instruction in method.instructions) {
                if (instruction.range.contains(position)) {
                    return Pair(NodeType.INSTRUCTION, instruction)
                }
            }
        }
        
        // Check methods (more specific than class)
        for (method in methods) {
            if (method.range.contains(position)) {
                return Pair(NodeType.METHOD, method)
            }
        }
        
        // Check fields (more specific than class)
        for (field in fields) {
            if (field.range.contains(position)) {
                return Pair(NodeType.FIELD, field)
            }
        }
        
        // Fall back to class definition (least specific - covers whole file)
        if (classDefinition.range.contains(position)) {
            return Pair(NodeType.CLASS, classDefinition)
        }
        
        return null
    }    /**
     * Get line content from position (when we need to parse references within a line).
     * NOTE: This is a temporary bridge until we have full instruction-level AST.
     */
    fun getLineContent(position: Position, fileContent: String): String {
        val lines = fileContent.lines()
        return if (position.line < lines.size) lines[position.line] else ""
    }
}

enum class NodeType {
    CLASS,
    METHOD,
    FIELD,
    INSTRUCTION
}

/**
 * Extension function to check if Range contains Position
 */
fun Range.contains(position: Position): Boolean {
    val startLine = this.start.line
    val startChar = this.start.character
    val endLine = this.end.line
    val endChar = this.end.character
    
    val line = position.line
    val char = position.character
    
    // Position is before range
    if (line < startLine) return false
    if (line == startLine && char < startChar) return false
    
    // Position is after range
    if (line > endLine) return false
    if (line == endLine && char > endChar) return false
    
    return true
}

data class ClassDefinition(
    val name: String,  // e.g., "Lcom/example/MyClass;"
    val range: Range,
    val modifiers: Set<String>,  // public, final, etc.
    val superClass: String?,  // null for Object
    val interfaces: List<String>,
    val superClassRange: Range? = null,  // Range of .super directive line
    val interfaceRanges: Map<String, Range> = emptyMap()  // Map of interface name to .implements directive range
)

data class MethodDefinition(
    val name: String,  // e.g., "myMethod" or "<init>"
    val descriptor: String,  // e.g., "(Ljava/lang/String;)V"
    val range: Range,
    val modifiers: Set<String>,
    val parameters: List<Parameter>,
    val returnType: String,
    val instructions: List<Instruction> = emptyList()  // Instruction-level AST
)

data class Parameter(
    val type: String,
    val name: String?  // Can be null in smali
)

data class FieldDefinition(
    val name: String,
    val type: String,
    val range: Range,
    val modifiers: Set<String>
)

/**
 * References to symbols (for goto definition, find references)
 */
data class SymbolReference(
    val symbolName: String,
    val symbolType: SymbolType,
    val range: Range
)

enum class SymbolType {
    CLASS,
    METHOD,
    FIELD,
    LABEL,
    PARAMETER
}

/**
 * Instruction-level AST for navigation.
 * Only critical instructions that reference symbols (for goto definition).
 */
sealed class Instruction(
    open val range: Range
)

/**
 * Method invocation: invoke-virtual, invoke-static, invoke-direct, invoke-interface
 */
data class InvokeInstruction(
    val opcode: String,  // invoke-virtual, invoke-static, etc.
    val className: String,  // Lcom/example/MyClass;
    val methodName: String,
    val descriptor: String,
    override val range: Range
) : Instruction(range)

/**
 * Field access: iget, iput, sget, sput
 */
data class FieldAccessInstruction(
    val opcode: String,  // iget, iput, sget, sput
    val className: String,
    val fieldName: String,
    val fieldType: String,
    override val range: Range
) : Instruction(range)

/**
 * Type reference: new-instance, check-cast, instance-of, const-class
 */
data class TypeInstruction(
    val opcode: String,  // new-instance, check-cast, instance-of, const-class
    val className: String,
    override val range: Range
) : Instruction(range)

/**
 * Helper to create Range objects
 */
fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int): Range {
    return Range(
        Position(startLine, startChar),
        Position(endLine, endChar)
    )
}
