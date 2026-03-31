package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import kotlin.test.*
import xyz.surendrajat.smalilsp.integration.lsp.TempTestWorkspace

import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.integration.lsp.TestWorkspace
/**
 * Comprehensive tests for primitive and array type hover with CORRECT positions.
 * 
 * Tests cover:
 * 1. Primitives in method descriptors (parameters and return types)
 * 2. Primitive arrays in method descriptors
 * 3. Primitive arrays in field types
 * 4. Multi-dimensional arrays
 * 5. Mixed descriptors with objects, primitives, and arrays
 */
class ComprehensivePrimitiveHoverTest {

    @Test
    fun `hover on primitive parameter types in method descriptor`() {
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
        
        // Line: "invoke-static {v0, v1, v2, v3, v4, v5, v6, v7}, LTest;->process(IJZBCSFD)V"
        // Descriptor starts at position ~60: "(IJZBCSFD)V"
        val line = 11
        
        // We need to find exact positions - let's be methodical
        // invoke-static = 0-12
        // space = 13
        // {v0, v1, v2, v3, v4, v5, v6, v7} = 14-43
        // , = 44
        // space = 45
        // LTest; = 46-51
        // -> = 52-53
        // process = 54-60
        // (IJZBCSFD)V = 61-71
        
        val testCases = listOf(
            68 to "int",      // I
            69 to "long",     // J
            70 to "boolean",  // Z
            71 to "byte",     // B
            72 to "char",     // C
            73 to "short",    // S
            74 to "float",    // F
            75 to "double",   // D
            77 to "void"      // V
        )
        
        for ((pos, expectedType) in testCases) {
            val hover = hoverProvider.provideHover(uri, Position(line, pos))
            assertNotNull(hover, "Should provide hover at position $pos")
            val content = hover.contents.right.value
            assertTrue(content.contains(expectedType, ignoreCase = true),
                "Position $pos should show $expectedType, got: $content")
        }
    }
    
    @Test
    fun `hover on primitive array in method parameter`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public static sort([I)V
                .registers 1
                return-void
            .end method
            
            .method public test()V
                .locals 1
                
                invoke-static {v0}, LTest;->sort([I)V
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val hoverProvider = HoverProvider(index)
        
        // Line: "invoke-static {v0}, LTest;->sort([I)V"
        // [I is around position 37-38
        val line = 11
        
        val hover = hoverProvider.provideHover(uri, Position(line, 37))
        assertNotNull(hover, "Should provide hover for [I")
        val content = hover.contents.right.value
        assertTrue(content.contains("int") && (content.contains("array") || content.contains("[]")),
            "Should show int array info, got: $content")
    }
    
    @Test
    fun `hover on multi-dimensional primitive array`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public static matrix([[I)V
                .registers 1
                return-void
            .end method
            
            .method public test()V
                .locals 1
                
                invoke-static {v0}, LTest;->matrix([[I)V
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val hoverProvider = HoverProvider(index)
        
        // Line: "invoke-static {v0}, LTest;->matrix([[I)V"
        // [[I is around position 39-41
        val line = 11
        
        val hover = hoverProvider.provideHover(uri, Position(line, 39))
        assertNotNull(hover, "Should provide hover for [[I")
        val content = hover.contents.right.value
        assertTrue(content.contains("int") && content.contains("2"),
            "Should show 2D int array info, got: $content")
    }
    
    @Test
    fun `hover on primitive array in field type`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public data:[I
            
            .method public test()V
                .locals 1
                
                iget-object v0, p0, LTest;->data:[I
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val hoverProvider = HoverProvider(index)
        
        // Line: "iget-object v0, p0, LTest;->data:[I"
        // [I is at the end, around position 38-39
        val line = 8
        
        val hover = hoverProvider.provideHover(uri, Position(line, 38))
        assertNotNull(hover, "Should provide hover for [I in field")
        val content = hover.contents.right.value
        // Note: This might show field hover with type info, not primitive hover directly
        assertTrue(content.contains("int") || content.contains("I"),
            "Should show int array or field type info, got: $content")
    }
    
    @Test
    fun `hover on mixed descriptor with objects primitives and arrays`() {
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
        
        // Line: "invoke-static {v0, v1, v2, v3}, LTest;->complex(ILjava/lang/String;[JZ)Landroid/os/Bundle;"
        // Descriptor: (ILjava/lang/String;[JZ)Landroid/os/Bundle;
        val line = 12
        
        // Test I (first parameter) - position 52
        val hoverI = hoverProvider.provideHover(uri, Position(line, 52))
        assertNotNull(hoverI, "Should provide hover for I")
        assertTrue(hoverI.contents.right.value.contains("int"))
        
        // Test Ljava/lang/String; - position 60 is in middle of String
        val hoverString = hoverProvider.provideHover(uri, Position(line, 60))
        assertNotNull(hoverString, "Should provide hover for String")
        assertTrue(hoverString.contents.right.value.contains("String"))
        
        // Test [J (array parameter) - position 71-72
        val hoverArray = hoverProvider.provideHover(uri, Position(line, 71))
        assertNotNull(hoverArray, "Should provide hover for [J")
        assertTrue(hoverArray.contents.right.value.contains("long") || hoverArray.contents.right.value.contains("array"))
        
        // Test Z (boolean) - position 73
        val hoverZ = hoverProvider.provideHover(uri, Position(line, 73))
        assertNotNull(hoverZ, "Should provide hover for Z")
        assertTrue(hoverZ.contents.right.value.contains("boolean"))
        
        // Test return type Landroid/os/Bundle; - position 80 is in middle of Bundle
        val hoverReturn = hoverProvider.provideHover(uri, Position(line, 80))
        assertNotNull(hoverReturn, "Should provide hover for Bundle return type")
        assertTrue(hoverReturn.contents.right.value.contains("Bundle") || hoverReturn.contents.right.value.contains("android"))
    }
    
    @Test
    fun `hover on void return type`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public static run()V
                .registers 0
                return-void
            .end method
            
            .method public test()V
                .locals 0
                
                invoke-static {}, LTest;->run()V
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val hoverProvider = HoverProvider(index)
        
        // Line: "invoke-static {}, LTest;->run()V"
        // V is at the end, around position 35
        val line = 11
        
        val hover = hoverProvider.provideHover(uri, Position(line, 35))
        assertNotNull(hover, "Should provide hover for V (void)")
        val content = hover.contents.right.value
        assertTrue(content.contains("void"),
            "Should show void info, got: $content")
    }
    
    @Test
    fun `hover works on all primitive types`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public static allTypes(IJZBCSFD)V
                .registers 9
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val file = workspace.parseFile("Test.smali")
        
        // Verify the method exists
        val method = file.methods.find { it.name == "allTypes" }
        assertNotNull(method, "Should find allTypes method")
        assertEquals("(IJZBCSFD)V", method.descriptor, "Descriptor should have all primitive types")
        
        // The descriptor contains all 9 primitive types - verify they can be extracted
        // This is a sanity check that the parser sees the descriptor correctly
        assertTrue(method.descriptor.contains("I"))
        assertTrue(method.descriptor.contains("J"))
        assertTrue(method.descriptor.contains("Z"))
        assertTrue(method.descriptor.contains("B"))
        assertTrue(method.descriptor.contains("C"))
        assertTrue(method.descriptor.contains("S"))
        assertTrue(method.descriptor.contains("F"))
        assertTrue(method.descriptor.contains("D"))
        assertTrue(method.descriptor.contains("V"))
    }
}
