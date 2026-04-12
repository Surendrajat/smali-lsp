package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.util.ClassUtils
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
     * Compute diagnostics from an already-parsed result.
     * Avoids re-parsing when the caller already has a ParseResult (e.g. didChange).
     */
    fun computeDiagnosticsFromParseResult(uri: String, parseResult: SmaliParser.ParseResult): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        diagnostics.addAll(convertSyntaxErrors(parseResult.syntaxErrors))
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
        return ParseResult(
            smaliFile = parseResult.smaliFile,
            syntaxDiagnostics = convertSyntaxErrors(parseResult.syntaxErrors)
        )
    }

    private fun convertSyntaxErrors(errors: List<SmaliParser.SyntaxError>): List<Diagnostic> {
        return errors.map { error ->
            createDiagnostic(
                range = Range(
                    Position(error.line - 1, error.charPositionInLine),
                    Position(error.line - 1, error.charPositionInLine + 10)
                ),
                message = error.message,
                severity = DiagnosticSeverity.Error,
                code = "syntax-error"
            )
        }
    }
    
    /**
     * Compute semantic diagnostics for a parsed file.
     * Checks for undefined references, missing classes, etc.
     */
    private fun computeSemanticDiagnostics(uri: String, file: SmaliFile): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        
        // Check superclass exists
        file.classDefinition.superClass?.let { superClass ->
            if (!index.hasClass(superClass) && !ClassUtils.isSDKClass(superClass)) {
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
            if (!index.hasClass(interfaceName) && !ClassUtils.isSDKClass(interfaceName)) {
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
                        if (!index.hasClass(instruction.className) && !ClassUtils.isSDKClass(instruction.className)) {
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
                        if (!index.hasClass(instruction.className) && !ClassUtils.isSDKClass(instruction.className)) {
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
                        if (!index.hasClass(instruction.className) && !ClassUtils.isSDKClass(instruction.className)) {
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
                    is xyz.surendrajat.smalilsp.core.JumpInstruction -> {
                        // JumpInstructions reference labels within the same method
                        // Label validation could be added here if needed
                    }
                    is xyz.surendrajat.smalilsp.core.ConstStringInstruction -> {
                        // String literals don't need diagnostics
                    }
                }
            }
        }
        
        // Check field references
        file.fields.forEach { field ->
            val typeClass = extractTypeClass(field.type)
            if (typeClass != null && !index.hasClass(typeClass) && !ClassUtils.isSDKClass(typeClass)) {
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


