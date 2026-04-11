package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.*
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.resolver.TypeResolver
import xyz.surendrajat.smalilsp.util.ClassUtils
import xyz.surendrajat.smalilsp.util.InstructionSymbolExtractor
import org.slf4j.LoggerFactory

/**
 * Provides "Go to Definition" functionality - AST-based implementation.
 * 
 * Architecture:
 * - Phase 6: Declaration-to-declaration navigation (class, method, field)
 * - Phase 6.5: Usage-to-declaration (invoke-virtual, iget/iput, etc.)
 * 
 * Current scope: Declaration-level only
 * - User clicks on class name in .class or .super → Find class definition
 * - User clicks on method name in .method → Find method definition
 * - User clicks on field name in .field → Find field definition
 * - User clicks on type in method descriptor → Find class definition
 * 
 * Implementation:
 * - Uses AST directly from WorkspaceIndex (NO regex parsing)
 * - Fast: O(1) lookup for indexed symbols
 * - Accurate: Uses parsed Range information
 * 
 * Future scope (Phase 6.5):
 * - User clicks on invoke-virtual → Find method definition
 * - User clicks on iget → Find field definition
 * - Requires instruction-level AST
 */
class DefinitionProvider(
    private val workspaceIndex: WorkspaceIndex
) {
    private val logger = LoggerFactory.getLogger(DefinitionProvider::class.java)
    
    companion object {
        // Regex for temporary bridge until instruction-level AST
        // Only used for extracting class references from within lines
        private val CLASS_PATTERN = Regex("""L[^;\s]+;""")
    }
    
    /**
     * Find definition for symbol at cursor position.
     * 
     * @param uri Document URI
     * @param position Cursor position
     * @return List of definition locations (can be multiple for overloads)
     */
    fun findDefinition(
        uri: String,
        position: Position
    ): List<Location> {
        // Get parsed file from index
        val file = workspaceIndex.findFileByUri(uri) ?: run {
            logger.warn("File not found in index: $uri")
            return emptyList()
        }
        
        // Find AST node at position
        val node = file.findNodeAt(position)
        
        return when {
            node == null -> {
                // Not on any node, might be on a reference within a line
                // This is temporary until we have instruction-level AST
                findClassReferenceAtPosition(file, position, uri)
            }
            
            node.first == NodeType.INSTRUCTION -> {
                // On instruction - navigate based on symbol under cursor
                handleInstructionNavigation(node.second as Instruction, uri, position)
            }
            
            node.first == NodeType.CLASS -> {
                // On class definition - check if cursor is on .super/.implements directive
                // First try reading actual file content for precise navigation
                val classRef = findClassReferenceAtPosition(file, position, uri)
                if (classRef.isNotEmpty()) {
                    return classRef
                }
                
                // Fallback: If no file content (e.g., virtual URIs in tests), use ClassDefinition
                // This preserves old behavior for tests while supporting directive-based navigation
                val classDef = node.second as ClassDefinition
                classDef.superClass?.let { findClassDefinition(it) } ?: emptyList()
            }
            
            node.first == NodeType.METHOD -> {
                // On method definition - only navigate to types in signature
                // Don't navigate to method itself (already at definition)
                val method = node.second as MethodDefinition
                findTypeInMethodSignature(method, position, uri)
            }
            
            node.first == NodeType.FIELD -> {
                // On field definition - only navigate to types
                // Don't navigate to field itself (already at definition)
                val field = node.second as FieldDefinition
                findTypeInFieldDeclaration(field, position, uri)
            }
            
            node.first == NodeType.LABEL -> {
                // On label definition (e.g., :cond_0)
                // User is already at the definition, return its location
                val labelDef = node.second as LabelDefinition
                listOf(Location(uri, labelDef.range))
            }
            
            else -> emptyList()
        }
    }
    
    /**
     * Handle navigation for instruction nodes.
     * Uses InstructionSymbolExtractor to determine which symbol cursor is on.
     */
    private fun handleInstructionNavigation(
        instruction: Instruction,
        uri: String,
        position: Position
    ): List<Location> {
        // Handle JumpInstruction (goto, if-*) - navigate to label definition
        if (instruction is JumpInstruction) {
            return findLabelDefinition(instruction.targetLabel, uri)
        }
        
        val lineContent = workspaceIndex.getLineContent(uri, position.line)
            ?: return handleInstructionNavigationFallback(instruction)
        
        // Extract symbol at cursor position; fall back to primary symbol if cursor is on opcode/whitespace
        val symbol = InstructionSymbolExtractor.extractSymbol(
            instruction,
            lineContent,
            position.character
        ) ?: return handleInstructionNavigationFallback(instruction)
        
        // Navigate based on symbol type
        return when (symbol) {
            is InstructionSymbolExtractor.Symbol.ClassRef -> {
                findClassDefinition(symbol.className)
            }
            is InstructionSymbolExtractor.Symbol.MethodRef -> {
                val locations = workspaceIndex.findMethod(
                    symbol.className,
                    symbol.methodName,
                    symbol.descriptor
                )
                locations.toList()
            }
            is InstructionSymbolExtractor.Symbol.FieldRef -> {
                val location = workspaceIndex.findField(
                    symbol.className,
                    symbol.fieldName
                )
                if (location != null) listOf(location) else emptyList()
            }
        }
    }
    
    /**
     * Find label definition within the same method.
     * Labels are local to the method they're defined in.
     */
    private fun findLabelDefinition(labelName: String, uri: String): List<Location> {
        val file = workspaceIndex.findFileByUri(uri) ?: return emptyList()
        
        // Search all methods for the label definition
        for (method in file.methods) {
            val labelDef = method.labels[labelName]
            if (labelDef != null) {
                return listOf(Location(uri, labelDef.range))
            }
        }
        
        logger.debug("Label not found: $labelName in $uri")
        return emptyList()
    }
    
    /**
     * Fallback navigation when line content can't be read.
     * Navigates to first symbol in instruction (old behavior).
     */
    private fun handleInstructionNavigationFallback(instruction: Instruction): List<Location> {
        return when (instruction) {
            is InvokeInstruction -> {
                // Navigate to method definition
                val locations = workspaceIndex.findMethod(
                    instruction.className,
                    instruction.methodName,
                    instruction.descriptor
                )
                locations.toList()
            }
            
            is FieldAccessInstruction -> {
                // Navigate to field definition
                val location = workspaceIndex.findField(
                    instruction.className,
                    instruction.fieldName
                )
                if (location != null) listOf(location) else emptyList()
            }
            
            is TypeInstruction -> {
                // Navigate to class definition
                findClassDefinition(instruction.className)
            }
            
            is JumpInstruction -> {
                // JumpInstruction is handled separately in handleInstructionNavigation
                // This fallback shouldn't be reached for jumps
                emptyList()
            }

            is ConstStringInstruction -> {
                // String literals have no definition to navigate to
                emptyList()
            }
        }
    }
    
    /**
     * Find definition for a class.
     * Checks workspace first, then SDK stubs.
     * 
     * BUGFIX: Array types need base type extraction for lookup.
     * [[Lt5/d; → Lt5/d; (for index lookup)
     * [Ljava/lang/String; → Ljava/lang/String; (then blocked as SDK)
     */
    private fun findClassDefinition(className: String): List<Location> {
        // BUGFIX: SDK classes should not be navigable
        // User requirement: "SDK Class should not be clickable or have any definitions"
        if (ClassUtils.isSDKClass(className)) {
            return emptyList()
        }
        
        // Strip array brackets for lookup (index stores base types only)
        // [[Lt5/d; → Lt5/d;
        // [Lcom/example/MyClass; → Lcom/example/MyClass;
        val baseClassName = className.trimStart('[')
        
        val file = workspaceIndex.findClass(baseClassName)
        if (file != null) {
            return listOf(Location(file.uri, file.classDefinition.range))
        }
        return emptyList()
    }
    
    /**
     * Find type definition in method signature.
     * Checks cursor position to determine which type (return or param) user clicked on.
     * 
     * BUG FIX: Previously returned FIRST matching type, now returns type at cursor position.
     */
    private fun findTypeInMethodSignature(
        method: MethodDefinition,
        position: Position,
        uri: String
    ): List<Location> {
        val line = workspaceIndex.getLineContent(uri, position.line)
        if (line == null) {
            // Fallback for tests: no document content available
            val returnClassName = TypeResolver.extractClassName(method.returnType)
            if (returnClassName != null && method.returnType.startsWith('L')) {
                return findClassDefinition(returnClassName)
            }
            for (param in method.parameters) {
                if (param.type.startsWith('L') || param.type.startsWith('[')) {
                    val paramClassName = TypeResolver.extractClassName(param.type)
                    if (paramClassName != null) {
                        return findClassDefinition(paramClassName)
                    }
                }
            }
            return emptyList()
        }

        val cursorChar = position.character

        // Check if cursor is on method name/modifiers (before "(")
        val methodKeywordIndex = line.indexOf(".method")
        val openParenIndex = line.indexOf('(', if (methodKeywordIndex >= 0) methodKeywordIndex else 0)
        if (methodKeywordIndex >= 0 && openParenIndex > methodKeywordIndex && cursorChar < openParenIndex) {
            logger.debug("Cursor on method name or modifiers, not navigating")
            return emptyList()
        }

        // Check return type position
        val returnClassName = TypeResolver.extractClassName(method.returnType)
        if (returnClassName != null && method.returnType.startsWith('L')) {
            val returnTypeIndex = line.lastIndexOf(method.returnType)
            if (returnTypeIndex >= 0) {
                val returnTypeEnd = returnTypeIndex + method.returnType.length
                if (cursorChar >= returnTypeIndex && cursorChar <= returnTypeEnd) {
                    return findClassDefinition(returnClassName)
                }
            }
        }

        // Check parameter types positions
        val closeParenIndex = line.indexOf(')', openParenIndex)
        if (closeParenIndex > 0 && cursorChar >= openParenIndex && cursorChar <= closeParenIndex) {
            var searchStart = openParenIndex + 1
            for (param in method.parameters) {
                if (param.type.startsWith('L') || param.type.startsWith('[')) {
                    val paramClassName = TypeResolver.extractClassName(param.type)
                    if (paramClassName != null) {
                        val paramTypeIndex = line.indexOf(param.type, searchStart)
                        if (paramTypeIndex >= 0 && paramTypeIndex < closeParenIndex) {
                            val paramTypeEnd = paramTypeIndex + param.type.length
                            searchStart = paramTypeEnd
                            if (cursorChar >= paramTypeIndex && cursorChar <= paramTypeEnd) {
                                return findClassDefinition(paramClassName)
                            }
                        }
                    }
                }
            }
        }

        return emptyList()
    }
    
    /**
     * Find type reference in field declaration.
     * Only navigates if cursor is on the type, not on the field name.
     */
    private fun findTypeInFieldDeclaration(
        field: FieldDefinition,
        position: Position,
        uri: String
    ): List<Location> {
        // Field format: .field modifiers name:type = value
        // Navigate only if cursor is on the TYPE part, not on the name
        val line = workspaceIndex.getLineContent(uri, position.line)
        if (line == null) {
            // Fallback for tests: no document content available
            val className = TypeResolver.extractClassName(field.type)
            if (className != null) return findClassDefinition(className)
            return emptyList()
        }

        val cursorChar = position.character
        val colonIndex = line.indexOf(':')
        if (colonIndex >= 0 && cursorChar > colonIndex) {
            val typeIndex = line.indexOf(field.type, colonIndex)
            if (typeIndex >= 0) {
                val typeEnd = typeIndex + field.type.length
                if (cursorChar >= typeIndex && cursorChar <= typeEnd) {
                    val className = TypeResolver.extractClassName(field.type)
                    if (className != null) return findClassDefinition(className)
                }
            }
        } else {
            logger.debug("Cursor on field name or modifiers, not navigating")
            return emptyList()
        }

        return emptyList()
    }
    
    /**
     * Find class reference at position (fallback for edge cases).
     * This is used when cursor is on a class reference within a line
     * but not on a specific AST node (e.g., .super, .implements directives).
     * 
     * Note: Instruction-level AST is implemented. This fallback handles
     * special directives not yet modeled as AST nodes.
     */
    private fun findClassReferenceAtPosition(
        file: SmaliFile,
        position: Position,
        uri: String
    ): List<Location> {
        try {
            val line = workspaceIndex.getLineContent(uri, position.line)
                ?: return emptyList()
            val charPos = position.character
            
            // BUGFIX: Ignore annotation keywords (system, runtime, build)
            // These are not navigable - they're language keywords
            if (line.trim().startsWith(".annotation")) {
                // Extract word at cursor position
                val wordStart = line.substring(0, charPos).lastIndexOf(' ') + 1
                val wordEnd = line.indexOf(' ', charPos).let { if (it == -1) line.length else it }
                if (wordStart >= 0 && wordEnd <= line.length && wordStart < wordEnd) {
                    val word = line.substring(wordStart, wordEnd).trim()
                    // Annotation keywords that should not be navigable
                    if (word in setOf("system", "runtime", "build")) {
                        return emptyList()
                    }
                }
            }
            
            // Check for directives with class references
            val trimmed = line.trim()
            when {
                trimmed.startsWith(".class") -> {
                    // .class public final Lcom/example/MyClass;
                    val className = extractClassName(line)
                    if (className != null) {
                        return findClassDefinition(className)
                    }
                }
                trimmed.startsWith(".super") -> {
                    // .super Landroid/app/Activity;
                    val className = extractClassName(line)
                    if (className != null) {
                        return findClassDefinition(className)
                    }
                }
                trimmed.startsWith(".implements") -> {
                    // .implements Ljava/io/Serializable;
                    val className = extractClassName(line)
                    if (className != null) {
                        return findClassDefinition(className)
                    }
                }
                trimmed.startsWith(".catch") -> {
                    // .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0
                    val catchClassStart = line.indexOf('L', line.indexOf(".catch") + 6)
                    if (catchClassStart >= 0) {
                        val catchClassEnd = line.indexOf(';', catchClassStart)
                        if (catchClassEnd > catchClassStart) {
                            val className = line.substring(catchClassStart, catchClassEnd + 1)
                            if (charPos >= catchClassStart && charPos <= catchClassEnd) {
                                return findClassDefinition(className)
                            }
                        }
                    }
                }
            }
            
            // Fallback: Find any class reference at cursor position
            // Handles .annotation, .parameter, and other directives
            for (match in CLASS_PATTERN.findAll(line)) {
                if (charPos >= match.range.first && charPos <= match.range.last) {
                    return findClassDefinition(match.value)
                }
            }
        } catch (e: Exception) {
            logger.error("Error reading file for class reference: $uri", e)
        }
        
        return emptyList()
    }
    
    /**
     * Extract class name from a line.
     * Temporary helper until instruction-level AST.
     */
    private fun extractClassName(line: String): String? {
        val match = CLASS_PATTERN.find(line)
        return match?.value
    }
}
