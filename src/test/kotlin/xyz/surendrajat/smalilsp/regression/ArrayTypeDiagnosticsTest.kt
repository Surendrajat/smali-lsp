package xyz.surendrajat.smalilsp.regression

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DiagnosticProvider

/**
 * Test suite for array type diagnostics bug.
 * 
 * User reported issue:
 * - check-cast p1, [I shows "Class '[I' not found in workspace or SDK"
 * - check-cast p1, [Ljava/lang/Object; shows similar error
 * 
 * Both are VALID array types and should NOT show diagnostics.
 */
class ArrayTypeDiagnosticsTest {
    
    private lateinit var parser: SmaliParser
    private lateinit var workspaceIndex: WorkspaceIndex
    private lateinit var diagnosticProvider: DiagnosticProvider
    
    @BeforeEach
    fun setup() {
        parser = SmaliParser()
        workspaceIndex = WorkspaceIndex()
        diagnosticProvider = DiagnosticProvider(parser, workspaceIndex)
    }
    
    @Test
    fun `primitive array check-cast should not show diagnostic`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                check-cast p1, [I
                return-void
            .end method
        """.trimIndent()
        
        val diagnostics = diagnosticProvider.computeDiagnostics("file:///Test.smali", content)
        
        // Should have NO diagnostics for [I (primitive int array)
        val arrayDiagnostics = diagnostics.filter { it.message.contains("[I") }
        assertEquals(0, arrayDiagnostics.size, 
            "Primitive array type [I should not show diagnostic. Found: ${arrayDiagnostics.map { it.message }}")
    }
    
    @Test
    fun `object array check-cast should not show diagnostic`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                check-cast p1, [Ljava/lang/Object;
                return-void
            .end method
        """.trimIndent()
        
        val diagnostics = diagnosticProvider.computeDiagnostics("file:///Test.smali", content)
        
        // Should have NO diagnostics for [Ljava/lang/Object; (Object array)
        val arrayDiagnostics = diagnostics.filter { 
            it.message.contains("[Ljava/lang/Object;") || it.message.contains("Ljava/lang/Object")
        }
        assertEquals(0, arrayDiagnostics.size,
            "Object array type [Ljava/lang/Object; should not show diagnostic. Found: ${arrayDiagnostics.map { it.message }}")
    }
    
    @Test
    fun `multi-dimensional array should not show diagnostic`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                check-cast p1, [[I
                check-cast p2, [[[Ljava/lang/String;
                return-void
            .end method
        """.trimIndent()
        
        val diagnostics = diagnosticProvider.computeDiagnostics("file:///Test.smali", content)
        
        // Should have NO diagnostics for multi-dimensional arrays
        val arrayDiagnostics = diagnostics.filter { it.message.contains("[[") }
        assertEquals(0, arrayDiagnostics.size,
            "Multi-dimensional array types should not show diagnostic. Found: ${arrayDiagnostics.map { it.message }}")
    }
    
    @Test
    fun `undefined non-array class should still show diagnostic`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                check-cast p1, Lcom/example/UndefinedClass;
                return-void
            .end method
        """.trimIndent()
        
        val diagnostics = diagnosticProvider.computeDiagnostics("file:///Test.smali", content)
        
        // SHOULD have diagnostic for undefined class (not in workspace, not SDK)
        val undefinedDiagnostics = diagnostics.filter { it.message.contains("UndefinedClass") }
        assertTrue(undefinedDiagnostics.isNotEmpty(),
            "Undefined class should show diagnostic")
    }
    
    @Test
    fun `array field type should not show diagnostic`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field private myArray:[I
            .field private objectArray:[Ljava/lang/String;
        """.trimIndent()
        
        val diagnostics = diagnosticProvider.computeDiagnostics("file:///Test.smali", content)
        
        // Should have NO diagnostics for array field types
        val arrayDiagnostics = diagnostics.filter { 
            it.message.contains("[I") || it.message.contains("[Ljava/lang/String")
        }
        assertEquals(0, arrayDiagnostics.size,
            "Array field types should not show diagnostic. Found: ${arrayDiagnostics.map { it.message }}")
    }
}
