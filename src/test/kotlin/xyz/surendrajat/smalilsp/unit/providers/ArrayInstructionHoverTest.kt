package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import kotlin.test.*
import xyz.surendrajat.smalilsp.shared.TempTestWorkspace

import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.shared.TestWorkspace
/**
 * Test hover on array instructions: filled-new-array and new-array.
 * 
 * User report: "filled-new-array/range {v1 .. v107}, [[I" and "new-array v1, v0, [I"
 * don't show hover info on the array types.
 */
class ArrayInstructionHoverTest {

    @Test
    fun `hover on primitive array in filled-new-array-range instruction`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 108
                
                filled-new-array/range {v1 .. v107}, [[I
                move-result-object v0
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val hoverProvider = HoverProvider(index)
        
        println("\n=== Testing hover on [[I in filled-new-array/range ===")
        
        // Line: "filled-new-array/range {v1 .. v107}, [[I"
        val line = 6
        
        // Scan positions to find where [[I is
        val lineContent = "        filled-new-array/range {v1 .. v107}, [[I"
        println("Line content: '$lineContent'")
        println("Looking for '[[I' at position: ${lineContent.indexOf("[[I")}")
        
        for (col in 0..lineContent.length) {
            val hover = hoverProvider.provideHover(uri, Position(line, col))
            if (hover != null) {
                val content = hover.contents.right.value
                if (content.contains("int") || content.contains("array") || content.contains("[[")) {
                    println("Position $col: ${content.take(80).replace("\n", " | ")}")
                }
            }
        }
        
        // Test at positions that showed hover in scan (positions 41-43)
        val workingPos = 41
        println("\n=== Testing at working position $workingPos ===")
        
        val hover = hoverProvider.provideHover(uri, Position(line, workingPos))
        
        if (hover == null) {
            fail("❌ USER ISSUE CONFIRMED: No hover on [[I in filled-new-array/range instruction")
        } else {
            val content = hover.contents.right.value
            println("Hover content: $content")
            
            // Should show primitive array info
            assertTrue(
                content.contains("int") || content.contains("array") || content.contains("Primitive"),
                "Should show int array info for [[I, got: $content"
            )
        }
    }
    
    @Test
    fun `hover on primitive array in new-array instruction`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 2
                
                const/4 v0, 0x5
                new-array v1, v0, [I
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val hoverProvider = HoverProvider(index)
        
        println("\n=== Testing hover on [I in new-array ===")
        
        // Line: "new-array v1, v0, [I"
        val line = 7
        
        // Scan positions
        val lineContent = "        new-array v1, v0, [I"
        println("Line content: '$lineContent'")
        println("Looking for '[I' at position: ${lineContent.indexOf("[I")}")
        
        for (col in 0..lineContent.length) {
            val hover = hoverProvider.provideHover(uri, Position(line, col))
            if (hover != null) {
                val content = hover.contents.right.value
                if (content.contains("int") || content.contains("array") || content.contains("[")) {
                    println("Position $col: ${content.take(80).replace("\n", " | ")}")
                }
            }
        }
        
        // Test at positions that showed hover in scan (positions 22-23)
        val workingPos = 22
        println("\n=== Testing at working position $workingPos ===")
        
        val hover = hoverProvider.provideHover(uri, Position(line, workingPos))
        
        if (hover == null) {
            fail("❌ USER ISSUE CONFIRMED: No hover on [I in new-array instruction")
        } else {
            val content = hover.contents.right.value
            println("Hover content: $content")
            
            // Should show primitive array info
            assertTrue(
                content.contains("int") || content.contains("array") || content.contains("Primitive"),
                "Should show int array info for [I, got: $content"
            )
        }
    }
    
    @Test
    fun `hover on object array in filled-new-array instruction`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 3
                
                filled-new-array {v1, v2}, [Ljava/lang/String;
                move-result-object v0
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val hoverProvider = HoverProvider(index)
        
        println("\n=== Testing hover on [Ljava/lang/String; in filled-new-array ===")
        
        // Line: "filled-new-array {v1, v2}, [Ljava/lang/String;"
        val line = 6
        
        val lineContent = "        filled-new-array {v1, v2}, [Ljava/lang/String;"
        val arrayTypePos = lineContent.indexOf("[Ljava/lang/String;")
        
        println("Testing at position $arrayTypePos")
        
        val hover = hoverProvider.provideHover(uri, Position(line, arrayTypePos))
        
        assertNotNull(hover, "Should provide hover for [Ljava/lang/String; in filled-new-array")
        
        val content = hover.contents.right.value
        println("Hover content: $content")
        
        assertTrue(
            content.contains("String") || content.contains("array"),
            "Should show String array info, got: $content"
        )
    }
}
