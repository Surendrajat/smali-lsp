package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import kotlin.test.*
import xyz.surendrajat.smalilsp.integration.lsp.TempTestWorkspace

import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.integration.lsp.TestWorkspace
/**
 * Test the 2 remaining user-reported issues:
 * 1. Hover on [ and [[ object arrays shows "not found" instead of array info
 * 2. Position-based find references on declarations doesn't work
 */
class RemainingIssuesTest {

    @Test
    fun `issue 1 - hover on object array shows array info not not found`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public items:[Ljava/lang/String;
            
            .method public test()V
                .locals 1
                
                iget-object v0, p0, LTest;->items:[Ljava/lang/String;
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val hoverProvider = HoverProvider(index)
        
        println("\n=== Testing object array hover ===")
        
        // Line: "iget-object v0, p0, LTest;->items:[Ljava/lang/String;"
        // Find positions for the array type
        val line = 8
        
        for (col in 0..60) {
            val hover = hoverProvider.provideHover(uri, Position(line, col))
            if (hover != null) {
                val content = hover.contents.right.value
                if (content.contains("[") || content.contains("array") || content.contains("String")) {
                    println("Position $col: ${content.take(80).replace("\n", " ")}")
                }
            }
        }
        
        // Now test at specific position on the array type
        val pos = Position(line, 43) // On '[' of [Ljava/lang/String;
        val hover = hoverProvider.provideHover(uri, pos)
        
        println("\n=== Result at position 43 ===")
        if (hover == null) {
            println("NULL - No hover found")
            fail("Should provide hover for [Ljava/lang/String; array type")
        } else {
            val content = hover.contents.right.value
            println("Content: $content")
            
            // Check if it says "not found"
            if (content.contains("not found", ignoreCase = true) || content.contains("Not found")) {
                fail("Hover shows 'not found' for array type: $content")
            }
            
            // Should show array or String info
            assertTrue(content.contains("String") || content.contains("array") || content.contains("[]"),
                "Should show array or String info, got: $content")
        }
    }
    
    @Test
    fun `issue 1 - hover on 2D object array`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public matrix:[[Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                
                iget-object v0, p0, LTest;->matrix:[[Ljava/lang/Object;
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val hoverProvider = HoverProvider(index)
        
        println("\n=== Testing 2D object array hover ===")
        
        val line = 8
        val pos = Position(line, 44) // On '[[' of [[Ljava/lang/Object;
        val hover = hoverProvider.provideHover(uri, pos)
        
        if (hover == null) {
            println("NULL - No hover found")
            fail("Should provide hover for [[Ljava/lang/Object; array type")
        } else {
            val content = hover.contents.right.value
            println("Content: $content")
            
            if (content.contains("not found", ignoreCase = true)) {
                fail("Hover shows 'not found' for 2D array: $content")
            }
            
            assertTrue(content.contains("Object") || content.contains("array") || content.contains("[][]"),
                "Should show 2D array or Object info, got: $content")
        }
    }
}
