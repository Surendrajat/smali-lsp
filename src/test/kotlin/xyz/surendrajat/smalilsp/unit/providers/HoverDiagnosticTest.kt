package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.integration.lsp.TempTestWorkspace

import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.integration.lsp.TestWorkspace
/**
 * Simple diagnostic to see what hover returns at each position
 */
class HoverDiagnosticTest {

    @Test
    fun `find exact positions for all primitives test`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public static process(IJZBCSFD)V
                .registers 9
                return-void
            .end method
            
            .method public test()V
                .locals 8
                
                invoke-static {v0, v1, v2, v3, v4, v5, v6, v7}, LTest;->process(IJZBCSFD)V
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val hoverProvider = HoverProvider(index)
        
        val line = 11
        
        println("\n=== Finding positions for IJZBCSFD ===")
        
        for (col in 0..90) {
            val pos = Position(line, col)
            val hover = hoverProvider.provideHover(uri, pos)
            if (hover != null && hover.contents.right.value.contains("Primitive Type")) {
                val typeName = hover.contents.right.value.lines()[0].substringAfter("**Primitive Type:** `").substringBefore("`")
                println("Position $col: $typeName")
            }
        }
    }
    
    @Test
    fun `find exact positions for mixed descriptor test`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public static complex(ILjava/lang/String;[JZ)Landroid/os/Bundle;
                .registers 5
                const/4 v0, 0x0
                return-object v0
            .end method
            
            .method public test()V
                .locals 4
                
                invoke-static {v0, v1, v2, v3}, LTest;->complex(ILjava/lang/String;[JZ)Landroid/os/Bundle;
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val hoverProvider = HoverProvider(index)
        
        val line = 12
        
        println("\n=== Finding positions for mixed descriptor ===")
        
        for (col in 0..120) {
            val pos = Position(line, col)
            val hover = hoverProvider.provideHover(uri, pos)
            if (hover != null) {
                val preview = hover.contents.right.value.take(50).replace("\n", " ")
                if (preview.contains("Primitive") || preview.contains("String") || preview.contains("Bundle") || preview.contains("array")) {
                    println("Position $col: $preview")
                }
            }
        }
    }
}
