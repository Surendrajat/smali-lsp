package xyz.surendrajat.smalilsp.index

import xyz.surendrajat.smalilsp.core.ClassDefinition
import xyz.surendrajat.smalilsp.core.ConstStringInstruction
import xyz.surendrajat.smalilsp.core.FieldDefinition
import xyz.surendrajat.smalilsp.core.MethodDefinition
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.core.range
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.util.concurrent.ConcurrentHashMap

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
    private val stringIndex = ConcurrentHashMap<String, MutableSet<Location>>()
    
    /**
     * Index a smali file. Thread-safe.
     */
    fun indexFile(file: SmaliFile) {
        val className = file.classDefinition.name
        
        // Store file
        files[className] = file
        classToUri[className] = file.uri
        uriToClass[file.uri] = className
        
        // Index methods
        file.methods.forEach { method ->
            val signature = methodSignature(className, method.name, method.descriptor)
            methodLocations.computeIfAbsent(signature) { ConcurrentHashMap.newKeySet() }
                .add(Location(file.uri, method.range))
        }
        
        // Index fields
        file.fields.forEach { field ->
            val signature = fieldSignature(className, field.name)
            fieldLocations[signature] = Location(file.uri, field.range)
        }
        
        // Track superclass usage
        file.classDefinition.superClass?.let { superClass ->
            classUsages.computeIfAbsent(superClass) { ConcurrentHashMap.newKeySet() }
                .add(file.uri)
        }
        
        // Track interface usages
        file.classDefinition.interfaces.forEach { iface ->
            classUsages.computeIfAbsent(iface) { ConcurrentHashMap.newKeySet() }
                .add(file.uri)
        }

        // Index string literals
        file.methods.forEach { method ->
            method.instructions.forEach { instr ->
                if (instr is ConstStringInstruction) {
                    stringIndex.computeIfAbsent(instr.value) { ConcurrentHashMap.newKeySet() }
                        .add(Location(file.uri, instr.range))
                }
            }
        }
    }
    
    /**
     * Find a class definition in workspace.
     * Returns null for SDK/system classes (not in user's APK).
     */
    fun findClass(className: String): SmaliFile? {
        return files[className]
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
     * Search string literals by substring match (case-insensitive).
     * Returns matching string values with their locations.
     */
    fun searchStrings(query: String, maxResults: Int = 500): List<StringSearchResult> {
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
        return stringIndex[value] ?: emptySet()
    }

    /**
     * Find all files that reference a class.
     */
    fun findClassUsages(className: String): Set<String> {
        return classUsages[className] ?: emptySet()
    }
    
    /**
     * Get index statistics.
     */
    fun getStats(): IndexStats {
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
     * Get all indexed class names (for comprehensive testing).
     * Returns a list copy to avoid concurrent modification issues.
     */
    fun getAllClassNames(): List<String> {
        return files.keys.toList()
    }
    
    /**
     * Get all indexed files (for find references).
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
