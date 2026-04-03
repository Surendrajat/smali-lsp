package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.shared.TempTestWorkspace
import xyz.surendrajat.smalilsp.providers.HoverProvider
import kotlin.test.*

/**
 * Regression test for primitive type hover - BROKEN AGAIN!
 * 
 * User reported: "the hover info for premitive types is broken again."
 * 
 * This test verifies that primitive types show proper hover info
 * in ALL contexts: method parameters, return types, field types.
 */
class PrimitiveHoverRegressionTest {
    
    @Test
    fun `primitive hover on method parameter type`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public testMethod(IJZ)V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = HoverProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val file = index.findFileByUri(uri)!!
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val methodLine = lines.indexOfFirst { it.contains(".method") }
        val line = lines[methodLine]
        
        // Test each primitive in the descriptor (IJZ)V
        val iPos = line.indexOf("(I")
        val jPos = line.indexOf("J")
        val zPos = line.indexOf("Z")
        
        println("Testing method descriptor: $line")
        println("I position: $iPos, J position: $jPos, Z position: $zPos")
        
        // Hover on I (int)
        val hoverI = provider.provideHover(uri, Position(methodLine, iPos + 1))
        println("Hover on I: ${hoverI?.contents?.right?.value}")
        assertNotNull(hoverI, "Hover on I should not be null")
        val contentI = hoverI.contents.right.value
        assertTrue(contentI.contains("int") || contentI.contains("Primitive"), 
                   "Should show primitive info for I, got: $contentI")
        
        // Hover on J (long)
        val hoverJ = provider.provideHover(uri, Position(methodLine, jPos))
        println("Hover on J: ${hoverJ?.contents?.right?.value}")
        assertNotNull(hoverJ, "Hover on J should not be null")
        val contentJ = hoverJ.contents.right.value
        assertTrue(contentJ.contains("long") || contentJ.contains("Primitive"),
                   "Should show primitive info for J, got: $contentJ")
        
        // Hover on Z (boolean)
        val hoverZ = provider.provideHover(uri, Position(methodLine, zPos))
        println("Hover on Z: ${hoverZ?.contents?.right?.value}")
        assertNotNull(hoverZ, "Hover on Z should not be null")
        val contentZ = hoverZ.contents.right.value
        assertTrue(contentZ.contains("boolean") || contentZ.contains("Primitive"),
                   "Should show primitive info for Z, got: $contentZ")
        
        workspace.cleanup()
    }
    
    @Test
    fun `primitive hover on field type`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field private myInt:I
            .field private myLong:J
            .field private myBoolean:Z
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = HoverProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val lines = java.io.File(java.net.URI(uri)).readLines()
        
        // Test I field
        val intFieldLine = lines.indexOfFirst { it.contains("myInt") }
        val intLine = lines[intFieldLine]
        val iPos = intLine.indexOf(":I")
        
        println("Testing field type I: $intLine at pos $iPos")
        val hoverI = provider.provideHover(uri, Position(intFieldLine, iPos + 1))
        println("Hover result: ${hoverI?.contents?.right?.value}")
        
        assertNotNull(hoverI, "Hover on I field type should not be null")
        val contentI = hoverI.contents.right.value
        assertTrue(contentI.contains("int") || contentI.contains("Primitive"),
                   "Should show primitive info for I field, got: $contentI")
        
        // Test J field
        val longFieldLine = lines.indexOfFirst { it.contains("myLong") }
        val longLine = lines[longFieldLine]
        val jPos = longLine.indexOf(":J")
        
        val hoverJ = provider.provideHover(uri, Position(longFieldLine, jPos + 1))
        assertNotNull(hoverJ, "Hover on J field type should not be null")
        val contentJ = hoverJ.contents.right.value
        assertTrue(contentJ.contains("long") || contentJ.contains("Primitive"),
                   "Should show primitive info for J field, got: $contentJ")
        
        workspace.cleanup()
    }
    
    @Test
    fun `primitive hover on instruction reference`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                invoke-static {v0, v1}, LHelper;->doSomething(IJ)V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = HoverProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val invokeLine = lines.indexOfFirst { it.contains("invoke-static") }
        val line = lines[invokeLine]
        
        val iPos = line.indexOf("(I")
        val jPos = line.indexOf("J")
        
        println("Testing invoke instruction: $line")
        
        // Hover on I in instruction
        val hoverI = provider.provideHover(uri, Position(invokeLine, iPos + 1))
        println("Hover on I in instruction: ${hoverI?.contents?.right?.value}")
        
        assertNotNull(hoverI, "Hover on I in instruction should not be null")
        val contentI = hoverI.contents.right.value
        assertTrue(contentI.contains("int") || contentI.contains("Primitive"),
                   "Should show primitive info for I in instruction, got: $contentI")
        
        workspace.cleanup()
    }
    
    @Test
    fun `primitive array hover`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field private intArray:[I
            .field private longArray:[J
            
            .method public test()V
                new-array v0, v1, [I
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = HoverProvider(index)
        val uri = workspace.getUri("Test.smali")
        
        val lines = java.io.File(java.net.URI(uri)).readLines()
        
        // Test [I in field
        val intArrayLine = lines.indexOfFirst { it.contains("intArray") }
        val fieldLine = lines[intArrayLine]
        val arrayPos = fieldLine.indexOf(":[I")
        
        println("Testing primitive array field: $fieldLine")
        val hoverField = provider.provideHover(uri, Position(intArrayLine, arrayPos + 2))
        println("Hover result: ${hoverField?.contents?.right?.value}")
        
        assertNotNull(hoverField, "Hover on [I field should not be null")
        val contentField = hoverField.contents.right.value
        assertTrue(contentField.contains("int") || contentField.contains("array") || contentField.contains("Primitive"),
                   "Should show array info for [I field, got: $contentField")
        
        // Test [I in instruction
        val newArrayLine = lines.indexOfFirst { it.contains("new-array") }
        val instrLine = lines[newArrayLine]
        val instrPos = instrLine.indexOf("[I")
        
        println("Testing primitive array instruction: $instrLine")
        val hoverInstr = provider.provideHover(uri, Position(newArrayLine, instrPos + 1))
        println("Hover result: ${hoverInstr?.contents?.right?.value}")
        
        assertNotNull(hoverInstr, "Hover on [I instruction should not be null")
        val contentInstr = hoverInstr.contents.right.value
        assertTrue(contentInstr.contains("int") || contentInstr.contains("array") || contentInstr.contains("Primitive"),
                   "Should show array info for [I instruction, got: $contentInstr")
        
        workspace.cleanup()
    }
}
