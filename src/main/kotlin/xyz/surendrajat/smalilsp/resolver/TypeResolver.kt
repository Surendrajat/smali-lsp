package xyz.surendrajat.smalilsp.resolver

/**
 * Resolves type descriptors to class names.
 * 
 * Smali uses JVM type descriptors:
 * - Primitives: Z (boolean), B (byte), C (char), S (short), I (int), J (long), F (float), D (double), V (void)
 * - Objects: Lpackage/Class; (e.g., Ljava/lang/String;)
 * - Arrays: [elementType (e.g., [I, [[Ljava/lang/String;)
 * 
 * This resolver converts descriptors to human-readable forms and extracts class names.
 */
object TypeResolver {
    
    /**
     * Extract the class name from a type descriptor.
     * 
     * Examples:
     * - "Ljava/lang/String;" -> "Ljava/lang/String;"
     * - "[Ljava/lang/String;" -> "Ljava/lang/String;"
     * - "[[I" -> null (primitive array)
     * - "I" -> null (primitive)
     * 
     * @return Class name if descriptor references a class, null for primitives
     */
    fun extractClassName(descriptor: String): String? {
        if (descriptor.isEmpty()) return null
        
        // Skip array dimensions
        var i = 0
        while (i < descriptor.length && descriptor[i] == '[') {
            i++
        }
        
        // Check if what's left is a class reference
        if (i < descriptor.length && descriptor[i] == 'L') {
            // Find the semicolon
            val endIndex = descriptor.indexOf(';', i)
            if (endIndex > i) {
                return descriptor.substring(i, endIndex + 1)
            }
        }
        
        // Primitive type or malformed
        return null
    }
    
    /**
     * Extract all class names from a method descriptor.
     * 
     * Method descriptor format: (param1param2...)returnType
     * Example: "(Ljava/lang/String;I)Ljava/lang/Integer;" has String and Integer
     * 
     * @return Set of all class names referenced in the descriptor
     */
    fun extractClassNames(descriptor: String): Set<String> {
        val classes = mutableSetOf<String>()
        
        if (descriptor.isEmpty()) return classes
        
        // Find the opening parenthesis
        val openParen = descriptor.indexOf('(')
        if (openParen < 0) return classes
        
        // Find the closing parenthesis
        val closeParen = descriptor.indexOf(')', openParen)
        if (closeParen < 0) return classes
        
        // Extract parameter types
        val params = descriptor.substring(openParen + 1, closeParen)
        extractClassNamesFromTypeSequence(params, classes)
        
        // Extract return type
        val returnType = descriptor.substring(closeParen + 1)
        extractClassName(returnType)?.let { classes.add(it) }
        
        return classes
    }
    
    /**
     * Extract class names from a sequence of type descriptors (no separators).
     * 
     * Example: "ILjava/lang/String;[Ljava/lang/Integer;" -> [String, Integer]
     */
    private fun extractClassNamesFromTypeSequence(sequence: String, output: MutableSet<String>) {
        var i = 0
        while (i < sequence.length) {
            when (sequence[i]) {
                'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D', 'V' -> {
                    // Primitive, skip
                    i++
                }
                'L' -> {
                    // Object type - find semicolon
                    val semi = sequence.indexOf(';', i)
                    if (semi > i) {
                        output.add(sequence.substring(i, semi + 1))
                        i = semi + 1
                    } else {
                        i++
                    }
                }
                '[' -> {
                    // Array - continue to get element type
                    i++
                }
                else -> i++
            }
        }
    }
    
    /**
     * Check if a descriptor is a primitive type.
     */
    fun isPrimitive(descriptor: String): Boolean {
        return when {
            descriptor.isEmpty() -> false
            descriptor.startsWith('[') -> {
                // Array of primitives
                val elementType = descriptor.trimStart('[')
                elementType.length == 1 && elementType[0] in "ZBCSIFJDV"
            }
            else -> descriptor.length == 1 && descriptor[0] in "ZBCSIFJDV"
        }
    }
    
    /**
     * Get human-readable type name.
     * 
     * Examples:
     * - "I" -> "int"
     * - "Ljava/lang/String;" -> "java.lang.String"
     * - "[I" -> "int[]"
     * - "[[Ljava/lang/String;" -> "java.lang.String[][]"
     */
    fun toReadableName(descriptor: String): String {
        if (descriptor.isEmpty()) return "unknown"
        
        // Count array dimensions
        var arrayDims = 0
        var i = 0
        while (i < descriptor.length && descriptor[i] == '[') {
            arrayDims++
            i++
        }
        
        val brackets = "[]".repeat(arrayDims)
        
        // Get base type
        val baseType = when {
            i >= descriptor.length -> "unknown"
            descriptor[i] == 'L' -> {
                // Object type - convert Lpackage/Class; to package.Class
                val endIndex = descriptor.indexOf(';', i)
                if (endIndex > i) {
                    descriptor.substring(i + 1, endIndex).replace('/', '.')
                } else {
                    "unknown"
                }
            }
            descriptor[i] == 'Z' -> "boolean"
            descriptor[i] == 'B' -> "byte"
            descriptor[i] == 'C' -> "char"
            descriptor[i] == 'S' -> "short"
            descriptor[i] == 'I' -> "int"
            descriptor[i] == 'J' -> "long"
            descriptor[i] == 'F' -> "float"
            descriptor[i] == 'D' -> "double"
            descriptor[i] == 'V' -> "void"
            else -> "unknown"
        }
        
        return baseType + brackets
    }
}
