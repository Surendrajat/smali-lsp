package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.*
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.MethodDefinition
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/**
 * Provides call hierarchy (incoming/outgoing calls) for smali methods.
 *
 * LSP Requests:
 * - textDocument/prepareCallHierarchy
 * - callHierarchy/incomingCalls
 * - callHierarchy/outgoingCalls
 */
class CallHierarchyProvider(
    private val index: WorkspaceIndex
) {

    /**
     * Prepare call hierarchy at the given position.
     * Identifies the method at cursor and returns a CallHierarchyItem for it.
     */
    fun prepare(uri: String, position: Position): List<CallHierarchyItem> {
        val file = index.findFileByUri(uri) ?: return emptyList()

        // Find the method containing the cursor position
        val method = file.methods.find { methodContainsPosition(it, position) }
            ?: return emptyList()

        return listOf(createItem(file, method))
    }

    /**
     * Find all callers of the given method (incoming calls).
     * Scans all indexed files for invoke instructions targeting this method.
     */
    fun incomingCalls(item: CallHierarchyItem): List<CallHierarchyIncomingCall> {
        val targetMethod = parseItemName(item.name)
        val targetClass = item.detail ?: return emptyList()

        val results = mutableListOf<CallHierarchyIncomingCall>()

        for (file in index.getAllFiles()) {
            for (method in file.methods) {
                val callSites = method.instructions
                    .filterIsInstance<InvokeInstruction>()
                    .filter { it.className == targetClass && it.methodName == targetMethod.first && it.descriptor == targetMethod.second }

                if (callSites.isNotEmpty()) {
                    val call = CallHierarchyIncomingCall()
                    call.from = createItem(file, method)
                    call.fromRanges = callSites.map { it.range }
                    results.add(call)
                }
            }
        }

        return results
    }

    /**
     * Find all methods called from the given method (outgoing calls).
     * Scans invoke instructions within the method.
     */
    fun outgoingCalls(item: CallHierarchyItem): List<CallHierarchyOutgoingCall> {
        val itemClass = item.detail ?: return emptyList()
        val file = index.findClass(itemClass) ?: return emptyList()

        val method = file.methods.find { it.range == item.range }
            ?: return emptyList()

        // Group invoke instructions by target method to consolidate calls
        val callsByTarget = method.instructions
            .filterIsInstance<InvokeInstruction>()
            .groupBy { Triple(it.className, it.methodName, it.descriptor) }

        val results = mutableListOf<CallHierarchyOutgoingCall>()

        for ((target, callSites) in callsByTarget) {
            val (className, methodName, descriptor) = target
            val targetLocations = index.findMethod(className, methodName, descriptor)
            val targetLocation = targetLocations.firstOrNull()

            val toItem = if (targetLocation != null) {
                val targetFile = index.findFileByUri(targetLocation.uri)
                val targetMethodDef = targetFile?.methods?.find { it.range == targetLocation.range }
                if (targetFile != null && targetMethodDef != null) {
                    createItem(targetFile, targetMethodDef)
                } else {
                    // External method — create a synthetic item
                    createExternalItem(className, methodName, descriptor)
                }
            } else {
                createExternalItem(className, methodName, descriptor)
            }

            val call = CallHierarchyOutgoingCall()
            call.to = toItem
            call.fromRanges = callSites.map { it.range }
            results.add(call)
        }

        return results
    }

    private fun createItem(file: SmaliFile, method: MethodDefinition): CallHierarchyItem {
        val item = CallHierarchyItem()
        item.name = "${method.name}${method.descriptor}"
        item.kind = SymbolKind.Method
        item.uri = file.uri
        item.range = method.range
        item.selectionRange = method.range
        item.detail = file.classDefinition.name
        return item
    }

    private fun createExternalItem(className: String, methodName: String, descriptor: String): CallHierarchyItem {
        val item = CallHierarchyItem()
        item.name = "$methodName$descriptor"
        item.kind = SymbolKind.Method
        item.detail = className
        // External methods have no real URI — use the class name as identifier
        val uri = index.getUri(className) ?: "external:$className"
        item.uri = uri
        item.range = Range(Position(0, 0), Position(0, 0))
        item.selectionRange = Range(Position(0, 0), Position(0, 0))
        return item
    }

    /**
     * Parse "methodName(descriptor)returnType" back into (name, descriptor).
     */
    private fun parseItemName(name: String): Pair<String, String> {
        val parenIndex = name.indexOf('(')
        return if (parenIndex >= 0) {
            Pair(name.substring(0, parenIndex), name.substring(parenIndex))
        } else {
            Pair(name, "")
        }
    }

    private fun methodContainsPosition(method: MethodDefinition, position: Position): Boolean {
        val r = method.range
        if (position.line < r.start.line || position.line > r.end.line) return false
        if (position.line == r.start.line && position.character < r.start.character) return false
        if (position.line == r.end.line && position.character > r.end.character) return false
        return true
    }
}
