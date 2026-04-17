package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import xyz.surendrajat.smalilsp.providers.DiagnosticProvider
/**
 * Tests for DiagnosticProvider - detecting syntax and semantic errors.
 */
class DiagnosticProviderTest {
    
    private lateinit var index: WorkspaceIndex
    private lateinit var parser: SmaliParser
    private lateinit var provider: DiagnosticProvider
    
    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        parser = SmaliParser()
        provider = DiagnosticProvider(parser, index)
        
        // Index SDK classes
        indexSDKClass("Ljava/lang/Object;")
        indexSDKClass("Ljava/lang/String;")
        indexSDKClass("Ljava/lang/Integer;")
        indexSDKClass("Ljava/util/List;")
    }
    
    private fun indexSDKClass(className: String) {
        val content = """
            .class public $className
            .super Ljava/lang/Object;
        """.trimIndent()
        
        parser.parse("sdk://$className", content)?.let { file ->
            index.indexFile(file)
        }
    }
    
    @Test
    fun `no diagnostics for valid file`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public foo()V
                return-void
            .end method
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        
        assertEquals(0, diagnostics.size, "Valid file should have no diagnostics")
    }
    
    @Test
    fun `warning for undefined superclass`() {
        val content = """
            .class public LTest;
            .super Lcom/example/NonExistent;
            
            .method public foo()V
                return-void
            .end method
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        
        assertEquals(1, diagnostics.size)
        assertEquals(DiagnosticSeverity.Warning, diagnostics[0].severity)
        assertTrue(diagnostics[0].message.contains("Superclass"))
        assertTrue(diagnostics[0].message.contains("not found"))
        assertEquals("undefined-class", diagnostics[0].code?.left)
    }
    
    @Test
    fun `no warning for SDK superclass`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public foo()V
                return-void
            .end method
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        
        assertEquals(0, diagnostics.size, "SDK classes should not trigger warnings")
    }
    
    @Test
    fun `warning for undefined interface`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            .implements Lcom/example/NonExistentInterface;
            
            .method public foo()V
                return-void
            .end method
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        
        assertTrue(diagnostics.any { 
            it.message.contains("Interface") && it.message.contains("not found") 
        }, "Should warn about undefined interface")
    }
    
    @Test
    fun `warning for undefined class in method invocation`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public foo()V
                invoke-virtual {v0}, Lcom/example/NonExistent;->bar()V
                return-void
            .end method
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        
        assertTrue(diagnostics.any { 
            it.message.contains("Lcom/example/NonExistent;") && it.message.contains("not found")
        }, "Should warn about undefined class in invoke")
    }
    
    @Test
    fun `no warning for SDK classes in method invocation`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public foo()V
                invoke-virtual {v0}, Ljava/lang/String;->length()I
                return-void
            .end method
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        
        assertEquals(0, diagnostics.size, "SDK classes in invocations should not trigger warnings")
    }
    
    @Test
    fun `information for undefined field type`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public myField:Lcom/example/CustomType;
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        
        assertTrue(diagnostics.any { 
            it.severity == DiagnosticSeverity.Information &&
            it.message.contains("Lcom/example/CustomType;")
        }, "Should show info diagnostic for undefined field type")
    }
    
    @Test
    fun `multiple undefined references generate multiple diagnostics`() {
        val content = """
            .class public LTest;
            .super Lcom/example/Missing1;
            .implements Lcom/example/Missing2;
            
            .field public field1:Lcom/example/Missing3;
            
            .method public foo()V
                invoke-virtual {v0}, Lcom/example/Missing4;->bar()V
                invoke-static {v1}, Lcom/example/Missing5;->baz()I
                return-void
            .end method
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        
        // Should have diagnostics for all missing classes
        assertTrue(diagnostics.size >= 5, "Should have at least 5 diagnostics for 5 missing classes")
        
        // Check each missing class is reported
        assertTrue(diagnostics.any { it.message.contains("Missing1") })
        assertTrue(diagnostics.any { it.message.contains("Missing2") })
        assertTrue(diagnostics.any { it.message.contains("Missing3") })
        assertTrue(diagnostics.any { it.message.contains("Missing4") })
        assertTrue(diagnostics.any { it.message.contains("Missing5") })
    }
    
    @Test
    fun `no warning for primitive types in fields`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public intField:I
            .field public longField:J
            .field public booleanField:Z
            .field public byteField:B
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        
        assertEquals(0, diagnostics.size, "Primitive types should not trigger diagnostics")
    }
    
    @Test
    fun `no warning for array types of SDK classes`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public stringArray:[Ljava/lang/String;
            .field public intArray:[I
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        
        assertEquals(0, diagnostics.size, "Array types should work correctly")
    }
    
    @Test
    fun `warning for array types of undefined classes`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public customArray:[Lcom/example/CustomType;
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        
        assertTrue(diagnostics.any { 
            it.message.contains("CustomType") && it.message.contains("not found")
        }, "Should warn about undefined class in array type")
    }
    
    @Test
    fun `handles unparseable files gracefully`() {
        val content = """
            this is not valid smali code at all
            it should not crash the diagnostic provider
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        
        // Should not crash, diagnostics may be empty or contain parse errors
        // The important thing is it doesn't throw exception
        assertTrue(true, "Should handle unparseable files gracefully")
    }
    
    @Test
    fun `handles very large files efficiently`() {
        // Generate file with 1000 methods
        val methods = (1..1000).joinToString("\n") { i ->
            """
            .method public method$i()V
                return-void
            .end method
            """.trimIndent()
        }
        
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            $methods
        """.trimIndent()
        
        val startTime = System.currentTimeMillis()
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        val duration = System.currentTimeMillis() - startTime
        
        assertTrue(duration < 500, "Should process large file in < 500ms, took ${duration}ms")
        assertEquals(0, diagnostics.size, "Large valid file should have no diagnostics")
    }
    
    @Test
    fun `respects diagnostic severity levels`() {
        val content = """
            .class public LTest;
            .super Lcom/example/MissingSuperclass;
            
            .field public myField:Lcom/example/MissingFieldType;
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        
        // Superclass should be WARNING
        val superclassDiagnostic = diagnostics.find { it.message.contains("Superclass") }
        assertEquals(DiagnosticSeverity.Warning, superclassDiagnostic?.severity)
        
        // Field type should be INFORMATION
        val fieldDiagnostic = diagnostics.find { it.message.contains("Type") }
        assertEquals(DiagnosticSeverity.Information, fieldDiagnostic?.severity)
    }
    
    @Test
    fun `cross-file references work correctly`() {
        // Index Foo class
        val fooContent = """
            .class public Lcom/example/Foo;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        parser.parse("file:///Foo.smali", fooContent)?.let { file ->
            index.indexFile(file)
        }
        
        // Reference Foo from Test
        val testContent = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public foo()V
                invoke-virtual {v0}, Lcom/example/Foo;->bar()V
                return-void
            .end method
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("file:///Test.smali", testContent)
        
        // Should not warn about Foo since it's indexed
        val fooDiagnostics = diagnostics.filter { it.message.contains("Foo") }
        assertEquals(0, fooDiagnostics.size, "Should not warn about indexed cross-file classes")
    }
    
    @Test
    fun `all diagnostic codes are set correctly`() {
        val content = """
            .class public LTest;
            .super Lcom/example/Missing;
        """.trimIndent()
        
        val diagnostics = provider.computeDiagnostics("test.smali", content)
        
        diagnostics.forEach { diagnostic ->
            assertTrue(diagnostic.code != null, "All diagnostics should have a code")
            assertTrue(diagnostic.source == "smali-lsp", "All diagnostics should be from smali-lsp")
        }
    }

    @Test
    fun `computeSyntaxDiagnosticsFromParseResult returns only syntax errors`() {
        // File with both syntax issues AND undefined class references
        val content = """
            .class public LTest;
            .super Lcom/example/NonExistent;
            
            .method public foo()V
                invoke-virtual {v0}, Lcom/example/Missing;->bar()V
                return-void
            .end method
        """.trimIndent()

        val parseResult = parser.parseWithErrors("test.smali", content)

        // Full diagnostics should include semantic warnings (undefined classes)
        val fullDiagnostics = provider.computeDiagnosticsFromParseResult("test.smali", parseResult)

        // Syntax-only diagnostics should NOT include semantic warnings
        val syntaxOnly = provider.computeSyntaxDiagnosticsFromParseResult(parseResult)

        // The file is syntactically valid, so no syntax errors
        assertEquals(0, syntaxOnly.size, "Syntactically valid file should have no syntax-only diagnostics")
        // But full diagnostics should find the undefined classes
        assertTrue(fullDiagnostics.size > 0, "Should have semantic warnings for undefined classes")
        assertTrue(fullDiagnostics.all { it.code?.left == "undefined-class" }, "All should be undefined-class warnings")
    }

    @Test
    fun `computeSyntaxDiagnosticsFromParseResult includes syntax errors`() {
        // Malformed smali: .class without a class name then .super
        val content = """
            .class public
            .super Ljava/lang/Object;
        """.trimIndent()

        val parseResult = parser.parseWithErrors("test.smali", content)
        val syntaxOnly = provider.computeSyntaxDiagnosticsFromParseResult(parseResult)

        assertTrue(syntaxOnly.isNotEmpty(), "Should detect syntax errors in malformed file")
        assertTrue(syntaxOnly.all { it.code?.left == "syntax-error" })
    }
}
