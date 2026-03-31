package xyz.surendrajat.smalilsp.util

import xyz.surendrajat.smalilsp.core.FieldAccessInstruction
import xyz.surendrajat.smalilsp.core.Instruction
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.TypeInstruction

/**
 * Regex-based implementation of instruction symbol extraction.
 * Uses pre-compiled regex patterns for performance.
 * 
 * This is an alternative implementation to compare against the string-based approach.
 */
object InstructionSymbolExtractorRegex {
    
    sealed class Symbol {
        data class ClassRef(val className: String, val instruction: Instruction) : Symbol()
        data class MethodRef(
            val className: String,
            val methodName: String,
            val descriptor: String,
            val instruction: Instruction
        ) : Symbol()
        data class FieldRef(
            val className: String,
            val fieldName: String,
            val fieldType: String,
            val instruction: Instruction
        ) : Symbol()
    }

    // Pre-compiled regex patterns for performance
    // Match class reference: Lpackage/name/ClassName;
    private val CLASS_PATTERN = Regex("""(L[a-zA-Z0-9/${'$'}_]+;)""")
    
    // Match invoke instruction: invoke-* {registers}, ClassName;->methodName(descriptor)returnType
    private val INVOKE_PATTERN = Regex(
        """invoke-(?:virtual|direct|static|interface|super)\s+\{[^}]*\}\s*,\s*(L[^;]+;)->([\w<>]+)\(([^)]*)\)(.+)"""
    )
    
    // Match field access: iget/iput/sget/sput-* registers, ClassName;->fieldName:fieldType
    private val FIELD_PATTERN = Regex(
        """[is](?:get|put)-\w+\s+[^,]+,\s*(L[^;]+;)->([\w${'$'}]+):(L[^;]+;)"""
    )
    
    // Match type instruction: new-instance/check-cast/instance-of register, ClassName;
    private val TYPE_PATTERN = Regex(
        """(?:new-instance|check-cast|instance-of)\s+\w+,\s*(L[^;]+;)"""
    )
    
    // Match class type in descriptor (non-primitive, non-array)
    private val DESCRIPTOR_TYPE_PATTERN = Regex("""(L[a-zA-Z0-9/${'$'}_]+;)""")

    /**
     * Extract the symbol at the cursor position from an instruction line.
     * 
     * @param instruction The parsed instruction object
     * @param lineContent The full line content as text
     * @param cursorChar The character position of the cursor (0-indexed)
     * @return Symbol if cursor is on a navigable symbol, null otherwise
     */
    fun extractSymbol(instruction: Instruction, lineContent: String, cursorChar: Int): Symbol? {
        return when (instruction) {
            is InvokeInstruction -> extractFromInvoke(instruction, lineContent, cursorChar)
            is FieldAccessInstruction -> extractFromFieldAccess(instruction, lineContent, cursorChar)
            is TypeInstruction -> extractFromType(instruction, lineContent, cursorChar)
            else -> null
        }
    }

    /**
     * Extract symbol from invoke instructions (invoke-virtual, invoke-static, etc.)
     * Can extract: class name, method name, parameter types, return type
     */
    private fun extractFromInvoke(
        instruction: InvokeInstruction,
        lineContent: String,
        cursorChar: Int
    ): Symbol? {
        val match = INVOKE_PATTERN.find(lineContent) ?: return null
        
        val (classNameMatch, methodNameMatch, paramsMatch, returnTypeMatch) = match.destructured
        
        // Check if cursor is on class name
        val classRange = match.groups[1]!!.range
        if (cursorChar in classRange) {
            return Symbol.ClassRef(classNameMatch, instruction)
        }
        
        // Check if cursor is on method name
        val methodRange = match.groups[2]!!.range
        if (cursorChar in methodRange) {
            return Symbol.MethodRef(
                classNameMatch,
                methodNameMatch,
                "$paramsMatch$returnTypeMatch",
                instruction
            )
        }
        
        // Check parameter types
        val paramTypes = extractTypesFromDescriptor(paramsMatch)
        val allTypes = paramTypes + listOfNotNull(
            if (returnTypeMatch.startsWith("L")) returnTypeMatch else null
        )
        
        // For each type in descriptor, find its position and check cursor
        var searchStart = match.range.start
        for (type in allTypes) {
            val typeMatch = CLASS_PATTERN.find(lineContent, searchStart) ?: continue
            val typeRange = typeMatch.range
            
            if (cursorChar in typeRange) {
                return Symbol.ClassRef(type, instruction)
            }
            
            searchStart = typeRange.last + 1
        }
        
        return null
    }

    /**
     * Extract symbol from field access instructions (iget, iput, sget, sput)
     * Can extract: class name, field name, field type
     */
    private fun extractFromFieldAccess(
        instruction: FieldAccessInstruction,
        lineContent: String,
        cursorChar: Int
    ): Symbol? {
        val match = FIELD_PATTERN.find(lineContent) ?: return null
        
        val (classNameMatch, fieldNameMatch, fieldTypeMatch) = match.destructured
        
        // Check if cursor is on class name
        val classRange = match.groups[1]!!.range
        if (cursorChar in classRange) {
            return Symbol.ClassRef(classNameMatch, instruction)
        }
        
        // Check if cursor is on field name
        val fieldRange = match.groups[2]!!.range
        if (cursorChar in fieldRange) {
            return Symbol.FieldRef(classNameMatch, fieldNameMatch, fieldTypeMatch, instruction)
        }
        
        // Check if cursor is on field type
        val typeRange = match.groups[3]!!.range
        if (cursorChar in typeRange) {
            return Symbol.ClassRef(fieldTypeMatch, instruction)
        }
        
        return null
    }

    /**
     * Extract symbol from type instructions (new-instance, check-cast, instance-of)
     * Can extract: class name
     */
    private fun extractFromType(
        instruction: TypeInstruction,
        lineContent: String,
        cursorChar: Int
    ): Symbol? {
        val match = TYPE_PATTERN.find(lineContent) ?: return null
        
        val classNameMatch = match.groups[1]!!.value
        val classRange = match.groups[1]!!.range
        
        // Skip primitive arrays (e.g., [I, [B, [Z)
        if (classNameMatch.startsWith("[") && classNameMatch.length == 2) {
            return null
        }
        
        if (cursorChar in classRange) {
            return Symbol.ClassRef(classNameMatch, instruction)
        }
        
        return null
    }

    /**
     * Extract all class types from a method descriptor.
     * Skips primitive types and primitive arrays.
     * 
     * Example: "(Ljava/lang/String;I[Ljava/lang/Object;)" -> ["Ljava/lang/String;", "Ljava/lang/Object;"]
     */
    private fun extractTypesFromDescriptor(descriptor: String): List<String> {
        return DESCRIPTOR_TYPE_PATTERN.findAll(descriptor)
            .map { it.value }
            .toList()
    }
}
