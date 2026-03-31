package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.integration.lsp.TempTestWorkspace
import xyz.surendrajat.smalilsp.providers.*
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.FieldAccessInstruction
import xyz.surendrajat.smalilsp.core.TypeInstruction
import kotlin.test.*

/**
 * Comprehensive test for "Missing Definition Required" bug pattern.
 * 
 * Bug Pattern: Code requires workspace definition before proceeding,
 * causing SDK classes (not in workspace) to fail silently.
 * 
 * Tests all providers against:
 * 1. SDK classes (java.*, android.*, etc.)
 * 2. Custom classes in workspace
 * 3. Missing classes (typos, etc.)
 * 4. Edge cases (arrays, primitives, etc.)
 */
class ComprehensiveMissingDefinitionTest {
    
    @Test
    fun `hover - SDK method shows fallback info`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                const-string v0, "test"
                invoke-virtual {v0}, Ljava/lang/String;->length()I
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = HoverProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val testMethod = file.methods.find { it.name == "test" }!!
        val invokeInsn = testMethod.instructions.filterIsInstance<InvokeInstruction>().firstOrNull()
            ?: error("No invoke instruction found, only ${testMethod.instructions.size} instructions")
        
        // Hover on String.length()
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[invokeInsn.range.start.line]
        val lengthPos = line.indexOf("->length")
        require(lengthPos >= 0)
        
        val hover = provider.provideHover(uri, Position(invokeInsn.range.start.line, lengthPos + 3))
        
        // Should return hover info (not null) for SDK methods
        assertNotNull(hover, "Hover should work for SDK methods")
        val content = hover.contents.right.value
        assertTrue(content.contains("length"), "Should show method name")
        assertTrue(content.contains("()I"), "Should show method descriptor")
        
        workspace.cleanup()
    }
    
    @Test
    fun `hover - SDK field shows fallback info`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = HoverProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val testMethod = file.methods.find { it.name == "test" }!!
        val sgetInsn = testMethod.instructions[0]
        
        // Hover on System.out
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[sgetInsn.range.start.line]
        val outPos = line.indexOf("->out")
        require(outPos >= 0)
        
        val hover = provider.provideHover(uri, Position(sgetInsn.range.start.line, outPos + 3))
        
        // Should return hover info (not null) for SDK fields
        assertNotNull(hover, "Hover should work for SDK fields")
        val content = hover.contents.right.value
        assertTrue(content.contains("out"), "Should show field name")
        assertTrue(content.contains("PrintStream"), "Should show field type")
        
        workspace.cleanup()
    }
    
    @Test
    fun `hover - SDK class shows fallback info`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                new-instance v0, Ljava/lang/StringBuilder;
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = HoverProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val testMethod = file.methods.find { it.name == "test" }!!
        val newInsn = testMethod.instructions[0]
        
        // Hover on StringBuilder class
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[newInsn.range.start.line]
        val classPos = line.indexOf("StringBuilder")
        require(classPos >= 0)
        
        val hover = provider.provideHover(uri, Position(newInsn.range.start.line, classPos + 3))
        
        // Should return hover info (not null) for SDK classes
        assertNotNull(hover, "Hover should work for SDK classes")
        val content = hover.contents.right.value
        assertTrue(content.contains("StringBuilder"), "Should show class name")
        
        workspace.cleanup()
    }
    
    @Test
    fun `definition - SDK class returns empty (by design)`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                new-instance v0, Ljava/lang/String;
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = DefinitionProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val testMethod = file.methods.find { it.name == "test" }!!
        val newInsn = testMethod.instructions[0]
        
        // Click on String class
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[newInsn.range.start.line]
        val classPos = line.indexOf("String")
        require(classPos >= 0)
        
        val defs = provider.findDefinition(uri, Position(newInsn.range.start.line, classPos + 3))
        
        assertTrue(defs.isEmpty(), "SDK classes should not be navigable (by design)")
        
        workspace.cleanup()
    }
    
    @Test
    fun `references - SDK method finds all call sites`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test1()V
                const-string v0, "a"
                invoke-virtual {v0}, Ljava/lang/String;->length()I
                return-void
            .end method
            
            .method public test2()V
                const-string v0, "b"
                invoke-virtual {v0}, Ljava/lang/String;->length()I
                return-void
            .end method
            
            .method public test3()V
                const-string v0, "c"
                invoke-virtual {v0}, Ljava/lang/String;->length()I
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val test1 = file.methods.find { it.name == "test1" }!!
        val invokeInsn = test1.instructions.filterIsInstance<InvokeInstruction>().firstOrNull()
            ?: error("No invoke instruction found, only ${test1.instructions.size} instructions")
        
        // Find refs from first call
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[invokeInsn.range.start.line]
        val lengthPos = line.indexOf("->length")
        require(lengthPos >= 0)
        
        val refs = provider.findReferences(uri, Position(invokeInsn.range.start.line, lengthPos + 3), false)
        
        assertEquals(3, refs.size, "Should find all 3 call sites for SDK method")
        
        workspace.cleanup()
    }
    
    @Test
    fun `references - SDK field finds all accesses`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test1()V
                sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
                return-void
            .end method
            
            .method public test2()V
                sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val test1 = file.methods.find { it.name == "test1" }!!
        val sgetInsn = test1.instructions[0]
        
        // Find refs from first access
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[sgetInsn.range.start.line]
        val outPos = line.indexOf("->out")
        require(outPos >= 0)
        
        val refs = provider.findReferences(uri, Position(sgetInsn.range.start.line, outPos + 3), false)
        
        assertEquals(2, refs.size, "Should find both accesses to SDK field")
        
        workspace.cleanup()
    }
    
    @Test
    fun `references - missing class method returns empty (graceful degradation)`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                invoke-static {}, LNonExistent;->method()V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val testMethod = file.methods.find { it.name == "test" }!!
        val invokeInsn = testMethod.instructions[0]
        
        // Try to find refs for non-existent class
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[invokeInsn.range.start.line]
        val methodPos = line.indexOf("->method")
        require(methodPos >= 0)
        
        val refs = provider.findReferences(uri, Position(invokeInsn.range.start.line, methodPos + 3), false)
        
        // Should find the 1 call site even though class doesn't exist
        assertEquals(1, refs.size, "Should find call site even for missing class")
        
        workspace.cleanup()
    }
    
    @Test
    fun `hover - missing class shows not found info`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                new-instance v0, LNonExistent;
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = HoverProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val testMethod = file.methods.find { it.name == "test" }!!
        val newInsn = testMethod.instructions[0]
        
        // Hover on non-existent class
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[newInsn.range.start.line]
        val classPos = line.indexOf("NonExistent")
        require(classPos >= 0)
        
        val hover = provider.provideHover(uri, Position(newInsn.range.start.line, classPos + 3))
        
        // Should return hover info even for missing classes (graceful degradation)
        assertNotNull(hover, "Hover should work even for missing classes")
        val content = hover.contents.right.value
        assertTrue(content.contains("NonExistent"), "Should show class name")
        
        workspace.cleanup()
    }
}
