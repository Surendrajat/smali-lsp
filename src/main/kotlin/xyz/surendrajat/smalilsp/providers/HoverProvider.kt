package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.*
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.resolver.TypeResolver
import xyz.surendrajat.smalilsp.util.InstructionSymbolExtractor
import java.io.File
import java.net.URI

/**
 * Provides hover information for Smali symbols.
 * 
 * Shows:
 * - Class hierarchy (superclass, interfaces)
 * - Method signatures
 * - Field types
 * - SDK class information
 * 
 * Format: Markdown for rich display in VS Code
 */
class HoverProvider(
    private val workspaceIndex: WorkspaceIndex
) {
    

    
    /**
     * Provide hover information at cursor position.
     * Uses AST directly from WorkspaceIndex (NO regex parsing).
     */
    fun provideHover(
        uri: String,
        position: Position
    ): Hover? {
        // Get parsed file from index
        val file = workspaceIndex.findFileByUri(uri) ?: return null
        
        // Find AST node at position
        val node = file.findNodeAt(position)
        
        return when {
            node == null -> {
                // Not on any specific node - check for special directives
                // BUG FIX: Hover on .super and .implements lines
                hoverForDirectiveLine(uri, position)
            }
            
            node.first == NodeType.CLASS -> {
                val classDef = node.second as ClassDefinition
                hoverForClass(classDef)
            }
            
            node.first == NodeType.METHOD -> {
                val method = node.second as MethodDefinition
                // Only show hover on method DECLARATION line
                if (position.line == method.range.start.line) {
                    // Check if cursor is on method name, descriptor type, or modifiers
                    // If file content not available (e.g., in tests), allow hover anywhere on line
                    val file = workspaceIndex.findFileByUri(uri)
                    val lineContent = if (file != null) getLineContent(file.uri, position.line) else null
                    
                    if (lineContent == null || isOnMethodName(lineContent, position)) {
                        hoverForMethod(method)
                    } else {
                        // Cursor not on method name - check if on class reference in signature
                        // Method format: .method modifiers name(Lclass;)Lreturn;
                        if (lineContent != null) {
                            // Try to extract class reference at cursor position
                            val classRef = extractClassRefAtPosition(lineContent, position.character)
                            if (classRef != null) {
                                // Cursor is on a class reference - show class hover
                                return hoverForClassReference(classRef)
                            }
                            
                            // Check if cursor is on a primitive type in the descriptor
                            val primitiveHover = extractPrimitiveTypeAtPosition(lineContent, position.character, method.descriptor)
                            if (primitiveHover != null) {
                                return primitiveHover
                            }
                        }
                        null
                    }
                } else {
                    null
                }
            }
            
            node.first == NodeType.INSTRUCTION -> {
                // Instruction-level hover - show info for symbol under cursor
                val instruction = node.second as Instruction
                hoverForInstruction(instruction, uri, position)
            }
            
            node.first == NodeType.FIELD -> {
                val field = node.second as FieldDefinition
                // Check if cursor is on field name, type descriptor, or modifiers
                // If file content not available (e.g., in tests), allow hover anywhere on line
                val file = workspaceIndex.findFileByUri(uri)
                val lineContent = if (file != null) getLineContent(file.uri, position.line) else null
                
                if (lineContent == null || isOnFieldName(lineContent, position)) {
                    hoverForField(field)
                } else {
                    // Check if cursor is on the field type (after colon)
                    // Field format: .field private name:Type
                    val colonIndex = lineContent?.indexOf(':') ?: -1
                    if (colonIndex >= 0 && position.character > colonIndex) {
                        // Extract type from field - could be primitive or class
                        val fieldType = field.type
                        
                        // Check if cursor is on a primitive type
                        val primitiveHover = lineContent?.let {
                            extractPrimitiveTypeAtPosition(it, position.character, fieldType, colonIndex + 1)
                        }
                        
                        // If not on primitive, check if on class type
                        if (primitiveHover == null && (fieldType.startsWith("L") || fieldType.startsWith("["))) {
                            // Cursor on class type - show class hover
                            hoverForClassReference(fieldType)
                        } else {
                            primitiveHover
                        }
                    } else {
                        null
                    }
                }
            }
            
            else -> null
        }
    }
    
    /**
     * Hover on .class directive - show class info.
     */
    private fun hoverForClass(classDef: ClassDefinition): Hover? {
        val file = workspaceIndex.findClass(classDef.name)
        if (file != null) {
            val md = buildString {
                append("**Class:** `${TypeResolver.toReadableName(file.classDefinition.name)}`\n\n")
                
                if (file.classDefinition.modifiers.isNotEmpty()) {
                    append("**Modifiers:** ${file.classDefinition.modifiers.joinToString(", ")}\n\n")
                }
                
                file.classDefinition.superClass?.let {
                    append("**Extends:** `${TypeResolver.toReadableName(it)}`\n\n")
                }
                
                if (file.classDefinition.interfaces.isNotEmpty()) {
                    append("**Implements:**\n")
                    file.classDefinition.interfaces.forEach { iface ->
                        append("- `${TypeResolver.toReadableName(iface)}`\n")
                    }
                    append("\n")
                }
                
                append("**Methods:** ${file.methods.size}  ")
                append("**Fields:** ${file.fields.size}")
            }
            
            return Hover(MarkupContent("markdown", md))
        }
        return null
    }
    

    
    /**
     * Get line content from file URI.
     * Reads file from disk on demand and returns the specified line.
     * Only used for hover on declaration lines (rare - <1% of hovers).
     * Cost: 1-10ms per call, but happens rarely so imperceptible to user.
     * Benefit: Save 0.5-2 GB memory for large apps.
     */
    private fun getLineContent(uri: String, lineIndex: Int): String? {
        return try {
            val path = java.net.URI(uri).path
            val file = java.io.File(path)
            if (!file.exists()) return null
            
            val lines = file.readLines()
            if (lineIndex < 0 || lineIndex >= lines.size) null
            else lines[lineIndex]
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Check if cursor position is on field name (not on modifiers like .field, public, etc.)
     * 
     * Example: .field public myField:Ljava/lang/String;
     *                        ^^^^^^^  <- should hover here
     *          ^^^^^^ ^^^^^^  <- should NOT hover on these
     */
    private fun isOnFieldName(lineContent: String, position: Position): Boolean {
        // Find field name in line
        // Field declaration format: .field [modifiers] fieldName:Type
        // We need to find where fieldName starts
        
        // Find the colon (type separator)
        val colonIndex = lineContent.indexOf(':')
        if (colonIndex == -1) return false
        
        // Find where field name starts (after last space before ':')
        var fieldNameStart = colonIndex - 1
        while (fieldNameStart >= 0 && lineContent[fieldNameStart] != ' ') {
            fieldNameStart--
        }
        fieldNameStart++ // Move to first char of field name
        
        val fieldNameEnd = colonIndex
        
        // Check if cursor is within field name range
        return position.character in fieldNameStart until fieldNameEnd
    }
    
    /**
     * Check if cursor position is on method name (not on modifiers like .method, public, etc.)
     * 
     * Example: .method public test()V
     *                        ^^^^  <- should hover here
     *          ^^^^^^^ ^^^^^^  <- should NOT hover on these
     */
    private fun isOnMethodName(lineContent: String, position: Position): Boolean {
        // Find method name in line
        // Method declaration format: .method [modifiers] methodName(descriptor)returnType
        // We need to find where methodName starts
        
        // Find the last occurrence of a space before the method name
        // Method name is right before the opening parenthesis
        val openParenIndex = lineContent.indexOf('(')
        if (openParenIndex == -1) return false
        
        // Find where method name starts (after last space before '(')
        var methodNameStart = openParenIndex - 1
        while (methodNameStart >= 0 && lineContent[methodNameStart] != ' ') {
            methodNameStart--
        }
        methodNameStart++ // Move to first char of method name
        
        val methodNameEnd = openParenIndex
        
        // Check if cursor is within method name range
        return position.character in methodNameStart until methodNameEnd
    }
    
    /**
     * Hover on .method directive - show method signature.
     */
    private fun hoverForMethod(method: MethodDefinition): Hover? {
        val md = buildString {
            append("**Method:** `${method.name}`\n\n")
            
            if (method.modifiers.isNotEmpty()) {
                append("**Modifiers:** ${method.modifiers.joinToString(", ")}\n\n")
            }
            
            val params = method.parameters.joinToString(", ") { param ->
                TypeResolver.toReadableName(param.type)
            }
            val returnType = TypeResolver.toReadableName(method.returnType)
                
            append("**Signature:** `($params)$returnType`\n\n")
            
            // Show parameters
            if (method.parameters.isNotEmpty()) {
                append("**Parameters:**\n")
                method.parameters.forEach { param ->
                    append("- `${TypeResolver.toReadableName(param.type)}`")
                    param.name?.let { append(" $it") }
                    append("\n")
                }
                append("\n")
            }
            
            append("**Returns:** `$returnType`")
        }
        
        return Hover(MarkupContent("markdown", md))
    }
    
    /**
     * Hover on .field directive - show field type.
     */
    private fun hoverForField(field: FieldDefinition): Hover? {
        val md = buildString {
            append("**Field:** `${field.name}`\n\n")
            
            if (field.modifiers.isNotEmpty()) {
                append("**Modifiers:** ${field.modifiers.joinToString(", ")}\n\n")
            }
            
            append("**Type:** `${TypeResolver.toReadableName(field.type)}`")
        }
        
        return Hover(MarkupContent("markdown", md))
    }
    
    /**
     * Hover on instruction - show info for symbol under cursor.
     * Uses InstructionSymbolExtractor to determine which symbol cursor is on.
     */
    private fun hoverForInstruction(instruction: Instruction, uri: String, position: Position): Hover? {
        // Read the actual line content to find which symbol cursor is on
        val lineContent = try {
            val file = File(URI(uri))
            if (!file.exists()) return null
            val lines = file.readLines()
            if (position.line >= lines.size) return null
            lines[position.line]
        } catch (e: Exception) {
            return null
        }
        
        // Extract symbol at cursor position
        val symbol = InstructionSymbolExtractor.extractSymbol(
            instruction,
            lineContent,
            position.character
        ) ?: return null
        
        // Show hover based on symbol type
        return when (symbol) {
            is InstructionSymbolExtractor.Symbol.ClassRef -> {
                hoverForClassReference(symbol.className)
            }
            is InstructionSymbolExtractor.Symbol.MethodRef -> {
                // Check if cursor is actually on a primitive type in the descriptor
                // InstructionSymbolExtractor returns MethodRef as fallback for primitives
                // (for navigation purposes), but for hover we want to show primitive info
                val primitiveHover = extractPrimitiveTypeAtPosition(
                    lineContent, 
                    position.character, 
                    symbol.descriptor
                )
                primitiveHover ?: hoverForMethodReference(symbol.className, symbol.methodName, symbol.descriptor)
            }
            is InstructionSymbolExtractor.Symbol.FieldRef -> {
                hoverForFieldReference(symbol.className, symbol.fieldName, symbol.fieldType)
            }
        }
    }
    
    /**
     * Hover on class reference in instruction.
     */
    private fun hoverForClassReference(className: String): Hover? {
        // Check if it's a primitive type first
        if (isPrimitiveType(className)) {
            return hoverForPrimitiveType(className)
        }
        
        // For arrays, extract the base element class name (preserves descriptor format)
        // [La0/a3; -> La0/a3;, [[Ljava/lang/String; -> Ljava/lang/String;
        val elementClassName = extractElementClassName(className)
        
        val file = workspaceIndex.findClass(elementClassName)
        if (file != null) {
            return hoverForClass(file.classDefinition)
        }
        
        // Class not found - show basic info with SDK indication
        val md = buildString {
            append("**Class:** `${TypeResolver.toReadableName(className)}`\n\n")
            if (isSDKClass(className)) {
                append("*SDK Class* - Part of Android SDK or Java standard library")
            } else {
                append("*Not found in workspace*")
            }
        }
        return Hover(MarkupContent("markdown", md))
    }
    
    /**
     * Extract element class name from array type descriptor.
     * Returns in DESCRIPTOR format (La0/a3;) for index lookup.
     * 
     * [La0/a3; -> La0/a3;
     * [[Ljava/lang/String; -> Ljava/lang/String;
     * Ljava/lang/Object; -> Ljava/lang/Object;
     */
    /**
     * Extracts the base element class name from an array type descriptor.
     * 
     * This function handles multi-dimensional arrays by stripping all leading '[' brackets
     * and returning the base element type in proper descriptor format.
     * 
     * **Algorithm**:
     * 1. Skip all leading '[' characters (array dimensions)
     * 2. Parse the element type:
     *    - Object types: Extract from 'L' to ';' (inclusive)
     *    - Primitives: Return as-is
     * 
     * **Examples**:
     * ```
     * [La0/a3;                → La0/a3;              (single-dim object array)
     * [[Ljava/lang/String;    → Ljava/lang/String;  (multi-dim object array)
     * [I                      → I                    (primitive array)
     * [[I                     → I                    (multi-dim primitive array)
     * Lcom/example/Class;     → Lcom/example/Class; (non-array, returned as-is)
     * ```
     * 
     * **Design Note**: The function preserves descriptor format (keeps 'L' and ';' for objects)
     * because the workspace index stores classes in descriptor format, not readable format.
     * 
     * @param className The type descriptor (may be array or non-array)
     * @return The base element type in descriptor format (e.g., "Ljava/lang/String;" not "java.lang.String")
     */
    private fun extractElementClassName(className: String): String {
        var i = 0
        // Skip array brackets
        while (i < className.length && className[i] == '[') {
            i++
        }
        
        // Return element type in descriptor format
        return when {
            i >= className.length -> className
            className[i] == 'L' -> {
                // Object type: already in descriptor format (Lpackage/Class;)
                val endIndex = className.indexOf(';', i)
                if (endIndex > i) {
                    className.substring(i, endIndex + 1)  // Keep L and ;
                } else {
                    className
                }
            }
            else -> className // Primitive or other
        }
    }
    
    /**
     * Check if a class is an SDK/system class.
     */
    private fun isSDKClass(className: String): Boolean {
        val baseType = className.trimStart('[')
        if (!baseType.startsWith("L")) {
            return true // Primitives are system types
        }
        return baseType.startsWith("Ljava/") ||
               baseType.startsWith("Ljavax/") ||
               baseType.startsWith("Landroid/") ||
               baseType.startsWith("Ldalvik/") ||
               baseType.startsWith("Lkotlin/") ||
               baseType.startsWith("Lkotlinx/")
    }
    
    /**
     * Hover on method reference in instruction.
     */
    private fun hoverForMethodReference(className: String, methodName: String, descriptor: String): Hover? {
        val file = workspaceIndex.findClass(className)
        if (file != null) {
            // Find the specific method
            val method = file.methods.find { 
                it.name == methodName && it.descriptor == descriptor 
            }
            if (method != null) {
                return hoverForMethod(method)
            }
        }
        
        // Method not found - show basic info
        val md = buildString {
            append("**Method:** `${className}.${methodName}`\n\n")
            append("**Descriptor:** `${descriptor}`\n\n")
            if (isSDKClass(className)) {
                append("*SDK Method* - Part of Android SDK or Java standard library")
            } else {
                append("*Not found in workspace*")
            }
        }
        return Hover(MarkupContent("markdown", md))
    }
    
    /**
     * Hover on field reference in instruction.
     */
    private fun hoverForFieldReference(className: String, fieldName: String, fieldType: String): Hover? {
        val file = workspaceIndex.findClass(className)
        if (file != null) {
            // Find the specific field
            val field = file.fields.find { it.name == fieldName }
            if (field != null) {
                return hoverForField(field)
            }
        }
        
        // Field not found - show basic info
        val md = buildString {
            append("**Field:** `${className}.${fieldName}`\n\n")
            append("**Type:** `${TypeResolver.toReadableName(fieldType)}`\n\n")
            if (isSDKClass(className)) {
                append("*SDK Field* - Part of Android SDK or Java standard library")
            } else {
                append("*Not found in workspace*")
            }
        }
        return Hover(MarkupContent("markdown", md))
    }
    
    /**
     * Check if a type string represents a primitive type.
     * Primitives: I (int), J (long), Z (boolean), B (byte), C (char), S (short), F (float), D (double), V (void)
     */
    private fun isPrimitiveType(type: String): Boolean {
        // Strip array markers
        val baseType = type.trimStart('[')
        // Primitive types are single characters that are not 'L' (which starts object types)
        return baseType.length == 1 && baseType[0] in "IJZBCSFDV"
    }
    
    /**
     * Provide hover information for primitive types.
     */
    private fun hoverForPrimitiveType(type: String): Hover {
        // Count array dimensions
        val arrayDim = type.takeWhile { it == '[' }.length
        val baseType = type.trimStart('[')
        val primitiveChar = baseType[0]
        
        val (typeName, description) = when (primitiveChar) {
            'I' -> "int" to "32-bit signed integer"
            'J' -> "long" to "64-bit signed integer"
            'Z' -> "boolean" to "true or false"
            'B' -> "byte" to "8-bit signed integer"
            'C' -> "char" to "16-bit Unicode character"
            'S' -> "short" to "16-bit signed integer"
            'F' -> "float" to "32-bit floating point"
            'D' -> "double" to "64-bit floating point"
            'V' -> "void" to "no return value"
            else -> "unknown" to "unknown primitive type"
        }
        
        val md = buildString {
            if (arrayDim > 0) {
                val brackets = "[]".repeat(arrayDim)
                append("**Primitive Array:** `${typeName}${brackets}`\n\n")
                append("**Base Type:** `${typeName}` ($description)\n\n")
                append("**Dimensions:** $arrayDim\n\n")
            } else {
                append("**Primitive Type:** `${typeName}`\n\n")
                append("**Description:** $description\n\n")
            }
            append("*Built-in type* - Part of the Dalvik/Java type system")
        }
        
        return Hover(MarkupContent("markdown", md))
    }
    
    /**
     * Extract primitive type at cursor position in a descriptor string.
     * 
     * Used for hovering on primitive types in:
     * - Method descriptors: `.method public test(IJZ)V`
     * - Field types: `.field private data:I`
     * 
     * @param lineContent The full line of text
     * @param cursorChar Cursor character position in the line (0-indexed)
     * @param descriptor The descriptor string (e.g., "(IJZ)V" or "I" or "[I")
     * @param startSearchFrom Optional start position in line to search for descriptor (for field types after colon)
     * @return Hover for primitive type if cursor is on a primitive, null otherwise
     */
    private fun extractPrimitiveTypeAtPosition(
        lineContent: String,
        cursorChar: Int,
        descriptor: String,
        startSearchFrom: Int = 0
    ): Hover? {
        // Find where the descriptor appears in the line
        val descriptorIndex = lineContent.indexOf(descriptor, startSearchFrom)
        if (descriptorIndex == -1) return null
        
        // Check if cursor is within the descriptor
        if (cursorChar < descriptorIndex || cursorChar >= descriptorIndex + descriptor.length) {
            return null
        }
        
        // Calculate position within descriptor
        val posInDescriptor = cursorChar - descriptorIndex
        
        // Parse descriptor to find type at position
        // Descriptor can be:
        // - Method: "(IJZ)V" - parameters in parens, return type after
        // - Field: "I" or "[I" or "Lclass;" - just the type
        
        var i = 0
        while (i < descriptor.length) {
            when (descriptor[i]) {
                '[' -> {
                    // Array - extract full array type with brackets
                    val arrayStart = i
                    var arrayBrackets = 0
                    while (i < descriptor.length && descriptor[i] == '[') {
                        arrayBrackets++
                        i++
                    }
                    
                    if (i < descriptor.length) {
                        when (descriptor[i]) {
                            'L' -> {
                                // Object array [Lclass;
                                val semicolonIndex = descriptor.indexOf(';', i)
                                if (semicolonIndex != -1) {
                                    i = semicolonIndex + 1
                                    // Check if cursor is on this array type
                                    if (posInDescriptor >= arrayStart && posInDescriptor < i) {
                                        val arrayType = descriptor.substring(arrayStart, i)
                                        // Check if it's a primitive array (shouldn't happen with L but defensive)
                                        return null // Object array, not primitive
                                    }
                                }
                            }
                            in "IJZBCSFDV" -> {
                                // Primitive array [I, [[J, etc
                                i++
                                if (posInDescriptor >= arrayStart && posInDescriptor < i) {
                                    val primitiveArray = descriptor.substring(arrayStart, i)
                                    return hoverForPrimitiveType(primitiveArray)
                                }
                            }
                        }
                    }
                }
                'L' -> {
                    // Object type Lclass;
                    val semicolonIndex = descriptor.indexOf(';', i)
                    if (semicolonIndex != -1) {
                        i = semicolonIndex + 1
                        // Object type, not primitive
                    } else {
                        i++
                    }
                }
                in "IJZBCSFDV" -> {
                    // Primitive type
                    if (posInDescriptor == i) {
                        val primitiveType = descriptor[i].toString()
                        return hoverForPrimitiveType(primitiveType)
                    }
                    i++
                }
                '(', ')', ' ' -> {
                    // Delimiter
                    i++
                }
                else -> {
                    // Unknown character, skip
                    i++
                }
            }
        }
        
        return null
    }
    
    /**
     * Extract class reference at cursor position in a line.
     * Finds L....; pattern that contains the cursor position.
     * 
     * @param lineContent The line text
     * @param cursorChar Cursor character position (0-indexed)
     * @return Class reference (e.g., "Lcom/example/Class;") or null if cursor not on a class ref
     */
    private fun extractClassRefAtPosition(lineContent: String, cursorChar: Int): String? {
        // Find all class references in the line
        val classPattern = Regex("""L[a-zA-Z0-9/\$]+;""")
        for (match in classPattern.findAll(lineContent)) {
            // Check if cursor is within this class reference
            if (cursorChar >= match.range.first && cursorChar <= match.range.last) {
                return match.value
            }
        }
        return null
    }
    
    /**
     * Hover for directive lines that don't have AST nodes (.super, .implements).
     * BUG FIX: These directives don't have separate AST nodes, so findNodeAt() returns null.
     * This fallback reads the line content and provides hover info for class references.
     */
    private fun hoverForDirectiveLine(uri: String, position: Position): Hover? {
        try {
            val file = File(URI(uri))
            if (!file.exists()) return null
            
            val lines = file.readLines()
            if (position.line >= lines.size) return null
            
            val line = lines[position.line].trim()
            
            // Check for .super directive
            if (line.startsWith(".super ")) {
                val className = line.substring(7).trim()
                return hoverForClassReference(className)
            }
            
            // Check for .implements directive
            if (line.startsWith(".implements ")) {
                val className = line.substring(12).trim()
                return hoverForClassReference(className)
            }
            
            // Check for .catch directive
            // Format: .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0
            if (line.startsWith(".catch ")) {
                // Extract class name - find first 'L' and ending ';'
                val catchClassStart = line.indexOf('L', 7) // After ".catch "
                if (catchClassStart >= 0) {
                    val catchClassEnd = line.indexOf(';', catchClassStart)
                    if (catchClassEnd > catchClassStart) {
                        val className = line.substring(catchClassStart, catchClassEnd + 1)
                        // Check if cursor is anywhere on the class reference
                        if (position.character >= catchClassStart && position.character <= catchClassEnd) {
                            return hoverForClassReference(className)
                        }
                    }
                }
            }
            
            // Fallback: Check for any class reference in the line
            // This handles .annotation, .parameter, and other directives with class references
            val classPattern = Regex("""L[a-zA-Z0-9/$]+;""")
            val matches = classPattern.findAll(line)
            for (match in matches) {
                if (position.character >= match.range.first && position.character <= match.range.last) {
                    return hoverForClassReference(match.value)
                }
            }
            
            return null
        } catch (e: Exception) {
            return null
        }
    }
    
}
