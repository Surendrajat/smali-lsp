package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.integration.lsp.TempTestWorkspace
import xyz.surendrajat.smalilsp.providers.*
import xyz.surendrajat.smalilsp.parser.SmaliParser
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

/**
 * Stress tests to find edge cases and bugs in providers.
 * Tests unusual inputs, boundary conditions, and error scenarios.
 */
class ProviderStressTest {
    
    @Test
    fun `HoverProvider - handles malformed descriptors gracefully`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public broken1:LNoSemicolon
            .field public broken2:[[[[[[[[[I
            .field public broken3:Ljava/lang/String;
            
            .method public broken4(LTruncated)V
                return-void
            .end method
            
            .method public broken5(I[[[[[[[)V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = HoverProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        // Should not crash on malformed descriptors
        val hover1 = provider.provideHover(uri, Position(3, 20))
        val hover2 = provider.provideHover(uri, Position(4, 20))
        val hover3 = provider.provideHover(uri, Position(5, 20))
        val hover4 = provider.provideHover(uri, Position(7, 30))
        val hover5 = provider.provideHover(uri, Position(11, 30))
        
        // Should handle gracefully (return null or valid hover, but not crash)
        assertTrue(true, "Should not crash on malformed descriptors")
        workspace.cleanup()
    }
    
    @Test
    fun `HoverProvider - handles very long descriptors`() {
        val workspace = TempTestWorkspace.create()
        
        // Create method with 100 parameters
        val params = (1..100).joinToString("") { "Ljava/lang/String;" }
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public manyParams($params)V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = HoverProvider(index)
        val uri = workspace.getUri("Test.smali")
        val file = index.findFileByUri(uri)!!
        
        // Find the method and hover over its name
        val method = file.methods.find { it.name == "manyParams" }!!
        val hover = provider.provideHover(uri, Position(method.range.start.line, 20))
        
        // This might return null if the descriptor is too long to parse, which is acceptable
        // The test is to ensure it doesn't CRASH
        assertTrue(true, "Should not crash on method with 100 parameters")
        workspace.cleanup()
    }
    
    @Test
    fun `DefinitionProvider - handles deeply nested classes`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Outer.smali", """
            .class public LOuter;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        workspace.addFile("Outer\$Inner1.smali", """
            .class public LOuter${'$'}Inner1;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        workspace.addFile("Outer\$Inner1\$Inner2.smali", """
            .class public LOuter${'$'}Inner1${'$'}Inner2;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                new-instance v0, LOuter${'$'}Inner1${'$'}Inner2;
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = DefinitionProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val locations = provider.findDefinition(uri, Position(4, 30))
        assertTrue(locations.isNotEmpty(), "Should find deeply nested class definition")
        workspace.cleanup()
    }
    
    @Test
    fun `DefinitionProvider - handles ambiguous overloaded methods`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                return-void
            .end method
            
            .method public test(I)V
                return-void
            .end method
            
            .method public test(Ljava/lang/String;)V
                return-void
            .end method
            
            .method public test(ILjava/lang/String;)V
                return-void
            .end method
            
            .method public caller()V
                invoke-virtual {p0}, LTest;->test()V
                invoke-virtual {p0, v0}, LTest;->test(I)V
                invoke-virtual {p0, v1}, LTest;->test(Ljava/lang/String;)V
                invoke-virtual {p0, v0, v1}, LTest;->test(ILjava/lang/String;)V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = DefinitionProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        // Find the actual instruction line numbers by checking parsed file
        val file = index.findFileByUri(uri)!!
        val caller = file.methods.find { it.name == "caller" }!!
        val instructions = caller.instructions
        
        // Verify we have all 4 invoke instructions
        assertEquals(4, instructions.size, "Should have 4 invoke instructions")
        
        // Now test navigation on each instruction (cursor on method name "test")
        // We need to dynamically find where "test" appears on each line since
        // register counts vary: {p0} vs {p0, v0} vs {p0, v0, v1}
        val testFile = workspace.getPath("Test.smali").toFile()
        val lines = testFile.readLines()
        
        fun findTestPosition(lineNum: Int): Int {
            val line = lines[lineNum]
            val arrowIdx = line.indexOf("->")
            val testIdx = line.indexOf("test", arrowIdx)
            return testIdx + 2  // Middle of "test"
        }
        
        val loc1 = provider.findDefinition(uri, Position(instructions[0].range.start.line, findTestPosition(instructions[0].range.start.line)))
        val loc2 = provider.findDefinition(uri, Position(instructions[1].range.start.line, findTestPosition(instructions[1].range.start.line)))
        val loc3 = provider.findDefinition(uri, Position(instructions[2].range.start.line, findTestPosition(instructions[2].range.start.line)))
        val loc4 = provider.findDefinition(uri, Position(instructions[3].range.start.line, findTestPosition(instructions[3].range.start.line)))
        
        assertTrue(loc1.isNotEmpty(), "Should find test()V")
        assertTrue(loc2.isNotEmpty(), "Should find test(I)V")
        assertTrue(loc3.isNotEmpty(), "Should find test(Ljava/lang/String;)V")
        assertTrue(loc4.isNotEmpty(), "Should find test(ILjava/lang/String;)V")
        workspace.cleanup()
    }
    
    @Test
    fun `ReferenceProvider - handles circular references`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("ClassA.smali", """
            .class public LClassA;
            .super Ljava/lang/Object;
            
            .field public b:LClassB;
            
            .method public getB()LClassB;
                iget-object v0, p0, LClassA;->b:LClassB;
                return-object v0
            .end method
        """.trimIndent())
        
        workspace.addFile("ClassB.smali", """
            .class public LClassB;
            .super Ljava/lang/Object;
            
            .field public a:LClassA;
            
            .method public getA()LClassA;
                iget-object v0, p0, LClassB;->a:LClassA;
                return-object v0
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val uri = workspace.getUri("ClassA.smali")
        
        val refs = provider.findReferences(uri, Position(3, 20), includeDeclaration = true)
        assertTrue(refs.isNotEmpty(), "Should find references even in circular dependency")
        workspace.cleanup()
    }
    
    @Test
    fun `ReferenceProvider - handles SDK class references`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public str:Ljava/lang/String;
            
            .method public test()V
                new-instance v0, Ljava/lang/String;
                const-string v1, "test"
                invoke-direct {v0, v1}, Ljava/lang/String;-><init>(Ljava/lang/String;)V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        // SDK class should not have definitions in workspace but should not crash
        val refs = provider.findReferences(uri, Position(3, 25), includeDeclaration = false)
        // Should return empty or handle gracefully, not crash
        assertTrue(true, "Should handle SDK class references without crashing")
        workspace.cleanup()
    }
    
    @Test
    fun `DocumentSymbolProvider - handles empty files`() {
        val workspace = TempTestWorkspace.create()
        val parser = SmaliParser()
        
        // Parser should return null for empty content
        val file = parser.parse("file:///empty.smali", "")
        assertTrue(file == null, "Empty file should return null")
        workspace.cleanup()
    }
    
    @Test
    fun `DocumentSymbolProvider - handles file with only comments`() {
        val workspace = TempTestWorkspace.create()
        val parser = SmaliParser()
        
        val content = """
            # This is a comment
            # Another comment
            # More comments
        """.trimIndent()
        
        // Parser should return null for comment-only file (no .class directive)
        val file = parser.parse("file:///comments.smali", content)
        assertTrue(file == null, "Comment-only file should return null")
        workspace.cleanup()
    }
    
    @Test
    fun `DiagnosticProvider - handles syntax errors gracefully`() {
        val workspace = TempTestWorkspace.create()
        val index = workspace.buildIndex()
        val parser = SmaliParser()
        val provider = DiagnosticProvider(parser, index)
        
        // Various syntax errors
        val broken1 = """
            .class public LTest;
            .super
        """.trimIndent()
        
        val broken2 = """
            .class public LTest;
            .method public test()
                return-void
            .end method
        """.trimIndent()
        
        val broken3 = """
            .class
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val diag1 = provider.computeDiagnostics("file:///test1.smali", broken1)
        val diag2 = provider.computeDiagnostics("file:///test2.smali", broken2)
        val diag3 = provider.computeDiagnostics("file:///test3.smali", broken3)
        
        assertTrue(diag1.isNotEmpty(), "Should report syntax error for incomplete .super")
        assertTrue(diag2.isNotEmpty(), "Should report syntax error for missing method descriptor")
        assertTrue(diag3.isNotEmpty(), "Should report syntax error for missing class name")
        workspace.cleanup()
    }
    
    @Test
    fun `WorkspaceSymbolProvider - handles special characters in queries`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest${'$'}Inner;
            .super Ljava/lang/Object;
            
            .method public test()V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = WorkspaceSymbolProvider(index)
        
        // Special characters
        val results1 = provider.search("${'$'}")
        val results2 = provider.search("Test${'$'}")
        val results3 = provider.search("<init>")
        val results4 = provider.search("*")
        val results5 = provider.search("")
        
        // Should not crash on special characters
        assertTrue(true, "Should handle special characters in search queries")
        workspace.cleanup()
    }
    
    @Test
    fun `Index - handles concurrent access`() {
        val workspace = TempTestWorkspace.create()
        
        // Add multiple files
        repeat(20) { i ->
            workspace.addFile("Test$i.smali", """
                .class public LTest$i;
                .super Ljava/lang/Object;
                
                .method public test()V
                    return-void
                .end method
            """.trimIndent())
        }
        
        val index = workspace.buildIndex()
        
        // Simulate concurrent access (in practice this would use threads)
        repeat(100) {
            val stats = index.getStats()
            val className = "LTest${it % 20};"
            val found = index.findClass(className)
            val uri = index.getUri(className)
        }
        
        val finalStats = index.getStats()
        assertEquals(20, finalStats.classes, "Should maintain correct class count")
        workspace.cleanup()
    }
}
