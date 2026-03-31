package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import xyz.surendrajat.smalilsp.providers.DiagnosticProvider
/**
 * Tests for syntax error diagnostics.
 * Verifies that syntax errors are detected and reported correctly.
 */
class SyntaxErrorDiagnosticsTest {
    
    private val parser = SmaliParser()
    private val index = WorkspaceIndex()
    private val diagnosticProvider = DiagnosticProvider(parser, index)
    
    @Test
    fun `syntax error - missing class name`() {
        val content = """
            .class public
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val diagnostics = diagnosticProvider.computeDiagnostics("file:///test.smali", content)
        
        // Should have at least one syntax error
        val syntaxErrors = diagnostics.filter { it.severity == DiagnosticSeverity.Error }
        assertTrue(syntaxErrors.isNotEmpty(), "Should detect syntax error for missing class name")
        
        println("✅ Detected syntax error: ${syntaxErrors[0].message}")
    }
    
    @Test
    fun `syntax error - invalid token`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                this-is-not-valid
                return-void
            .end method
        """.trimIndent()
        
        val diagnostics = diagnosticProvider.computeDiagnostics("file:///test.smali", content)
        
        // Should have at least one error (syntax or semantic)
        val errors = diagnostics.filter { 
            it.severity == DiagnosticSeverity.Error || it.severity == DiagnosticSeverity.Warning
        }
        assertTrue(errors.isNotEmpty(), "Should detect error for invalid token")
        
        println("✅ Detected error: ${errors[0].message}")
    }
    
    @Test
    fun `syntax error - incomplete method`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                return-void
            # Missing .end method
        """.trimIndent()
        
        val diagnostics = diagnosticProvider.computeDiagnostics("file:///test.smali", content)
        
        // Should have at least one syntax error
        val syntaxErrors = diagnostics.filter { it.severity == DiagnosticSeverity.Error }
        assertTrue(syntaxErrors.isNotEmpty(), "Should detect syntax error for incomplete method")
        
        println("✅ Detected syntax error: ${syntaxErrors[0].message}")
    }
    
    @Test
    fun `valid file has no syntax errors`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                return-void
            .end method
        """.trimIndent()
        
        val diagnostics = diagnosticProvider.computeDiagnostics("file:///test.smali", content)
        
        // Should have no syntax errors (may have semantic warnings)
        val syntaxErrors = diagnostics.filter { 
            it.code.isLeft && it.code.left == "syntax-error"
        }
        assertEquals(0, syntaxErrors.size, "Valid file should have no syntax errors")
        
        println("✅ Valid file has no syntax errors")
    }
    
    @Test
    fun `multiple syntax errors reported`() {
        val content = """
            .class
            .super
            .method
        """.trimIndent()
        
        val diagnostics = diagnosticProvider.computeDiagnostics("file:///test.smali", content)
        
        // Should have multiple syntax errors
        val syntaxErrors = diagnostics.filter { it.severity == DiagnosticSeverity.Error }
        assertTrue(syntaxErrors.size >= 2, "Should detect multiple syntax errors")
        
        println("✅ Detected ${syntaxErrors.size} syntax errors")
        syntaxErrors.forEach { println("   - ${it.message}") }
    }
    
    @Test
    fun `syntax error on didChange`() {
        val uri = "file:///test.smali"
        
        // First: valid content
        val validContent = """
            .class public LTest;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val diagnostics1 = diagnosticProvider.computeDiagnostics(uri, validContent)
        val errors1 = diagnostics1.filter { 
            it.code.isLeft && it.code.left == "syntax-error"
        }
        assertEquals(0, errors1.size, "Valid content should have no syntax errors")
        
        // Then: introduce syntax error
        val invalidContent = """
            .class public
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val diagnostics2 = diagnosticProvider.computeDiagnostics(uri, invalidContent)
        val errors2 = diagnostics2.filter { it.severity == DiagnosticSeverity.Error }
        assertTrue(errors2.isNotEmpty(), "Invalid content should have syntax errors")
        
        println("✅ Syntax error detected on content change")
    }
}
