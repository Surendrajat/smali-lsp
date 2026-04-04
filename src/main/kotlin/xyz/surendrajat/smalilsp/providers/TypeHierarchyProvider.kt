package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.*
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/**
 * Provides type hierarchy (supertypes/subtypes) for smali classes.
 *
 * LSP Requests:
 * - textDocument/prepareTypeHierarchy
 * - typeHierarchy/supertypes
 * - typeHierarchy/subtypes
 */
class TypeHierarchyProvider(
    private val index: WorkspaceIndex
) {

    /**
     * Prepare type hierarchy at the given position.
     * Identifies the class at cursor and returns a TypeHierarchyItem for it.
     */
    fun prepare(uri: String, position: Position): List<TypeHierarchyItem> {
        val file = index.findFileByUri(uri) ?: return emptyList()

        // Check if cursor is on the class definition line
        val classDef = file.classDefinition
        if (classDef.range.start.line == position.line) {
            return listOf(createItem(file))
        }

        // Check if cursor is on a .super directive
        if (classDef.superClassRange != null && classDef.superClassRange.start.line == position.line) {
            val superFile = classDef.superClass?.let { index.findClass(it) }
            return if (superFile != null) {
                listOf(createItem(superFile))
            } else {
                // Super class not in workspace — return current class so user can still navigate
                listOf(createItem(file))
            }
        }

        // Check if cursor is on an .implements directive
        for ((ifaceName, ifaceRange) in classDef.interfaceRanges) {
            if (ifaceRange.start.line == position.line) {
                val ifaceFile = index.findClass(ifaceName)
                return if (ifaceFile != null) {
                    listOf(createItem(ifaceFile))
                } else {
                    listOf(createItem(file))
                }
            }
        }

        // Default: return the class for this file
        return listOf(createItem(file))
    }

    /**
     * Find all supertypes (parent class + implemented interfaces).
     */
    fun supertypes(item: TypeHierarchyItem): List<TypeHierarchyItem> {
        val className = item.detail ?: return emptyList()
        val file = index.findClass(className) ?: return emptyList()
        val results = mutableListOf<TypeHierarchyItem>()

        // Add superclass
        file.classDefinition.superClass?.let { superName ->
            val superFile = index.findClass(superName)
            if (superFile != null) {
                results.add(createItem(superFile))
            } else if (superName != "Ljava/lang/Object;") {
                results.add(createExternalItem(superName))
            }
        }

        // Add interfaces
        for (ifaceName in file.classDefinition.interfaces) {
            val ifaceFile = index.findClass(ifaceName)
            if (ifaceFile != null) {
                results.add(createItem(ifaceFile))
            } else {
                results.add(createExternalItem(ifaceName))
            }
        }

        return results
    }

    /**
     * Find all subtypes (direct subclasses and implementors).
     */
    fun subtypes(item: TypeHierarchyItem): List<TypeHierarchyItem> {
        val className = item.detail ?: return emptyList()
        val results = mutableListOf<TypeHierarchyItem>()

        for (file in index.getAllFiles()) {
            val classDef = file.classDefinition

            // Direct subclass
            if (classDef.superClass == className) {
                results.add(createItem(file))
                continue
            }

            // Direct implementor
            if (className in classDef.interfaces) {
                results.add(createItem(file))
            }
        }

        return results
    }

    private fun createItem(file: SmaliFile): TypeHierarchyItem {
        val classDef = file.classDefinition
        val kind = if (classDef.modifiers.contains("interface")) SymbolKind.Interface else SymbolKind.Class
        val item = TypeHierarchyItem(
            extractSimpleName(classDef.name),
            kind,
            file.uri,
            classDef.range,
            classDef.range,
            classDef.name
        )
        return item
    }

    private fun createExternalItem(className: String): TypeHierarchyItem {
        val uri = index.getUri(className) ?: "external:$className"
        val zeroRange = Range(Position(0, 0), Position(0, 0))
        val item = TypeHierarchyItem(
            extractSimpleName(className),
            SymbolKind.Class,
            uri,
            zeroRange,
            zeroRange,
            className
        )
        return item
    }

    private fun extractSimpleName(fullName: String): String {
        // "Lcom/example/MyClass;" -> "MyClass"
        val withoutPrefix = fullName.removePrefix("L").removeSuffix(";")
        return withoutPrefix.substringAfterLast('/')
    }
}
