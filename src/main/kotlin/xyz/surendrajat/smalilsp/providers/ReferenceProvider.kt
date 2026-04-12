package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.*
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.ClassUtils
import xyz.surendrajat.smalilsp.util.InstructionSymbolExtractor

/**
 * Provides "Find All References" functionality - AST-based implementation.
 * 
 * Features:
 * - Find subclasses (classes that extend this class)
 * - Find implementations (classes that implement this interface)
 * - Find classes that reference this class as field type
 * - Find method call sites (invoke-virtual, invoke-static, invoke-direct, invoke-interface)
 * - Find field access sites (iget, iput, sget, sput)
 * 
 * Implementation:
 * - Uses instruction-level AST directly from WorkspaceIndex (NO regex parsing)
 * - Fast: O(1) lookup for indexed symbols, O(n) scan for usages
 * - Accurate: Uses parsed Range information
 * 
 * Scope:
 * - Class references: Inheritance and interface implementation tracking
 * - Method references: All invoke instruction call sites
 * - Field references: All field access instruction sites
 */
class ReferenceProvider(
    private val workspaceIndex: WorkspaceIndex
) {
    
    /**
     * Find all references to symbol at cursor position.
     * Uses AST directly from WorkspaceIndex (NO regex parsing).
     */
    fun findReferences(
        uri: String,
        position: Position,
        includeDeclaration: Boolean = true
    ): List<Location> {
        // Get parsed file from index
        val file = workspaceIndex.findFileByUri(uri) ?: return emptyList()
        
        // Find AST node at position
        val node = file.findNodeAt(position)
        
        return when {
            node == null -> {
                // Node is null - might be on a directive line that's not part of any node range
                // Check if it's a .super or .implements directive
                val directiveRef = extractClassRefFromDirectiveLine(uri, position)
                if (directiveRef != null) {
                    findClassReferences(directiveRef, includeDeclaration)
                } else {
                    emptyList()
                }
            }
            
            node.first == NodeType.CLASS -> {
                // Check if cursor is on .super or .implements directive
                val classDef = node.second as ClassDefinition
                val directiveClassRef = extractClassRefFromClassDirective(uri, position, classDef)
                if (directiveClassRef != null) {
                    // User clicked on a class reference in .super or .implements - find references to that class
                    findClassReferences(directiveClassRef, includeDeclaration)
                } else {
                    // User clicked on class name - find references to this class
                    findClassReferences(classDef.name, includeDeclaration)
                }
            }
            
            node.first == NodeType.METHOD -> {
                // Check if cursor is on a class reference in the method signature
                val methodDef = node.second as MethodDefinition
                val classRef = extractClassRefFromMethodSignature(uri, position, methodDef)
                if (classRef != null) {
                    // User clicked on a class reference in the signature - find references to that class
                    findClassReferences(classRef, includeDeclaration)
                } else {
                    // User clicked on method name - find method invocation references
                    findMethodReferences(file.classDefinition.name, methodDef, includeDeclaration)
                }
            }
            
            node.first == NodeType.FIELD -> {
                // Check if cursor is on the field type (class reference)
                val fieldDef = node.second as FieldDefinition
                val classRef = extractClassRefFromFieldType(uri, position, fieldDef)
                if (classRef != null) {
                    // User clicked on the type - find references to that class
                    findClassReferences(classRef, includeDeclaration)
                } else {
                    // User clicked on field name - find field access references
                    findFieldReferences(file.classDefinition.name, fieldDef, includeDeclaration)
                }
            }
            
            node.first == NodeType.INSTRUCTION -> {
                // User clicked on a symbol within an instruction (invoke, iget, new-instance, etc.)
                // Determine which symbol and find references to it
                handleInstructionReferences(node.second as Instruction, uri, position, includeDeclaration)
            }
            
            node.first == NodeType.LABEL -> {
                // User clicked on a label definition (e.g., :cond_0)
                val labelDef = node.second as LabelDefinition
                findLabelReferences(labelDef.name, uri, position, includeDeclaration)
            }
            
            else -> emptyList()
        }
    }
    
    /**
     * Find all references to a class.
     * Includes:
     * - Declaration location
     * - Subclasses (.super directive)
     * - Implementations (.implements directive)
     * - Field type references, method signature types, instruction targets
     *
     * Uses reverse indexes — O(1) lookup instead of scanning all files.
     */
    private fun findClassReferences(
        className: String,
        includeDeclaration: Boolean
    ): List<Location> {
        val locations = mutableListOf<Location>()

        // Add declaration if requested
        if (includeDeclaration) {
            val file = workspaceIndex.findClass(className)
            if (file != null) {
                locations.add(Location(file.uri, file.classDefinition.range))
            }
        }

        // Find inheritance usages (classes that extend/implement this class)
        val usageUris = workspaceIndex.findClassUsages(className)
        usageUris.forEach { usageUri ->
            val classNameFromUri = workspaceIndex.findClassNameByUri(usageUri)
            if (classNameFromUri != null) {
                val usageFile = workspaceIndex.findClass(classNameFromUri)
                if (usageFile != null) {
                    // Return .super directive range if this class is the superclass
                    if (usageFile.classDefinition.superClass == className) {
                        val range = usageFile.classDefinition.superClassRange
                            ?: usageFile.classDefinition.range
                        locations.add(Location(usageFile.uri, range))
                    }

                    // Return .implements directive range if this class is an interface
                    if (className in usageFile.classDefinition.interfaces) {
                        val range = usageFile.classDefinition.interfaceRanges[className]
                            ?: usageFile.classDefinition.range
                        locations.add(Location(usageFile.uri, range))
                    }
                }
            }
        }

        // All other references: instructions + field types + method signatures (O(1) lookup)
        locations.addAll(workspaceIndex.findClassRefLocations(className))

        return locations.distinctBy { "${it.uri}:${it.range.start.line}:${it.range.start.character}" }
    }
    
    /**
     * Find all references to a method.
     * Searches all methods in the workspace for invoke instructions that call this method.
     * Includes calls through subclasses (polymorphism).
     */
    private fun findMethodReferences(
        targetClassName: String,
        targetMethod: MethodDefinition,
        includeDeclaration: Boolean
    ): List<Location> {
        // Delegate to overloaded version
        return findMethodReferences(
            targetClassName,
            targetMethod.name,
            targetMethod.descriptor,
            includeDeclaration,
            targetMethod.range
        )
    }
    
    /**
     * Find all references to a method by name and descriptor.
     * This version works for both workspace classes and SDK classes.
     *
     * Uses reverse indexes — O(1) lookup per class instead of scanning all files.
     */
    private fun findMethodReferences(
        targetClassName: String,
        methodName: String,
        descriptor: String,
        includeDeclaration: Boolean,
        declarationRange: Range? = null
    ): List<Location> {
        val locations = mutableListOf<Location>()

        // Add declaration if requested and available (in workspace)
        if (includeDeclaration && declarationRange != null) {
            val file = workspaceIndex.findClass(targetClassName)
            if (file != null) {
                locations.add(Location(file.uri, declarationRange))
            }
        }

        // Direct call sites
        locations.addAll(workspaceIndex.findMethodUsages(targetClassName, methodName, descriptor))

        // Polymorphic call sites (calls through subclasses/implementors)
        // Skip for SDK classes to avoid too many results (e.g., Object.<init>)
        if (!ClassUtils.isSDKClass(targetClassName)) {
            for (subClass in workspaceIndex.getAllSubclasses(targetClassName)) {
                locations.addAll(workspaceIndex.findMethodUsages(subClass, methodName, descriptor))
            }
        }

        return locations
    }
    
    
    /**
     * Find all references to a field.
     * Searches all methods in the workspace for field access instructions (iget, iput, sget, sput).
     */
    private fun findFieldReferences(
        targetClassName: String,
        targetField: FieldDefinition,
        includeDeclaration: Boolean
    ): List<Location> {
        // Delegate to overloaded version
        return findFieldReferences(
            targetClassName,
            targetField.name,
            includeDeclaration,
            targetField.range
        )
    }
    
    /**
     * Find all references to a field by name and type.
     * This version works for both workspace classes and SDK classes.
     *
     * Uses reverse indexes — O(1) lookup instead of scanning all files.
     */
    private fun findFieldReferences(
        targetClassName: String,
        fieldName: String,
        includeDeclaration: Boolean,
        declarationRange: Range? = null
    ): List<Location> {
        val locations = mutableListOf<Location>()

        // Add declaration if requested and available (in workspace)
        if (includeDeclaration && declarationRange != null) {
            val file = workspaceIndex.findClass(targetClassName)
            if (file != null) {
                locations.add(Location(file.uri, declarationRange))
            }
        }

        // All field access sites (O(1) lookup)
        locations.addAll(workspaceIndex.findFieldUsages(targetClassName, fieldName))

        return locations
    }
    
    /**
     * Handle find references from within an instruction.
     * User clicked on a symbol (class, method, field) within an instruction line.
     * Uses InstructionSymbolExtractor to determine which symbol cursor is on.
     */
    private fun handleInstructionReferences(
        instruction: Instruction,
        uri: String,
        position: Position,
        includeDeclaration: Boolean
    ): List<Location> {
        // Handle JumpInstruction (goto, if-*) - find all jumps to this label
        if (instruction is JumpInstruction) {
            return findLabelReferences(instruction.targetLabel, uri, position, includeDeclaration)
        }
        
        val lineContent = workspaceIndex.getLineContent(uri, position.line)
            ?: return emptyList()

        // Extract symbol at cursor position
        val symbol = InstructionSymbolExtractor.extractSymbol(
            instruction,
            lineContent,
            position.character
        ) ?: return emptyList()
        
        // Find references based on symbol type
        return when (symbol) {
            is InstructionSymbolExtractor.Symbol.ClassRef -> {
                // Special case: If we're on an invoke instruction and cursor is on a class ref
                // in the descriptor (parameter or return type), prioritize finding references
                // to the METHOD, not the class. This provides better UX as users expect
                // "Find References" on a method call to find other calls to that method,
                // not other uses of the parameter type.
                if (instruction is InvokeInstruction) {
                    // Check if this ClassRef is in the method descriptor (not the class name before ->)
                    val arrowIndex = lineContent.indexOf("->")
                    val classRefIndex = lineContent.indexOf(symbol.className)
                    if (arrowIndex >= 0 && classRefIndex > arrowIndex) {
                        // ClassRef is in the descriptor, not the class name
                        // Find references to the METHOD instead
                        val targetFile = workspaceIndex.findClass(instruction.className)
                        if (targetFile != null) {
                            val method = targetFile.methods.find {
                                it.name == instruction.methodName && it.descriptor == instruction.descriptor
                            }
                            if (method != null) {
                                return findMethodReferences(instruction.className, method, includeDeclaration)
                            } else {
                                return findMethodReferences(instruction.className, instruction.methodName, instruction.descriptor, false)
                            }
                        } else {
                            // SDK class
                            return findMethodReferences(instruction.className, instruction.methodName, instruction.descriptor, false)
                        }
                    }
                }
                // Normal class reference (not in method descriptor)
                findClassReferences(symbol.className, includeDeclaration)
            }
            is InstructionSymbolExtractor.Symbol.MethodRef -> {
                // Try to find the method definition (for workspace classes)
                val targetFile = workspaceIndex.findClass(symbol.className)
                if (targetFile != null) {
                    // Workspace class - find definition
                    val method = targetFile.methods.find {
                        it.name == symbol.methodName && it.descriptor == symbol.descriptor
                    }
                    if (method != null) {
                        findMethodReferences(symbol.className, method, includeDeclaration)
                    } else {
                        // Method not found in workspace class - still search for references
                        findMethodReferences(symbol.className, symbol.methodName, symbol.descriptor, false)
                    }
                } else {
                    // SDK class - search for references without definition
                    findMethodReferences(symbol.className, symbol.methodName, symbol.descriptor, false)
                }
            }
            is InstructionSymbolExtractor.Symbol.FieldRef -> {
                // Try to find the field definition (for workspace classes)
                val targetFile = workspaceIndex.findClass(symbol.className)
                if (targetFile != null) {
                    // Workspace class - find definition
                    val field = targetFile.fields.find { it.name == symbol.fieldName }
                    if (field != null) {
                        findFieldReferences(symbol.className, field, includeDeclaration)
                    } else {
                        // Field not found in workspace class - still search for references
                        findFieldReferences(symbol.className, symbol.fieldName, false)
                    }
                } else {
                    // SDK class - search for references without definition
                    findFieldReferences(symbol.className, symbol.fieldName, false)
                }
            }
        }
    }
    
    /**
     * Extract class reference from .super or .implements directive line (when node is null).
     * This is a fallback for when findNodeAt() doesn't match the directive line.
     */
    private fun extractClassRefFromDirectiveLine(uri: String, position: Position): String? {
        val lineContent = workspaceIndex.getLineContent(uri, position.line)
            ?: return null
        
        val trimmed = lineContent.trim()
        
        // Check .super directive
        if (trimmed.startsWith(".super ")) {
            val className = trimmed.substring(7).trim().split('#')[0].trim()
            if (className.startsWith("L") && className.contains(";")) {
                val classEnd = className.indexOf(';') + 1
                return className.substring(0, classEnd)
            }
        }
        
        // Check .implements directive
        if (trimmed.startsWith(".implements ")) {
            val className = trimmed.substring(12).trim().split('#')[0].trim()
            if (className.startsWith("L") && className.contains(";")) {
                val classEnd = className.indexOf(';') + 1
                return className.substring(0, classEnd)
            }
        }
        
        return null
    }
    
    /**
     * Extract class reference from .super or .implements directive at cursor position.
     * .super LBaseClass;
     * .implements LInterface;
     * Returns the class name if cursor is on the type, null otherwise.
     */
    private fun extractClassRefFromClassDirective(uri: String, position: Position, classDef: ClassDefinition): String? {
        val lineContent = workspaceIndex.getLineContent(uri, position.line)
            ?: return null

        // Check if line is .super or .implements
        val trimmed = lineContent.trim()

        if (trimmed.startsWith(".super ")) {
            // .super LBaseClass;
            val className = trimmed.substring(7).trim().split('#')[0].trim()
            if (className.startsWith("L") && className.contains(";")) {
                val classEnd = className.indexOf(';') + 1
                val fullClassName = className.substring(0, classEnd)
                // Check if cursor is on the class name
                val classIndex = lineContent.indexOf(fullClassName)
                if (classIndex >= 0) {
                    val classEndPos = classIndex + fullClassName.length
                    if (position.character >= classIndex && position.character <= classEndPos) {
                        return fullClassName
                    }
                }
            }
        } else if (trimmed.startsWith(".implements ")) {
            // .implements LInterface;
            val className = trimmed.substring(12).trim().split('#')[0].trim()
            if (className.startsWith("L") && className.contains(";")) {
                val classEnd = className.indexOf(';') + 1
                val fullClassName = className.substring(0, classEnd)
                // Check if cursor is on the class name
                val classIndex = lineContent.indexOf(fullClassName)
                if (classIndex >= 0) {
                    val classEndPos = classIndex + fullClassName.length
                    if (position.character >= classIndex && position.character <= classEndPos) {
                        return fullClassName
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Extract class reference from field directive at cursor position.
     * .field modifiers fieldName:LClassName;
     * Returns LClassName; if cursor is on the type, null otherwise.
     */
    private fun extractClassRefFromFieldType(uri: String, position: Position, field: FieldDefinition): String? {
        val lineContent = workspaceIndex.getLineContent(uri, position.line)
            ?: return null
        
        // Field format: .field modifiers fieldName:LType;
        // Find the colon position
        val colonIndex = lineContent.indexOf(':')
        if (colonIndex < 0) return null
        
        // Extract type after colon
        val typeStart = colonIndex + 1
        if (position.character < typeStart) return null  // Cursor before type
        
        // Field type is everything after colon (may include comments)
        val afterColon = lineContent.substring(typeStart).trim()
        // Remove comments
        val type = afterColon.split('#', ';')[0].trim() + 
                   if (afterColon.contains(';')) ";" else ""
        
        // Check if type is a class reference (starts with L or [)
        if (!type.startsWith("L") && !type.startsWith("[")) return null
        
        // Check if cursor is within the type
        val typeIndex = lineContent.indexOf(type, colonIndex)
        if (typeIndex < 0) return null
        val typeEnd = typeIndex + type.length
        if (position.character >= typeIndex && position.character <= typeEnd) {
            // Extract the actual class name from array types
            // [LClassName; -> LClassName;, [[LClassName; -> LClassName;
            return if (type.startsWith("[") && type.contains("L")) {
                type.trimStart('[')
            } else {
                type
            }
        }
        
        return null
    }
    
    /**
     * Extract class reference from method signature at cursor position.
     * .method modifiers methodName(Lparam1;Lparam2;)Lreturn;
     * Returns class name if cursor is on a class reference, null otherwise.
     */
    private fun extractClassRefFromMethodSignature(uri: String, position: Position, method: MethodDefinition): String? {
        val lineContent = workspaceIndex.getLineContent(uri, position.line)
            ?: return null
        
        // Method format: .method modifiers methodName(params)return
        // Find the descriptor in the line
        val descriptorIndex = lineContent.indexOf(method.descriptor)
        if (descriptorIndex < 0) return null
        
        // Check if cursor is within the descriptor
        val descriptorEnd = descriptorIndex + method.descriptor.length
        if (position.character < descriptorIndex || position.character > descriptorEnd) {
            return null  // Cursor not in descriptor
        }
        
        // Extract all class references from the descriptor
        val classRefs = extractClassRefsFromDescriptor(method.descriptor)
        
        // Find which class reference the cursor is on
        var searchStart = descriptorIndex
        for (classRef in classRefs) {
            val classIndex = lineContent.indexOf(classRef, searchStart)
            if (classIndex >= 0) {
                val classEnd = classIndex + classRef.length
                if (position.character >= classIndex && position.character <= classEnd) {
                    return classRef
                }
                searchStart = classEnd
            }
        }
        
        return null
    }
    
    /**
     * Extract all class references from a method descriptor.
     * (ILjava/lang/String;[LBundle;)Ljava/lang/Object;
     * Returns: [Ljava/lang/String;, LBundle;, Ljava/lang/Object;]
     * Excludes primitives and primitive arrays.
     */
    private fun extractClassRefsFromDescriptor(descriptor: String): List<String> {
        val refs = mutableListOf<String>()
        var i = 0
        
        while (i < descriptor.length) {
            when (descriptor[i]) {
                'L' -> {
                    // Class type: Lpath/to/Class;
                    val end = descriptor.indexOf(';', startIndex = i)
                    if (end >= 0) {
                        refs.add(descriptor.substring(i, end + 1))
                        i = end + 1
                    } else {
                        i++
                    }
                }
                '[' -> {
                    // Array type
                    while (i < descriptor.length && descriptor[i] == '[') {
                        i++
                    }
                    if (i < descriptor.length && descriptor[i] == 'L') {
                        // Object array - extract element type
                        val end = descriptor.indexOf(';', startIndex = i)
                        if (end >= 0) {
                            refs.add(descriptor.substring(i, end + 1))  // Element class only
                            i = end + 1
                        } else {
                            i++
                        }
                    } else {
                        // Primitive array - skip
                        i++
                    }
                }
                else -> {
                    // Primitive or other character - skip
                    i++
                }
            }
        }
        
        return refs
    }
    
    /**
     * Find all references to a label within the same method.
     * Labels are local to the method they're defined in.
     * 
     * @param labelName Label name without colon (e.g., "cond_0")
     * @param uri URI of the file containing the label
     * @param position Position of the label (used to find containing method)
     * @param includeDeclaration Whether to include the label definition itself
     * @return List of locations where the label is referenced
     */
    private fun findLabelReferences(
        labelName: String,
        uri: String,
        position: Position,
        includeDeclaration: Boolean
    ): List<Location> {
        val file = workspaceIndex.findFileByUri(uri) ?: return emptyList()
        val locations = mutableListOf<Location>()
        
        // Find the method containing the position (where the label is referenced/defined)
        val containingMethod = file.methods.find { method ->
            position.line >= method.range.start.line && position.line <= method.range.end.line
        } ?: return emptyList()
        
        // Only search within the containing method (labels are method-scoped)
        val labelDef = containingMethod.labels[labelName]
            
        // Include label definition if requested and exists in this method
        if (includeDeclaration && labelDef != null) {
            locations.add(Location(uri, labelDef.range))
        }
        
        // Find all JumpInstructions that reference this label within the same method
        for (instruction in containingMethod.instructions) {
            if (instruction is JumpInstruction && instruction.targetLabel == labelName) {
                locations.add(Location(uri, instruction.labelRange))
            }
        }
        
        return locations
    }
}
