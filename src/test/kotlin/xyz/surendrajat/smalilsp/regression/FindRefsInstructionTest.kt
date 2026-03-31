package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.integration.lsp.TempTestWorkspace
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import kotlin.test.assertTrue

/**
 * Test find references on method/field calls in instructions
 */
class FindRefsInstructionTest {
    
    @Test
    fun `find references - method called in instructions`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public target()V
                return-void
            .end method
            
            .method public caller1()V
                invoke-virtual {p0}, LTest;->target()V
                return-void
            .end method
            
            .method public caller2()V
                invoke-virtual {p0}, LTest;->target()V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        // Find references from the method definition
        val file = index.findFileByUri(uri)!!
        val targetMethod = file.methods.find { it.name == "target" }!!
        
        // Click on the method name in the definition
        val clickChar = (targetMethod.range.start.character + targetMethod.range.end.character) / 2
        val refs = provider.findReferences(uri, Position(targetMethod.range.start.line, clickChar), true)
        
        println("=== Find References on Method Definition ===")
        println("Found ${refs.size} references:")
        refs.forEach { 
            println("  Line ${it.range.start.line}: ${it.uri.substringAfterLast("/")}")
        }
        
        // Should find: definition + 2 invoke instructions
        assertTrue(refs.size >= 3, "Expected at least 3 (def + 2 calls), found ${refs.size}")
        workspace.cleanup()
    }
    
    @Test
    fun `find references - field accessed in instructions`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Data.smali", """
            .class public LData;
            .super Ljava/lang/Object;
            
            .field public counter:I
            
            .method public inc()V
                iget v0, p0, LData;->counter:I
                add-int/lit8 v0, v0, 1
                iput v0, p0, LData;->counter:I
                return-void
            .end method
            
            .method public dec()V
                iget v0, p0, LData;->counter:I
                add-int/lit8 v0, v0, -1
                iput v0, p0, LData;->counter:I
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val uri = workspace.getUri("Data.smali")
        
        // Find references from field definition
        val file = index.findFileByUri(uri)!!
        val counterField = file.fields.find { it.name == "counter" }!!
        
        // Click on the field name in the definition
        val clickChar = (counterField.range.start.character + counterField.range.end.character) / 2
        val refs = provider.findReferences(uri, Position(counterField.range.start.line, clickChar), true)
        
        println("=== Find References on Field Definition ===")
        println("Found ${refs.size} references:")
        refs.forEach { 
            println("  Line ${it.range.start.line}: ${it.uri.substringAfterLast("/")}")
        }
        
        // Should find: definition + 4 field accesses (2 iget + 2 iput)
        assertTrue(refs.size >= 5, "Expected at least 5 (def + 4 accesses), found ${refs.size}")
        workspace.cleanup()
    }
    
    @Test
    fun `find references - clicking on invoke instruction`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public target()V
                return-void
            .end method
            
            .method public caller()V
                invoke-virtual {p0}, LTest;->target()V
                invoke-virtual {p0}, LTest;->target()V
                invoke-virtual {p0}, LTest;->target()V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        // Click on an invoke instruction
        val file = index.findFileByUri(uri)!!
        val caller = file.methods.find { it.name == "caller" }!!
        val firstInvoke = caller.instructions[0]
        
        // Read the actual line to find where the method name is
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[firstInvoke.range.start.line]
        val methodNamePos = line.indexOf("->target")
        require(methodNamePos >= 0) { "Method name not found in line: $line" }
        
        // Click on the method name in the invoke instruction
        val clickChar = methodNamePos + 3  // Click on 'target'
        val refs = provider.findReferences(uri, Position(firstInvoke.range.start.line, clickChar), true)
        
        println("=== Find References from Invoke Instruction ===")
        println("Found ${refs.size} references:")
        refs.forEach { 
            println("  Line ${it.range.start.line}: ${it.uri.substringAfterLast("/")}")
        }
        
        // Should find: definition + 3 invoke instructions
        assertTrue(refs.size >= 4, "Expected at least 4 (def + 3 calls), found ${refs.size}")
        workspace.cleanup()
    }
    
    @Test
    fun `find references - clicking on field access instruction`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Data.smali", """
            .class public LData;
            .super Ljava/lang/Object;
            
            .field public value:I
            
            .method public use()V
                iget v0, p0, LData;->value:I
                iget v1, p0, LData;->value:I
                iget v2, p0, LData;->value:I
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val uri = workspace.getUri("Data.smali")
        
        // Click on a field access instruction
        val file = index.findFileByUri(uri)!!
        val useMethod = file.methods.find { it.name == "use" }!!
        val firstIget = useMethod.instructions[0]
        
        // Read the actual line to find where the field name is
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[firstIget.range.start.line]
        val fieldNamePos = line.indexOf("->value")
        require(fieldNamePos >= 0) { "Field name not found in line: $line" }
        
        // Click on the field name in the iget instruction
        val clickChar = fieldNamePos + 3  // Click on 'value'
        val refs = provider.findReferences(uri, Position(firstIget.range.start.line, clickChar), true)
        
        println("=== Find References from Field Access Instruction ===")
        println("Found ${refs.size} references:")
        refs.forEach { 
            println("  Line ${it.range.start.line}: ${it.uri.substringAfterLast("/")}")
        }
        
        // Should find: definition + 3 iget instructions
        assertTrue(refs.size >= 4, "Expected at least 4 (def + 3 accesses), found ${refs.size}")
        workspace.cleanup()
    }
}
