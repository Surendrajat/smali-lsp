package xyz.surendrajat.smalilsp.util

/**
 * Utility functions for class name handling.
 * 
 * Centralized to avoid code duplication across providers.
 */
object ClassUtils {
    
    /**
     * Android/Java SDK class prefixes that don't exist in the workspace.
     * 
     * These classes are part of the Android framework or Java standard library
     * and should be treated specially (e.g., no definition navigation, filtered references).
     */
    private val SDK_PREFIXES = listOf(
        "Ljava/",
        "Ljavax/",
        "Landroid/",
        "Landroidx/",
        "Lkotlin/",
        "Lkotlinx/",
        "Ldalvik/",
        "Lcom/google/android/",
        "Lcom/android/",
        "Lsun/",
        "Ljdk/",
        "Lorg/json/",
        "Lorg/xml/",
        "Lorg/w3c/",
        "Lorg/apache/"
    )
    
    /**
     * Check if a class name is an SDK/system class.
     * 
     * SDK classes are part of Android framework or Java standard library.
     * They won't be found in the workspace index, so we handle them specially:
     * - Definition navigation: blocked (no source available)
     * - Find references: direct calls only (no polymorphic matching)
     * - Hover: show "SDK Class" indicator
     * 
     * Handles array types: [Ljava/lang/String; → checks java/lang/String;
     * Handles primitives: [I, Z, B → returns true (primitives are system types)
     * 
     * @param className Smali class name (e.g., "Ljava/lang/Object;", "[Ljava/lang/String;")
     * @return true if this is an SDK class or primitive
     */
    fun isSDKClass(className: String): Boolean {
        // Strip array brackets to get base type
        val baseType = className.trimStart('[')
        
        // Primitives (I, Z, B, etc.) are system types - no 'L' prefix
        if (!baseType.startsWith("L")) {
            return true
        }
        
        return SDK_PREFIXES.any { baseType.startsWith(it) }
    }
    
    /**
     * Extract simple class name from full Smali type.
     * 
     * Examples:
     * - "Lcom/example/MyClass;" -> "MyClass"
     * - "Lcom/example/Outer$Inner;" -> "Inner"
     * - "La/b/c/q;" -> "q"
     * - "[Ljava/lang/String;" -> "String" (strips array brackets)
     * 
     * @param fullName Full Smali class name
     * @return Simple class name without package or L; prefix/suffix
     */
    fun extractSimpleName(fullName: String): String {
        // Strip array brackets
        val withoutArrays = fullName.trimStart('[')
        
        // Remove leading 'L' and trailing ';'
        val cleaned = withoutArrays.removePrefix("L").removeSuffix(";")
        
        // Get the last part after '/' (package separator)
        val afterSlash = cleaned.substringAfterLast('/')
        
        // Get the last part after '$' (inner class separator) if present
        return afterSlash.substringAfterLast('$')
    }
    
    /**
     * Extract full class name from a type, stripping array notation.
     * 
     * Examples:
     * - "Ljava/lang/String;" -> "Ljava/lang/String;"
     * - "[Ljava/lang/String;" -> "Ljava/lang/String;"
     * - "[[I" -> null (primitive array)
     * 
     * @param type Smali type descriptor
     * @return Class name without array notation, or null for primitives
     */
    fun extractClassName(type: String): String? {
        val withoutArrays = type.trimStart('[')
        if (withoutArrays.startsWith("L") && withoutArrays.endsWith(";")) {
            return withoutArrays
        }
        return null
    }
    
    /**
     * Convert Smali type to human-readable format.
     * 
     * Examples:
     * - "I" -> "int"
     * - "Ljava/lang/String;" -> "java.lang.String"
     * - "[I" -> "int[]"
     * - "[[Ljava/lang/String;" -> "java.lang.String[][]"
     * 
     * @param smaliType Smali type descriptor
     * @return Human-readable type name
     */
    fun toReadableType(smaliType: String): String {
        // Count array dimensions
        var arrayDimensions = 0
        var type = smaliType
        while (type.startsWith("[")) {
            arrayDimensions++
            type = type.substring(1)
        }
        
        // Convert base type
        val baseType = when (type) {
            "V" -> "void"
            "Z" -> "boolean"
            "B" -> "byte"
            "S" -> "short"
            "C" -> "char"
            "I" -> "int"
            "J" -> "long"
            "F" -> "float"
            "D" -> "double"
            else -> {
                // Object type: Lcom/example/Class; -> com.example.Class
                if (type.startsWith("L") && type.endsWith(";")) {
                    type.substring(1, type.length - 1).replace('/', '.')
                } else {
                    type // Unknown type, return as-is
                }
            }
        }
        
        // Add array brackets
        return baseType + "[]".repeat(arrayDimensions)
    }
}
