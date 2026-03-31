package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import org.slf4j.LoggerFactory

/**
 * Provides diagnostic information for Smali files.
 * 
 * Two types of diagnostics:
 * 1. Syntax errors from parser (ANTLR errors)
 * 2. Semantic errors from analysis (undefined references, etc.)
 */
class DiagnosticProvider(
    private val parser: SmaliParser,
    private val index: WorkspaceIndex
) {
    private val logger = LoggerFactory.getLogger(DiagnosticProvider::class.java)
    
    /**
     * Compute all diagnostics for a file.
     * Includes both syntax and semantic errors.
     * 
     * @param uri File URI
     * @param content File content
     * @return List of diagnostics to display
     */
    fun computeDiagnostics(uri: String, content: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        
        // 1. Parse with error recovery to get syntax errors
        val parseResult = parseWithErrorRecovery(uri, content)
        diagnostics.addAll(parseResult.syntaxDiagnostics)
        
        // 2. If we have an AST (even partial), check semantic errors
        parseResult.smaliFile?.let { file ->
            diagnostics.addAll(computeSemanticDiagnostics(uri, file))
        }
        
        return diagnostics
    }
    
    /**
     * Parse file with error recovery enabled.
     * Collects syntax errors but continues parsing.
     */
    private fun parseWithErrorRecovery(uri: String, content: String): ParseResult {
        val parseResult = parser.parseWithErrors(uri, content)
        
        // Convert parser syntax errors to LSP diagnostics
        val diagnostics = parseResult.syntaxErrors.map { error ->
            createDiagnostic(
                range = Range(
                    Position(error.line - 1, error.charPositionInLine), // LSP is 0-indexed
                    Position(error.line - 1, error.charPositionInLine + 10)
                ),
                message = error.message,
                severity = DiagnosticSeverity.Error,
                code = "syntax-error"
            )
        }
        
        return ParseResult(
            smaliFile = parseResult.smaliFile,
            syntaxDiagnostics = diagnostics
        )
    }
    
    /**
     * Compute semantic diagnostics for a parsed file.
     * Checks for undefined references, missing classes, etc.
     */
    private fun computeSemanticDiagnostics(uri: String, file: SmaliFile): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        
        // Check superclass exists
        file.classDefinition.superClass?.let { superClass ->
            if (!index.hasClass(superClass) && !isSystemClass(superClass)) {
                diagnostics.add(
                    createDiagnostic(
                        range = Range(
                            Position(file.classDefinition.range.start.line, 0),
                            Position(file.classDefinition.range.end.line, 100)
                        ),
                        message = "Superclass '$superClass' not found in workspace or SDK",
                        severity = DiagnosticSeverity.Warning,
                        code = "undefined-class"
                    )
                )
            }
        }
        
        // Check interfaces exist
        file.classDefinition.interfaces.forEach { interfaceName ->
            if (!index.hasClass(interfaceName) && !isSystemClass(interfaceName)) {
                diagnostics.add(
                    createDiagnostic(
                        range = Range(
                            Position(file.classDefinition.range.start.line, 0),
                            Position(file.classDefinition.range.end.line, 100)
                        ),
                        message = "Interface '$interfaceName' not found in workspace or SDK",
                        severity = DiagnosticSeverity.Warning,
                        code = "undefined-class"
                    )
                )
            }
        }
        
        // Check method invocations
        file.methods.forEach { method ->
            method.instructions?.forEach { instruction ->
                when (instruction) {
                    is xyz.surendrajat.smalilsp.core.InvokeInstruction -> {
                        if (!index.hasClass(instruction.className) && !isSystemClass(instruction.className)) {
                            diagnostics.add(
                                createDiagnostic(
                                    range = instruction.range,
                                    message = "Class '${instruction.className}' not found in workspace or SDK",
                                    severity = DiagnosticSeverity.Warning,
                                    code = "undefined-class"
                                )
                            )
                        }
                    }
                    is xyz.surendrajat.smalilsp.core.FieldAccessInstruction -> {
                        if (!index.hasClass(instruction.className) && !isSystemClass(instruction.className)) {
                            diagnostics.add(
                                createDiagnostic(
                                    range = instruction.range,
                                    message = "Class '${instruction.className}' not found in workspace or SDK",
                                    severity = DiagnosticSeverity.Warning,
                                    code = "undefined-class"
                                )
                            )
                        }
                    }
                    is xyz.surendrajat.smalilsp.core.TypeInstruction -> {
                        if (!index.hasClass(instruction.className) && !isSystemClass(instruction.className)) {
                            diagnostics.add(
                                createDiagnostic(
                                    range = instruction.range,
                                    message = "Class '${instruction.className}' not found in workspace or SDK",
                                    severity = DiagnosticSeverity.Warning,
                                    code = "undefined-class"
                                )
                            )
                        }
                    }
                }
            }
        }
        
        // Check field references
        file.fields.forEach { field ->
            val typeClass = extractTypeClass(field.type)
            if (typeClass != null && !index.hasClass(typeClass) && !isSystemClass(typeClass)) {
                diagnostics.add(
                    createDiagnostic(
                        range = field.range,
                        message = "Type '$typeClass' not found in workspace or SDK",
                        severity = DiagnosticSeverity.Information,
                        code = "undefined-class"
                    )
                )
            }
        }
        
        return diagnostics
    }
    
    /**
     * Extract class from type descriptor.
     * Example: "Ljava/lang/String;" → "Ljava/lang/String;"
     * Example: "[Lcom/example/Foo;" → "Lcom/example/Foo;"
     */
    private fun extractTypeClass(type: String): String? {
        // Remove array markers
        val baseType = type.replace("[", "")
        
        // Only class types start with L
        return if (baseType.startsWith("L")) baseType else null
    }
    
    /**
     * Check if a class is a system class (java.*, android.*, etc.)
     * System classes might not be indexed if SDK is not configured.
     */
    private fun isSystemClass(className: String): Boolean {
        // Array types are always valid (e.g., [I, [Ljava/lang/String;, [[I)
        if (className.startsWith("[")) {
            return true
        }
        
        return className.startsWith("Ljava/") ||
               className.startsWith("Ljavax/") ||
               className.startsWith("Landroid/") ||
               className.startsWith("Ldalvik/") ||
               className.startsWith("Lkotlin/") ||
               className.startsWith("Lkotlinx/")
    }
    
    /**
     * Create a diagnostic with standard formatting.
     */
    private fun createDiagnostic(
        range: Range,
        message: String,
        severity: DiagnosticSeverity,
        code: String
    ): Diagnostic {
        return Diagnostic(range, message, severity, "smali-lsp", code)
    }
    
    /**
     * Result of parsing with error recovery.
     */
    private data class ParseResult(
        val smaliFile: SmaliFile?,
        val syntaxDiagnostics: List<Diagnostic>
    )
}


