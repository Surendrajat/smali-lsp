package xyz.surendrajat.smalilsp.util

/**
 * Consolidated parser for Dalvik type descriptor sequences.
 *
 * Dalvik method descriptors have the form `(paramTypes)returnType` where types are:
 * - Primitives: Z, B, C, S, I, J, F, D, V
 * - Object: Lpackage/Class;
 * - Array: [type (e.g., [I, [[Ljava/lang/String;)
 *
 * This parser replaces 4 duplicate descriptor-walking implementations:
 * - ASTBuilder.parseParameters()
 * - InstructionSymbolExtractor.extractTypesFromDescriptor()
 * - HoverProvider.extractPrimitiveTypeAtPosition()
 * - TypeResolver.extractClassNamesFromTypeSequence()
 */
object DescriptorParser {

    /**
     * A parsed type element with its position within the source string.
     */
    data class TypeSpan(
        /** Full type string, e.g. "Ljava/lang/String;", "[I", "I" */
        val type: String,
        /** Start offset in source string (inclusive) */
        val start: Int,
        /** End offset in source string (exclusive) */
        val end: Int,
        /** True if this is an object reference type (Lpackage/Class;) or an object array */
        val isClassType: Boolean,
        /** True if this is a primitive type (I, J, Z, etc.) */
        val isPrimitive: Boolean,
        /** True if this is an array type ([I, [[Ljava/lang/String;, etc.) */
        val isArray: Boolean,
        /** For object/array-of-object types: the class name including L and ; */
        val className: String? = null
    )

    private val PRIMITIVES = charArrayOf('Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D', 'V')

    /**
     * Parse all types in a type sequence string. Handles the parameter portion of a
     * descriptor (e.g. `ILjava/lang/String;[I`) or a full descriptor including `()`.
     *
     * Characters `(`, `)`, and spaces are treated as delimiters and skipped.
     */
    fun parseTypeSequence(sequence: String): List<TypeSpan> {
        val types = mutableListOf<TypeSpan>()
        var i = 0

        while (i < sequence.length) {
            when (sequence[i]) {
                '(', ')', ' ' -> {
                    i++
                }
                in PRIMITIVES -> {
                    types.add(TypeSpan(
                        type = sequence[i].toString(),
                        start = i,
                        end = i + 1,
                        isClassType = false,
                        isPrimitive = true,
                        isArray = false
                    ))
                    i++
                }
                'L' -> {
                    val semi = sequence.indexOf(';', i)
                    if (semi > i) {
                        val type = sequence.substring(i, semi + 1)
                        types.add(TypeSpan(
                            type = type,
                            start = i,
                            end = semi + 1,
                            isClassType = true,
                            isPrimitive = false,
                            isArray = false,
                            className = type
                        ))
                        i = semi + 1
                    } else {
                        // Truncated object type (missing semicolon) — skip
                        i++
                    }
                }
                '[' -> {
                    val arrayStart = i
                    while (i < sequence.length && sequence[i] == '[') i++

                    if (i >= sequence.length) break // Truncated array descriptor

                    when (sequence[i]) {
                        'L' -> {
                            val semi = sequence.indexOf(';', i)
                            if (semi > i) {
                                val fullType = sequence.substring(arrayStart, semi + 1)
                                val className = sequence.substring(i, semi + 1)
                                types.add(TypeSpan(
                                    type = fullType,
                                    start = arrayStart,
                                    end = semi + 1,
                                    isClassType = true,
                                    isPrimitive = false,
                                    isArray = true,
                                    className = className
                                ))
                                i = semi + 1
                            } else {
                                i++ // Truncated
                            }
                        }
                        in PRIMITIVES -> {
                            val fullType = sequence.substring(arrayStart, i + 1)
                            types.add(TypeSpan(
                                type = fullType,
                                start = arrayStart,
                                end = i + 1,
                                isClassType = false,
                                isPrimitive = true,
                                isArray = true
                            ))
                            i++
                        }
                        else -> {
                            i++ // Invalid element type
                        }
                    }
                }
                else -> {
                    i++ // Unknown character, skip
                }
            }
        }
        return types
    }

    /**
     * Extract only class names (Lpackage/Class;) from a type sequence.
     * For array-of-object types, extracts the element class name.
     */
    fun extractClassNames(sequence: String): Set<String> {
        return parseTypeSequence(sequence)
            .mapNotNull { it.className }
            .toSet()
    }

    /**
     * Find the type span at a given offset within the sequence.
     * Returns null if offset doesn't fall within any type.
     */
    fun typeAtOffset(sequence: String, offset: Int): TypeSpan? {
        return parseTypeSequence(sequence).find { offset >= it.start && offset < it.end }
    }
}
