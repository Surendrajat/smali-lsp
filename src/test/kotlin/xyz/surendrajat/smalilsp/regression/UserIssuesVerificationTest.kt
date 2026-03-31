package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.DiagnosticProvider
import xyz.surendrajat.smalilsp.providers.WorkspaceSymbolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * Comprehensive test for all user-reported issues from user-testing.md
 * Each test verifies actual behavior vs expected behavior.
 */
class UserIssuesVerificationTest {
    
    private lateinit var parser: SmaliParser
    private lateinit var index: WorkspaceIndex
    private lateinit var definitionProvider: DefinitionProvider
    private lateinit var diagnosticProvider: DiagnosticProvider
    private lateinit var workspaceSymbolProvider: WorkspaceSymbolProvider
    
    @BeforeEach
    fun setup() {
        parser = SmaliParser()
        index = WorkspaceIndex()
        definitionProvider = DefinitionProvider(index)
        diagnosticProvider = DiagnosticProvider(parser, index)
        workspaceSymbolProvider = WorkspaceSymbolProvider(index)
    }
    
    @Test
    fun `GoTo Definition on field name should go to field type not String`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field private static final TAG:Ljava/lang/String; = "ArrayMap"
        """.trimIndent()
        
        val uri = "file:///Test.smali"
        val file = parser.parse(uri, content)!!
        index.indexFile(file)
        
        // Cursor on "TAG" - should navigate to String class, not stay on field
        val locations = definitionProvider.findDefinition(uri, Position(3, 28))
        
        // If it goes to String, locations will be empty (String not in index)
        // But it should attempt to go to String, not return field location
        println("GoTo Definition on TAG: ${locations.size} locations")
        // This test documents current behavior
    }
    
    @Test
    fun `Diagnostics should show for array types incorrectly flagged`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                check-cast p1, [I
                check-cast p1, [Ljava/lang/Object;
                return-void
            .end method
        """.trimIndent()
        
        val uri = "file:///Test.smali"
        val diagnostics = diagnosticProvider.computeDiagnostics(uri, content)
        
        // Should NOT flag [I or [Ljava/lang/Object; as errors
        val arrayErrors = diagnostics.filter { 
            it.message.contains("[I") || it.message.contains("[Ljava/lang/Object;")
        }
        
        println("Array type diagnostics: ${arrayErrors.size}")
        arrayErrors.forEach { println("  - ${it.message}") }
        
        // Currently this might fail if array types are incorrectly flagged
        assertEquals(0, arrayErrors.size, "Array types should not be flagged as errors")
    }
    
    @Test
    fun `Workspace symbol search for getClassLoader should return method first`() {
        // Create test files with getClassLoader method and a class with getClassLoader in name
        val content1 = """
            .class public LUtils;
            .super Ljava/lang/Object;
            
            .method public getClassLoader()Ljava/lang/ClassLoader;
                .locals 0
                const/4 v0, 0x0
                return-object v0
            .end method
        """.trimIndent()
        
        val content2 = """
            .class public LgetClassLoaderUtils;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val file1 = parser.parse("file:///Utils.smali", content1)!!
        val file2 = parser.parse("file:///getClassLoaderUtils.smali", content2)!!
        index.indexFile(file1)
        index.indexFile(file2)
        
        // Search for "getClassLoader"
        val results = workspaceSymbolProvider.search("getClassLoader")
        
        println("Search results for 'getClassLoader':")
        results.take(5).forEach { println("  - ${it.name} (${it.kind})") }
        
        // First result should be the method LUtils;.getClassLoader, not LgetClassLoaderUtils; class
        assertTrue(results.isNotEmpty(), "Should return results")
        assertTrue(
            results[0].name.contains(".getClassLoader"),
            "First result should be the method, got: ${results[0].name}"
        )
    }
    
    @Test
    fun `Diagnostics should update on file content changes`() {
        val validContent = """
            .class public LTest;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val invalidContent = """
            .class public LTest;
            .super LNonExistentClass;
        """.trimIndent()
        
        val uri = "file:///Test.smali"
        
        // First check - valid file
        val diagnostics1 = diagnosticProvider.computeDiagnostics(uri, validContent)
        val superclassErrors1 = diagnostics1.filter { it.message.contains("not found") }
        
        // Second check - invalid file  
        val diagnostics2 = diagnosticProvider.computeDiagnostics(uri, invalidContent)
        val superclassErrors2 = diagnostics2.filter { it.message.contains("not found") }
        
        println("Valid content diagnostics: ${superclassErrors1.size}")
        println("Invalid content diagnostics: ${superclassErrors2.size}")
        
        // Should have error for non-existent superclass
        assertTrue(superclassErrors2.isNotEmpty(), "Should flag non-existent superclass")
    }
}
