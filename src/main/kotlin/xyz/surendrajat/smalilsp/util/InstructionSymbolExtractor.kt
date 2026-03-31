package xyz.surendrajat.smalilsp.util

import xyz.surendrajat.smalilsp.core.*

/**
 * Extracts the specific symbol under cursor from instruction lines.
 * 
 * Problem:
 * - AST contains instructions with symbols (className, methodName, fieldName)
 * - AST does NOT contain character-level positions for each symbol within the line
 * - Example: "invoke-virtual {p0}, LClass;->method()V"
 *   - AST knows: className="LClass;", methodName="method"
 *   - AST doesn't know: "LClass;" is at char 24-31, "method" is at char 34-40
 * 
 * Solution:
 * - Use string operations (String.indexOf) to find symbol positions (NO regex per architecture)
 * - Match cursor position to determine which symbol user is hovering on
 * - Return symbol type (class, method, field) for appropriate navigation/hover
 * 
 * Architecture:
 * - ANTLR-Based: Uses AST instruction objects, not line parsing
 * - NO regex: Uses explicit string operations
 * - Position-aware: Handles multiple occurrences of same class in one instruction
 * 
 * Performance:
 * - O(n) where n = line length (typically <100 chars)
 * - 3-5 indexOf() calls per instruction
 * - Target: <1ms per extraction
 */
object InstructionSymbolExtractor {
    
    /**
     * Symbol under cursor.
     */
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
    
    /**
     * Extract symbol at cursor position from instruction line.
     * 
     * @param instruction Instruction from AST (InvokeInstruction, FieldAccessInstruction, TypeInstruction)
     * @param lineContent The actual line text from file
     * @param cursorChar Cursor character position in line (0-indexed)
     * @return Symbol under cursor, or null if cursor not on a symbol
     */
    fun extractSymbol(
        instruction: Instruction,
        lineContent: String,
        cursorChar: Int
    ): Symbol? {
        return when (instruction) {
            is InvokeInstruction -> extractFromInvoke(instruction, lineContent, cursorChar)
            is FieldAccessInstruction -> extractFromFieldAccess(instruction, lineContent, cursorChar)
            is TypeInstruction -> extractFromType(instruction, lineContent, cursorChar)
            else -> null
        }
    }
    
    /**
     * Extract from invoke-* instructions.
     * Format: invoke-virtual {v0}, LClassName;->methodName(Params)ReturnType
     * 
     * Symbols:
     * - LClassName; (class reference)
     * - methodName (method reference, includes className + descriptor)
     * - Params types (class references)
     * - ReturnType (class reference if starts with L or [)
     */
    private fun extractFromInvoke(
        instruction: InvokeInstruction,
        lineContent: String,
        cursorChar: Int
    ): Symbol? {
        // Find class name position
        val className = instruction.className
        val classIndex = lineContent.indexOf(className)
        if (classIndex >= 0) {
            val classEnd = classIndex + className.length - 1
            if (cursorChar >= classIndex && cursorChar <= classEnd) {
                return Symbol.ClassRef(className, instruction)
            }
        }
        
        // Find method name position (after "->")
        val arrowIndex = lineContent.indexOf("->")
        if (arrowIndex >= 0) {
            val methodName = instruction.methodName
            val methodNameIndex = lineContent.indexOf(methodName, arrowIndex + 2)
            if (methodNameIndex >= 0) {
                val methodEnd = methodNameIndex + methodName.length - 1
                if (cursorChar >= methodNameIndex && cursorChar <= methodEnd) {
                    return Symbol.MethodRef(
                        className = instruction.className,
                        methodName = instruction.methodName,
                        descriptor = instruction.descriptor,
                        instruction = instruction
                    )
                }
            }
        }
        
        // Find parameter/return types
        // Format: (Lparam1;Lparam2;)Lreturn;
        val descriptor = instruction.descriptor
        // Descriptor immediately follows method name: test(I)V
        val descriptorIndex = lineContent.indexOf(descriptor, arrowIndex + 2)
        if (descriptorIndex >= 0) {
            val types = extractTypesFromDescriptor(descriptor)
            var searchStart = descriptorIndex
            
            for (type in types) {
                val typeIndex = lineContent.indexOf(type, searchStart)
                if (typeIndex >= 0) {
                    val typeEnd = typeIndex + type.length - 1
                    if (cursorChar >= typeIndex && cursorChar <= typeEnd) {
                        return Symbol.ClassRef(type, instruction)
                    }
                    // Bug #3 Fix: Update searchStart to AFTER current match
                    // This ensures duplicate types are matched sequentially
                    searchStart = typeIndex + type.length
                }
            }
            
            // If cursor is anywhere in descriptor but NOT on a specific class type,
            // still navigate to the method (useful for primitive-only descriptors like ()V or (I)V)
            val descriptorEnd = descriptorIndex + descriptor.length - 1
            if (cursorChar >= descriptorIndex && cursorChar <= descriptorEnd) {
                return Symbol.MethodRef(
                    className = instruction.className,
                    methodName = instruction.methodName,
                    descriptor = instruction.descriptor,
                    instruction = instruction
                )
            }
        }
        
        return null
    }
    
    /**
     * Extract from field access instructions (iget, iput, sget, sput).
     * Format: iget-object v0, v1, LClassName;->fieldName:LFieldType;
     * 
     * Symbols:
     * - LClassName; (class reference)
     * - fieldName (field reference, includes className + type)
     * - LFieldType; (class reference)
     */
    private fun extractFromFieldAccess(
        instruction: FieldAccessInstruction,
        lineContent: String,
        cursorChar: Int
    ): Symbol? {
        // Find class name position
        val className = instruction.className
        val classIndex = lineContent.indexOf(className)
        if (classIndex >= 0) {
            val classEnd = classIndex + className.length - 1
            if (cursorChar >= classIndex && cursorChar <= classEnd) {
                return Symbol.ClassRef(className, instruction)
            }
        }
        
        // Find field name position (after "->")
        val arrowIndex = lineContent.indexOf("->")
        if (arrowIndex >= 0) {
            val fieldName = instruction.fieldName
            val fieldNameIndex = lineContent.indexOf(fieldName, arrowIndex + 2)
            if (fieldNameIndex >= 0) {
                val fieldEnd = fieldNameIndex + fieldName.length - 1
                if (cursorChar >= fieldNameIndex && cursorChar <= fieldEnd) {
                    return Symbol.FieldRef(
                        className = instruction.className,
                        fieldName = instruction.fieldName,
                        fieldType = instruction.fieldType,
                        instruction = instruction
                    )
                }
            }
        }
        
        // Find field type position (after ":")
        val colonIndex = lineContent.indexOf(":", arrowIndex + 2)
        if (colonIndex >= 0) {
            val fieldType = instruction.fieldType
            val typeIndex = lineContent.indexOf(fieldType, colonIndex)
            if (typeIndex >= 0) {
                val typeEnd = typeIndex + fieldType.length - 1
                if (cursorChar >= typeIndex && cursorChar <= typeEnd) {
                    // If it's a class type, return ClassRef
                    if (fieldType.startsWith("L") || fieldType.startsWith("[")) {
                        return Symbol.ClassRef(fieldType, instruction)
                    }
                    // If it's a primitive type, return FieldRef (navigate to field)
                    return Symbol.FieldRef(
                        className = instruction.className,
                        fieldName = instruction.fieldName,
                        fieldType = instruction.fieldType,
                        instruction = instruction
                    )
                }
            }
        }
        
        return null
    }
    
    /**
     * Extract from type instructions (new-instance, check-cast, instance-of, filled-new-array, new-array).
     * Format: new-instance v0, LClassName;
     *         filled-new-array {v0, v1}, [Lcom/example/Class;
     *         new-array v1, v0, [I
     * 
     * Symbols:
     * - LClassName; (class reference)
     * - [I, [[I (primitive array - for hover only, not navigable)
     * - [Lclass; (object array - navigable to element class)
     */
    private fun extractFromType(
        instruction: TypeInstruction,
        lineContent: String,
        cursorChar: Int
    ): Symbol? {
        val className = instruction.className
        
        // For object arrays, strip brackets for navigation to element class
        // [Ljava/lang/Object; -> Ljava/lang/Object;
        // [[Ljava/lang/String; -> Ljava/lang/String;
        val lookupClassName = if (className.startsWith("[") && className.contains("L")) {
            className.trimStart('[')
        } else {
            // Primitive arrays [I, [[I - keep as-is for hover
            className
        }
        
        val classIndex = lineContent.indexOf(lookupClassName)
        if (classIndex >= 0) {
            val classEnd = classIndex + lookupClassName.length - 1
            if (cursorChar >= classIndex && cursorChar <= classEnd) {
                return Symbol.ClassRef(lookupClassName, instruction)
            }
        }
        
        return null
    }
    
    /**
     * Extract all type references from method descriptor.
     * Example: "(Ljava/lang/String;[I)Landroid/os/Bundle;" 
     * Returns: ["Ljava/lang/String;", "[I", "Landroid/os/Bundle;"]
     * 
     * Extracts ALL types: objects (L...;), primitives (I,J,Z, etc), and arrays ([I, [[L...;, etc)
     * This allows hover to work on ANY type in a method descriptor.
     */
    /**
     * Extracts all type descriptors from a method descriptor or field reference.
     * 
     * This function parses Dalvik type descriptors using a state machine that handles:
     * - Object types (Lpackage/Class;)
     * - Primitive types (I, J, Z, B, C, S, F, D, V)
     * - Array types (single and multi-dimensional)
     * 
     * **State Machine Algorithm**:
     * ```
     * START → 'L' → scan to ';' → add class type → CONTINUE
     *      → '[' → count brackets → parse element type → HANDLE_ARRAY
     *      → primitive → add primitive → CONTINUE
     * ```
     * 
     * **Array Handling Strategy**:
     * - **Object arrays** (`[Ljava/lang/String;`): Extract ELEMENT TYPE only (`Ljava/lang/String;`)
     *   - Rationale: Enables navigation (go-to-definition on "String" should work)
     *   - HoverProvider separately handles showing array dimension info
     * - **Primitive arrays** (`[I`, `[[J`): Extract FULL TYPE including brackets
     *   - Rationale: Cannot navigate to primitive definitions, so hover needs complete type
     * 
     * **Examples**:
     * ```
     * Input: "(ILjava/lang/String;[Landroid/os/Bundle;)V"
     * Output: ["I", "Ljava/lang/String;", "Landroid/os/Bundle;", "V"]
     * 
     * Input: "([[Ljava/util/List;[I)Ljava/lang/Object;"
     * Output: ["Ljava/util/List;", "[I", "Ljava/lang/Object;"]
     * ```
     * 
     * **Error Handling**:
     * - Malformed descriptors: Skips invalid characters, continues parsing
     * - Truncated types: Returns types found up to truncation point
     * - Missing semicolons: Safely handles by scanning to end
     * 
     * @param descriptor The method descriptor (e.g., `(ILjava/lang/String;)V`) or field type
     * @return List of type descriptors in order of appearance
     */
    private fun extractTypesFromDescriptor(descriptor: String): List<String> {
        val types = mutableListOf<String>()
        var i = 0
        
        while (i < descriptor.length) {
            when (descriptor[i]) {
                'L' -> {
                    // Class type: Lpath/to/Class;
                    val end = descriptor.indexOf(';', startIndex = i)
                    if (end >= 0) {
                        types.add(descriptor.substring(i, end + 1))
                        i = end + 1
                    } else {
                        i++
                    }
                }
                '[' -> {
                    // Array type: [Lpath/to/Class; or [I or [[I
                    // Count array dimensions
                    val arrayStart = i
                    var arrayEnd = i
                    while (arrayEnd < descriptor.length && descriptor[arrayEnd] == '[') {
                        arrayEnd++
                    }
                    
                    if (arrayEnd < descriptor.length) {
                        when (descriptor[arrayEnd]) {
                            'L' -> {
                                // Array of objects: [Lpath/to/Class; or [[Ljava/lang/String;
                                // For object arrays, extract ELEMENT TYPE only (not array)
                                // This allows navigation to work (go-to-definition on String in [Ljava/lang/String;)
                                // For hover, HoverProvider will handle showing array info
                                val semicolon = descriptor.indexOf(';', startIndex = arrayEnd)
                                if (semicolon >= 0) {
                                    types.add(descriptor.substring(arrayEnd, semicolon + 1)) // Element type only
                                    i = semicolon + 1
                                } else {
                                    i = arrayEnd + 1
                                }
                            }
                            in "IJZBCSFDV" -> {
                                // Primitive array: [I, [[J, etc
                                // For primitive arrays, extract FULL type including brackets
                                // (can't navigate to primitive, so hover needs full type)
                                types.add(descriptor.substring(arrayStart, arrayEnd + 1))
                                i = arrayEnd + 1
                            }
                            else -> {
                                // Unknown character after brackets
                                i = arrayEnd + 1
                            }
                        }
                    } else {
                        // Brackets at end of string (malformed)
                        i = arrayEnd
                    }
                }
                in "IJZBCSFDV" -> {
                    // Primitive type: I (int), J (long), Z (boolean), etc
                    // DON'T extract - primitives are not navigable
                    // They'll be handled by the descriptor fallback logic
                    i++
                }
                else -> {
                    // Other character: (, ), or unknown - skip
                    i++
                }
            }
        }
        
        return types
    }
}
