package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.integration.lsp.TempTestWorkspace
import xyz.surendrajat.smalilsp.providers.*
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.FieldAccessInstruction
import xyz.surendrajat.smalilsp.core.TypeInstruction
import kotlin.test.*
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Edge case stress tests for SDK class handling.
 * 
 * Tests complex type scenarios:
 * 1. SDK array types ([Ljava/lang/String;, [[I, etc.)
 * 2. Nested SDK classes (Ljava/util/Map$Entry;)
 * 3. Generic types in bytecode
 * 4. Primitive arrays ([I, [J, [[B, etc.)
 * 5. Anonymous classes (Lcom/example/Outer$1;)
 * 6. Mixed SDK/workspace types
 * 
 * Ensures all edge cases are handled gracefully without crashes.
 */
class EdgeCaseStressTest {
    
    @Test
    fun `references - SDK array types work correctly`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test1()V
                const/4 v0, 0x5
                new-array v0, v0, [Ljava/lang/String;
                invoke-virtual {v0}, [Ljava/lang/String;->clone()Ljava/lang/Object;
                return-void
            .end method
            
            .method public test2()V
                const/4 v0, 0x5
                new-array v0, v0, [Ljava/lang/String;
                invoke-virtual {v0}, [Ljava/lang/String;->clone()Ljava/lang/Object;
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val test1 = file.methods.find { it.name == "test1" }!!
        val invokeInsn = test1.instructions.filterIsInstance<InvokeInstruction>().firstOrNull()
            ?: error("No invoke instruction found")
        
        // Find refs for array method call
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[invokeInsn.range.start.line]
        val clonePos = line.indexOf("->clone")
        require(clonePos >= 0)
        
        val refs = provider.findReferences(uri, Position(invokeInsn.range.start.line, clonePos + 3), false)
        
        // Should find both array method calls
        assertEquals(2, refs.size, "Should find both SDK array method calls")
        
        workspace.cleanup()
    }
    
    @Test
    fun `hover - SDK nested class shows info`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                sget-object v0, Ljava/util/Locale${"$"}Category;->DISPLAY:Ljava/util/Locale${"$"}Category;
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = HoverProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val testMethod = file.methods.find { it.name == "test" }!!
        val sgetInsn = testMethod.instructions.filterIsInstance<FieldAccessInstruction>().firstOrNull()
            ?: error("No field access instruction found")
        
        // Hover on nested class field
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[sgetInsn.range.start.line]
        val displayPos = line.indexOf("->DISPLAY")
        require(displayPos >= 0)
        
        val hover = provider.provideHover(uri, Position(sgetInsn.range.start.line, displayPos + 3))
        
        // Should return hover info for nested SDK class field
        assertNotNull(hover, "Hover should work for nested SDK class fields")
        val content = hover.contents.right.value
        assertTrue(content.contains("DISPLAY"), "Should show field name")
        
        workspace.cleanup()
    }
    
    @Test
    fun `references - primitive array types work`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test1()V
                const/4 v0, 0x10
                new-array v0, v0, [I
                invoke-virtual {v0}, [I->clone()Ljava/lang/Object;
                return-void
            .end method
            
            .method public test2()V
                const/4 v0, 0x10
                new-array v0, v0, [I
                invoke-virtual {v0}, [I->clone()Ljava/lang/Object;
                return-void
            .end method
            
            .method public test3()V
                const/4 v0, 0x10
                new-array v0, v0, [I
                invoke-virtual {v0}, [I->clone()Ljava/lang/Object;
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val test1 = file.methods.find { it.name == "test1" }!!
        val invokeInsn = test1.instructions.filterIsInstance<InvokeInstruction>().firstOrNull()
            ?: error("No invoke instruction found")
        
        // Find refs for primitive array method
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[invokeInsn.range.start.line]
        val clonePos = line.indexOf("->clone")
        require(clonePos >= 0)
        
        val refs = provider.findReferences(uri, Position(invokeInsn.range.start.line, clonePos + 3), false)
        
        // Should find all 3 primitive array method calls
        assertEquals(3, refs.size, "Should find all primitive array method calls")
        
        workspace.cleanup()
    }
    
    @Test
    fun `hover - multi-dimensional array types work`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                const/4 v0, 0x5
                new-array v0, v0, [[Ljava/lang/Object;
                invoke-virtual {v0}, [[Ljava/lang/Object;->clone()Ljava/lang/Object;
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = HoverProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val testMethod = file.methods.find { it.name == "test" }!!
        val invokeInsn = testMethod.instructions.filterIsInstance<InvokeInstruction>().firstOrNull()
            ?: error("No invoke instruction found")
        
        // Hover on multi-dimensional array method
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[invokeInsn.range.start.line]
        val clonePos = line.indexOf("->clone")
        require(clonePos >= 0)
        
        val hover = provider.provideHover(uri, Position(invokeInsn.range.start.line, clonePos + 3))
        
        // Should return hover info even for complex array types
        assertNotNull(hover, "Hover should work for multi-dimensional arrays")
        val content = hover.contents.right.value
        assertTrue(content.contains("clone"), "Should show method name")
        
        workspace.cleanup()
    }
    
    @Test
    @org.junit.jupiter.api.Disabled("Anonymous class constructor parsing needs investigation")
    fun `references - anonymous class inner method calls work`() {
        val workspace = TempTestWorkspace.create()
        // Simulate anonymous class pattern
        workspace.addFile("Outer.smali", """
            .class public LOuter;
            .super Ljava/lang/Object;
            
            .method public createAnonymous()Ljava/lang/Runnable;
                new-instance v0, LOuter\$1;
                invoke-direct {v0}, LOuter\$1;-><init>()V
                return-object v0
            .end method
        """.trimIndent())
        
        workspace.addFile("Outer\$1.smali", """
            .class LOuter\$1;
            .super Ljava/lang/Object;
            .implements Ljava/lang/Runnable;
            
            .method public run()V
                return-void
            .end method
        """.trimIndent())
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                new-instance v0, LOuter\$1;
                invoke-direct {v0}, LOuter\$1;-><init>()V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val outerUri = workspace.getUri("Outer.smali")
        
        val outerFile = index.findFileByUri(outerUri)!!
        val createMethod = outerFile.methods.find { it.name == "createAnonymous" }!!
        val invokeInsn = createMethod.instructions.filterIsInstance<InvokeInstruction>().firstOrNull()
            ?: error("No invoke instruction found")
        
        // Find refs for anonymous class constructor
        val lines = java.io.File(java.net.URI(outerUri)).readLines()
        val line = lines[invokeInsn.range.start.line]
        val initPos = line.indexOf("-><init>")
        require(initPos >= 0)
        
        val refs = provider.findReferences(outerUri, Position(invokeInsn.range.start.line, initPos + 3), true)
        
        // Should find definition + both instantiation sites
        assertTrue(refs.size >= 2, "Should find multiple references to anonymous class constructor (found ${refs.size})")
        
        workspace.cleanup()
    }
    
    @Test
    fun `hover - SDK class with dollar sign (nested) works`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                new-instance v0, Ljava/lang/Thread${"$"}State;
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = HoverProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val testMethod = file.methods.find { it.name == "test" }!!
        val newInsn = testMethod.instructions.filterIsInstance<TypeInstruction>().firstOrNull()
            ?: error("No type instruction found")
        
        // Hover on nested SDK class
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[newInsn.range.start.line]
        val statePos = line.indexOf("Thread\$State")
        require(statePos >= 0)
        
        val hover = provider.provideHover(uri, Position(newInsn.range.start.line, statePos + 8))
        
        // Should return hover info for nested SDK class
        assertNotNull(hover, "Hover should work for nested SDK classes with $ in name")
        val content = hover.contents.right.value
        assertTrue(content.contains("Thread") || content.contains("State"), "Should show class name")
        
        workspace.cleanup()
    }
    
    @Test
    fun `references - mixed array and non-array types work`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("MyClass.smali", """
            .class public LMyClass;
            .super Ljava/lang/Object;
            
            .method public process([Ljava/lang/String;)V
                return-void
            .end method
            
            .method public processSingle(Ljava/lang/String;)V
                return-void
            .end method
        """.trimIndent())
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test1()V
                const/4 v0, 0x0
                new-instance v1, LMyClass;
                invoke-virtual {v1, v0}, LMyClass;->process([Ljava/lang/String;)V
                return-void
            .end method
            
            .method public test2()V
                const/4 v0, 0x0
                new-instance v1, LMyClass;
                invoke-virtual {v1, v0}, LMyClass;->process([Ljava/lang/String;)V
                return-void
            .end method
            
            .method public test3()V
                const/4 v0, 0x0
                new-instance v1, LMyClass;
                invoke-virtual {v1, v0}, LMyClass;->processSingle(Ljava/lang/String;)V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val test1 = file.methods.find { it.name == "test1" }!!
        val invokeInsn = test1.instructions.filterIsInstance<InvokeInstruction>().firstOrNull()
            ?: error("No invoke instruction found")
        
        // Find refs for array parameter method
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[invokeInsn.range.start.line]
        val processPos = line.indexOf("->process")
        require(processPos >= 0)
        
        val refs = provider.findReferences(uri, Position(invokeInsn.range.start.line, processPos + 3), true)
        
        // Should find definition + 2 calls (not the processSingle method)
        assertEquals(3, refs.size, "Should find array method only, not single-param variant")
        
        workspace.cleanup()
    }
    
    @Test
    fun `no crash on malformed SDK class references`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                invoke-virtual {v0}, Ljava/lang/Nonexistent${"$"}Weird${"$"}Nested${"$"}Class;->method()V
                sget-object v1, LBroken${"$"}${"$"}${"$"}${"$"}Multiple${"$"}${"$"}Dollars;->field:I
                new-instance v2, [[[[[LTooManyArrayDims;
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val hoverProvider = HoverProvider(index)
        val refProvider = ReferenceProvider(index)
        val defProvider = DefinitionProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val testMethod = file.methods.find { it.name == "test" }!!
        
        // Try hover on each malformed instruction - should not crash
        testMethod.instructions.forEach { insn ->
            assertNotNull(insn, "Instruction should be parsed even if class is malformed")
            
            val midLinePos = Position(insn.range.start.line, (insn.range.start.character + insn.range.end.character) / 2)
            
            // These should all return gracefully (null or empty), never throw
            assertDoesNotThrow {
                hoverProvider.provideHover(uri, midLinePos)
            }
            
            assertDoesNotThrow {
                refProvider.findReferences(uri, midLinePos, false)
            }
            
            assertDoesNotThrow {
                defProvider.findDefinition(uri, midLinePos)
            }
        }
        
        workspace.cleanup()
    }
}
