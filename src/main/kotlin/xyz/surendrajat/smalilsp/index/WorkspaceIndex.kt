package xyz.surendrajat.smalilsp.index

import xyz.surendrajat.smalilsp.core.*
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.net.URI

/**
 * Thread-safe workspace index.
 * 
 * Design principles:
 * - All operations are atomic
 * - No removal of classes when files close (learned from v1 bug)
 * - Fast lookups with ConcurrentHashMap
 * - Tracks reverse dependencies for "find references"
 */
class WorkspaceIndex {
    
    // Class name → SmaliFile
    private val files = ConcurrentHashMap<String, SmaliFile>()
    
    // Class name → URI
    private val classToUri = ConcurrentHashMap<String, String>()
    
    // URI → Class name (reverse lookup for fast findFileByUri)
    private val uriToClass = ConcurrentHashMap<String, String>()
    
    // Method signature → List<Location> (can have multiple definitions - overloads, overrides)
    private val methodLocations = ConcurrentHashMap<String, MutableSet<Location>>()
    
    // Field signature → Location
    private val fieldLocations = ConcurrentHashMap<String, Location>()
    
    // Reverse index: class name → Set<URIs that reference it>
    private val classUsages = ConcurrentHashMap<String, MutableSet<String>>()

    // String literal index: string value → Set<Location>
    // Built lazily on first search — not during parallel indexFile() to avoid CHM contention.
    private var stringIndex = HashMap<String, MutableSet<Location>>()
    @Volatile private var stringIndexDirty = true
    private val stringIndexLock = Any()

    // Subclass hierarchy: parent class → direct subclasses
    // Built at index time, used by ReferenceProvider and TypeHierarchyProvider
    private val subclassIndex = ConcurrentHashMap<String, MutableSet<String>>()

    // Interface implementors: interface → direct implementors
    private val implementorsIndex = ConcurrentHashMap<String, MutableSet<String>>()

    // Reverse usage indexes: symbol → all call/access sites across workspace
    // Built during indexFile() — enables O(1) "Find References" instead of O(n) full scans.
    // Keys use same format as methodSignature()/fieldSignature().
    private val methodUsages = ConcurrentHashMap<String, MutableSet<Location>>()
    private val fieldUsages = ConcurrentHashMap<String, MutableSet<Location>>()

    // Class reference locations: className → all locations that reference it
    // Includes: instruction targets, field type declarations, method signature types
    private val classRefLocations = ConcurrentHashMap<String, MutableSet<Location>>()

    // Document content for open files (uri → content string)
    // Only populated for files open in the editor (didOpen/didChange)
    // Removed on didClose to bound memory to open editor tabs
    private val documentContents = ConcurrentHashMap<String, String>()
    
    /**
     * Index a smali file. Thread-safe.
     *
     * On re-index (didChange), removes stale entries from ALL index maps
     * before adding new ones — prevents ghost references to renamed/deleted symbols.
     */
    fun indexFile(file: SmaliFile) {
        val className = file.classDefinition.name

        // Clean up old entries if re-indexing (didChange)
        files[className]?.let { oldFile -> removeOldEntries(oldFile) }

        // Store file
        files[className] = file
        classToUri[className] = file.uri
        uriToClass[file.uri] = className

        // Index method declarations
        file.methods.forEach { method ->
            val signature = methodSignature(className, method.name, method.descriptor)
            methodLocations.computeIfAbsent(signature) { ConcurrentHashMap.newKeySet() }
                .add(Location(file.uri, method.range))
        }

        // Index field declarations
        file.fields.forEach { field ->
            val signature = fieldSignature(className, field.name)
            fieldLocations[signature] = Location(file.uri, field.range)
        }

        // Track superclass usage + subclass hierarchy
        file.classDefinition.superClass?.let { superClass ->
            classUsages.computeIfAbsent(superClass) { ConcurrentHashMap.newKeySet() }
                .add(file.uri)
            subclassIndex.computeIfAbsent(superClass) { ConcurrentHashMap.newKeySet() }
                .add(className)
        }

        // Track interface usages + implementors
        file.classDefinition.interfaces.forEach { iface ->
            classUsages.computeIfAbsent(iface) { ConcurrentHashMap.newKeySet() }
                .add(file.uri)
            implementorsIndex.computeIfAbsent(iface) { ConcurrentHashMap.newKeySet() }
                .add(className)
        }

        // Build reverse usage indexes from instructions
        file.methods.forEach { method ->
            method.instructions.forEach { instr ->
                when (instr) {
                    is InvokeInstruction -> {
                        methodUsages.computeIfAbsent(
                            methodSignature(instr.className, instr.methodName, instr.descriptor)
                        ) { ConcurrentHashMap.newKeySet() }.add(Location(file.uri, instr.range))
                        classRefLocations.computeIfAbsent(instr.className) { ConcurrentHashMap.newKeySet() }
                            .add(Location(file.uri, instr.range))
                    }
                    is FieldAccessInstruction -> {
                        fieldUsages.computeIfAbsent(
                            fieldSignature(instr.className, instr.fieldName)
                        ) { ConcurrentHashMap.newKeySet() }.add(Location(file.uri, instr.range))
                        classRefLocations.computeIfAbsent(instr.className) { ConcurrentHashMap.newKeySet() }
                            .add(Location(file.uri, instr.range))
                        extractClassFromType(instr.fieldType)?.let { refClass ->
                            classRefLocations.computeIfAbsent(refClass) { ConcurrentHashMap.newKeySet() }
                                .add(Location(file.uri, instr.range))
                        }
                    }
                    is TypeInstruction -> {
                        classRefLocations.computeIfAbsent(instr.className) { ConcurrentHashMap.newKeySet() }
                            .add(Location(file.uri, instr.range))
                    }
                    is JumpInstruction, is ConstStringInstruction -> {
                        // Labels are method-local; strings handled by lazy stringIndex
                    }
                }
            }
        }

        // Index class references from field types
        file.fields.forEach { field ->
            extractClassFromType(field.type)?.let { refClass ->
                classRefLocations.computeIfAbsent(refClass) { ConcurrentHashMap.newKeySet() }
                    .add(Location(file.uri, field.range))
            }
        }

        // Index class references from method signatures (parameters + return type)
        file.methods.forEach { method ->
            extractClassFromType(method.returnType)?.let { refClass ->
                classRefLocations.computeIfAbsent(refClass) { ConcurrentHashMap.newKeySet() }
                    .add(Location(file.uri, method.range))
            }
            method.parameters.forEach { param ->
                extractClassFromType(param.type)?.let { refClass ->
                    classRefLocations.computeIfAbsent(refClass) { ConcurrentHashMap.newKeySet() }
                        .add(Location(file.uri, method.range))
                }
            }
        }

        // Mark string index as stale (rebuilt lazily on next search)
        stringIndexDirty = true
    }

    /**
     * Remove all index entries from a previously indexed file.
     * Called before re-indexing to prevent stale references.
     */
    private fun removeOldEntries(oldFile: SmaliFile) {
        val className = oldFile.classDefinition.name
        val uri = oldFile.uri
        // Remove method declarations
        oldFile.methods.forEach { method ->
            val sig = methodSignature(className, method.name, method.descriptor)
            methodLocations[sig]?.removeIf { it.uri == uri }
        }

        // Remove field declarations
        oldFile.fields.forEach { field ->
            fieldLocations.remove(fieldSignature(className, field.name))
        }

        // Remove hierarchy entries
        oldFile.classDefinition.superClass?.let { sc ->
            classUsages[sc]?.remove(uri)
            subclassIndex[sc]?.remove(className)
        }
        oldFile.classDefinition.interfaces.forEach { iface ->
            classUsages[iface]?.remove(uri)
            implementorsIndex[iface]?.remove(className)
        }

        // Remove reverse usage entries (instructions + field types + method signatures)
        oldFile.methods.forEach { method ->
            method.instructions.forEach { instr ->
                when (instr) {
                    is InvokeInstruction -> {
                        val sig = methodSignature(instr.className, instr.methodName, instr.descriptor)
                        methodUsages[sig]?.removeIf { it.uri == uri }
                        classRefLocations[instr.className]?.removeIf { it.uri == uri }
                    }
                    is FieldAccessInstruction -> {
                        val sig = fieldSignature(instr.className, instr.fieldName)
                        fieldUsages[sig]?.removeIf { it.uri == uri }
                        classRefLocations[instr.className]?.removeIf { it.uri == uri }
                        extractClassFromType(instr.fieldType)?.let { refClass ->
                            classRefLocations[refClass]?.removeIf { it.uri == uri }
                        }
                    }
                    is TypeInstruction -> {
                        classRefLocations[instr.className]?.removeIf { it.uri == uri }
                    }
                    else -> {}
                }
            }
        }
        oldFile.fields.forEach { field ->
            extractClassFromType(field.type)?.let { refClass ->
                classRefLocations[refClass]?.removeIf { it.uri == uri }
            }
        }
        oldFile.methods.forEach { method ->
            extractClassFromType(method.returnType)?.let { refClass ->
                classRefLocations[refClass]?.removeIf { it.uri == uri }
            }
            method.parameters.forEach { param ->
                extractClassFromType(param.type)?.let { refClass ->
                    classRefLocations[refClass]?.removeIf { it.uri == uri }
                }
            }
        }
    }

    /**
     * Extract class name from a type descriptor, stripping array brackets.
     * Returns null for primitive types.
     * Example: "[Lcom/Foo;" → "Lcom/Foo;", "I" → null
     */
    private fun extractClassFromType(type: String): String? {
        val base = type.trimStart('[')
        return if (base.startsWith('L') && base.endsWith(';')) base else null
    }
    
    /**
     * Find a class definition in workspace.
     * Returns null for SDK/system classes (not in user's APK).
     */
    fun findClass(className: String): SmaliFile? {
        return files[className]
    }

    /**
     * Find classes by simple name (e.g., "MainActivity" matches "Lcom/example/MainActivity;").
     * Returns all matching classes since simple names are not unique.
     */
    fun findClassesBySimpleName(simpleName: String): List<SmaliFile> {
        val suffix = "/$simpleName;"
        return files.entries
            .filter { it.key.endsWith(suffix) }
            .map { it.value }
    }
    
    /**
     * Get URI for a class in workspace.
     * Returns null for SDK/system classes.
     */
    fun getUri(className: String): String? {
        return classToUri[className]
    }
    
    /**
     * Check if a class is available in workspace.
     * Returns false for SDK/system classes.
     */
    fun hasClass(className: String): Boolean {
        return files.containsKey(className)
    }
    
    /**
     * Find method definition(s).
     * Can return multiple locations (overloads, overrides).
     */
    fun findMethod(className: String, methodName: String, descriptor: String): Set<Location> {
        // Try direct lookup first (fast path)
        val signature = methodSignature(className, methodName, descriptor)
        val directMatch = methodLocations[signature]
        if (directMatch != null && directMatch.isNotEmpty()) {
            return directMatch
        }
        
        // If not found, search up class hierarchy (parent classes and interfaces)
        // This handles inherited methods
        val classFile = files[className] ?: return emptySet()
        
        // Check superclass
        val superClass = classFile.classDefinition.superClass
        if (superClass != null && superClass != "Ljava/lang/Object;") {
            val parentResult = findMethod(superClass, methodName, descriptor)
            if (parentResult.isNotEmpty()) {
                return parentResult
            }
        }
        
        // Check interfaces
        for (interfaceName in classFile.classDefinition.interfaces) {
            val interfaceResult = findMethod(interfaceName, methodName, descriptor)
            if (interfaceResult.isNotEmpty()) {
                return interfaceResult
            }
        }
        
        return emptySet()
    }
    
    /**
     * Find field definition.
     */
    fun findField(className: String, fieldName: String): Location? {
        // Try direct lookup first (fast path)
        val signature = fieldSignature(className, fieldName)
        val directMatch = fieldLocations[signature]
        if (directMatch != null) {
            return directMatch
        }
        
        // If not found, search up class hierarchy (parent classes)
        // This handles inherited fields
        val classFile = files[className] ?: return null
        
        // Check superclass
        val superClass = classFile.classDefinition.superClass
        if (superClass != null && superClass != "Ljava/lang/Object;") {
            val parentResult = findField(superClass, fieldName)
            if (parentResult != null) {
                return parentResult
            }
        }
        
        // Note: Interfaces don't have instance fields in Smali/JVM
        // so we don't check interfaces for fields
        
        return null
    }
    
    /**
     * Build the string literal index from all indexed files.
     * Called lazily on first search — not during the parallel indexFile() scan,
     * where ConcurrentHashMap contention on stringIndex was the primary bottleneck.
     * Double-checked locking: volatile read is fast, synchronized block only on first build.
     */
    private fun ensureStringIndex() {
        if (!stringIndexDirty) return
        synchronized(stringIndexLock) {
            if (!stringIndexDirty) return@synchronized
            val newIndex = HashMap<String, MutableSet<Location>>()
            for (file in files.values) {
                for (method in file.methods) {
                    for (instr in method.instructions) {
                        if (instr is ConstStringInstruction) {
                            newIndex.getOrPut(instr.value) { mutableSetOf() }
                                .add(Location(file.uri, instr.range))
                        }
                    }
                }
            }
            stringIndex = newIndex
            stringIndexDirty = false
        }
    }

    /**
     * Search string literals by substring match (case-insensitive).
     * Returns matching string values with their locations.
     */
    fun searchStrings(query: String, maxResults: Int = 500): List<StringSearchResult> {
        ensureStringIndex()
        val normalizedQuery = query.lowercase()
        return stringIndex.entries
            .filter { (value, _) -> value.lowercase().contains(normalizedQuery) }
            .flatMap { (value, locations) ->
                locations.map { location -> StringSearchResult(value, location) }
            }
            .take(maxResults)
    }

    /**
     * Get all locations of an exact string literal.
     */
    fun findStringLocations(value: String): Set<Location> {
        ensureStringIndex()
        return stringIndex[value] ?: emptySet()
    }

    /**
     * Find all files that reference a class (inheritance/interface only).
     */
    fun findClassUsages(className: String): Set<String> {
        return classUsages[className] ?: emptySet()
    }

    // ========== Reverse Usage Index Queries ==========

    /**
     * Find all call sites for a method. O(1) lookup.
     * Returns locations of all invoke instructions targeting this method signature.
     */
    fun findMethodUsages(className: String, methodName: String, descriptor: String): Set<Location> {
        return methodUsages[methodSignature(className, methodName, descriptor)] ?: emptySet()
    }

    /**
     * Find all access sites for a field. O(1) lookup.
     * Returns locations of all iget/iput/sget/sput instructions targeting this field.
     */
    fun findFieldUsages(className: String, fieldName: String): Set<Location> {
        return fieldUsages[fieldSignature(className, fieldName)] ?: emptySet()
    }

    /**
     * Find all locations that reference a class. O(1) lookup.
     * Includes: invoke targets, field access targets, type instructions,
     * field type declarations, method parameter/return types.
     */
    fun findClassRefLocations(className: String): Set<Location> {
        return classRefLocations[className] ?: emptySet()
    }

    /**
     * Get direct subclasses of a class. O(1) lookup.
     */
    fun getDirectSubclasses(className: String): Set<String> {
        return subclassIndex[className] ?: emptySet()
    }

    /**
     * Get direct implementors of an interface. O(1) lookup.
     */
    fun getDirectImplementors(className: String): Set<String> {
        return implementorsIndex[className] ?: emptySet()
    }

    /**
     * Get all transitive subclasses (direct + indirect). BFS traversal.
     * Used by ReferenceProvider for polymorphic method reference matching.
     */
    fun getAllSubclasses(className: String): Set<String> {
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(className)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val directSubs = subclassIndex[current] ?: emptySet()
            val directImpls = implementorsIndex[current] ?: emptySet()
            for (sub in directSubs) {
                if (result.add(sub)) queue.add(sub)
            }
            for (impl in directImpls) {
                if (result.add(impl)) queue.add(impl)
            }
        }
        return result
    }
    
    /**
     * Get index statistics.
     */
    fun getStats(): IndexStats {
        ensureStringIndex()
        return IndexStats(
            classes = files.size,
            methods = methodLocations.values.sumOf { it.size },
            fields = fieldLocations.size,
            strings = stringIndex.size
        )
    }
    
    /**
     * Find SmaliFile by URI.
     * Critical for AST-based providers to get parsed file directly.
     * Optimized with O(1) reverse lookup map.
     */
    fun findFileByUri(uri: String): SmaliFile? {
        // Try all URI format variants for robust matching
        val variants = getNormalizedUriVariants(uri)
        
        for (uriVariant in variants) {
            val className = uriToClass[uriVariant]
            if (className != null) {
                return files[className]
            }
        }
        
        // Last resort: Linear search (should rarely happen)
        return files.values.find { file -> 
            variants.any { variant -> file.uri == variant }
        }
    }
    
    /**
     * Normalize URI to handle variations in format.
     * Returns all possible variations of the URI to try during lookup.
     */
    private fun getNormalizedUriVariants(uri: String): List<String> {
        val variants = mutableListOf(uri)
        
        // file:/// (triple slash) -> file:/ (single slash from Java's toURI())
        if (uri.startsWith("file:///")) {
            variants.add(uri.replaceFirst("file:///", "file:/"))
        }
        // file:/ (single slash) -> file:/// (triple slash for LSP standard)
        else if (uri.startsWith("file:/") && !uri.startsWith("file://")) {
            variants.add(uri.replaceFirst("file:/", "file:///"))
        }
        // file:// (double slash, Windows style) -> file:/// (triple slash)
        else if (uri.startsWith("file://") && !uri.startsWith("file:///")) {
            variants.add(uri.replaceFirst("file://", "file:///"))
        }
        
        return variants
    }
    
    /**
     * Get class name key set view. No copy — safe for concurrent iteration.
     * Preferred over getAllClassNames() for iteration (avoids 118K-element list copy).
     */
    fun getClassNames(): Set<String> {
        return files.keys
    }

    /**
     * Get all indexed class names (for comprehensive testing).
     * Returns a list copy to avoid concurrent modification issues.
     */
    fun getAllClassNames(): List<String> {
        return files.keys.toList()
    }

    /**
     * Get all indexed files (for workspace symbol search, MCP tools).
     * Returns a list copy to avoid concurrent modification issues.
     */
    fun getAllFiles(): List<SmaliFile> {
        return files.values.toList()
    }
    
    /**
     * Find class name by URI.
     * Useful for reverse lookup from usage URIs.
     * Optimized: O(1) lookup using uriToClass reverse map.
     */
    fun findClassNameByUri(uri: String): String? {
        // Try all URI format variants for robust matching
        val variants = getNormalizedUriVariants(uri)
        
        for (uriVariant in variants) {
            val className = uriToClass[uriVariant]
            if (className != null) {
                return className
            }
        }
        
        return null
    }
    
    /**
     * Store document content for an open file.
     * Called by SmaliTextDocumentService on didOpen/didChange.
     */
    fun setDocumentContent(uri: String, content: String) {
        documentContents[uri] = content
    }

    /**
     * Remove document content when file is closed.
     * Called by SmaliTextDocumentService on didClose.
     */
    fun removeDocumentContent(uri: String) {
        documentContents.remove(uri)
    }

    /**
     * Get a specific line from a document.
     * Checks in-memory content first (open editor buffers), falls back to disk.
     *
     * @param uri Document URI
     * @param lineIndex 0-based line index
     * @return Line content or null if unavailable
     */
    fun getLineContent(uri: String, lineIndex: Int): String? {
        // Fast path: check in-memory content (open files)
        val content = documentContents[uri]
        if (content != null) {
            val lines = content.lines()
            return if (lineIndex in lines.indices) lines[lineIndex] else null
        }

        // Slow path: read from disk (non-open files, e.g. navigating to definition)
        return try {
            val path = URI(uri).let { File(it) }
            val lines = path.readLines()
            if (lineIndex in lines.indices) lines[lineIndex] else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Clear all data (for tests).
     */
    fun clear() {
        files.clear()
        classToUri.clear()
        uriToClass.clear()
        methodLocations.clear()
        fieldLocations.clear()
        classUsages.clear()
        stringIndex.clear()
        stringIndexDirty = true
        subclassIndex.clear()
        implementorsIndex.clear()
        methodUsages.clear()
        fieldUsages.clear()
        classRefLocations.clear()
        documentContents.clear()
    }
    
    private fun methodSignature(className: String, methodName: String, descriptor: String): String {
        return "$className->$methodName$descriptor"
    }
    
    private fun fieldSignature(className: String, fieldName: String): String {
        return "$className->$fieldName"
    }
}

data class IndexStats(
    val classes: Int,
    val methods: Int,
    val fields: Int,
    val strings: Int = 0
)

data class StringSearchResult(
    val value: String,
    val location: Location
)
