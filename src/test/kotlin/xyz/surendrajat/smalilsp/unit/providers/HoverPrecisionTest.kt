package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.MarkupContent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.HoverProvider
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test to verify hover precision - should NOT show method hover on instructions.
 */
class HoverPrecisionTest {
    
    private lateinit var parser: SmaliParser
    private lateinit var index: WorkspaceIndex
    private lateinit var hoverProvider: HoverProvider
    
    @BeforeEach
    fun setup() {
        parser = SmaliParser()
        index = WorkspaceIndex()
        hoverProvider = HoverProvider(index)
    }
    
    @Test
    fun `hover on method declaration line should show hover`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public onCreate()V
                .locals 1
                const/4 v0, 0x0
                return-void
            .end method
        """.trimIndent()
        
        val uri = "file:///test.smali"
        val file = parser.parse(uri, content)
        assertNotNull(file)
        index.indexFile(file)
        
        // Cursor on ".method public onCreate()V" line
        val hover = hoverProvider.provideHover(uri, Position(3, 15))
        assertNotNull(hover, "Should show hover on method declaration line")
    }
    
    @Test
    fun `hover on simple instruction (not in AST) should NOT show hover`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public onCreate()V
                .locals 1
                const/4 v0, 0x0
                return-void
            .end method
        """.trimIndent()
        
        val uri = "file:///test2.smali"
        val file = parser.parse(uri, content)
        assertNotNull(file)
        index.indexFile(file)
        
        // Cursor on "const/4 v0, 0x0" line (simple instruction, not in AST)
        val hover = hoverProvider.provideHover(uri, Position(5, 10))
        assertNull(hover, "Should NOT show hover on simple instruction (not in AST)")
    }
    
    @Test
    fun `hover on instruction - cursor on class name - should show class hover`() {
        // Write to actual file so HoverProvider can read line content
        val tempFile = java.io.File.createTempFile("TestInstructionHover", ".smali")
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public onCreate()V
                invoke-virtual {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        tempFile.writeText(content)
        tempFile.deleteOnExit()
        
        val uri = tempFile.toURI().toString()
        val file = parser.parse(uri, content)
        assertNotNull(file)
        index.indexFile(file)
        
        // Index Object class so hover can find it
        val objectContent = """
            .class public Ljava/lang/Object;
            .super Ljava/lang/Object;
        """.trimIndent()
        val objectFile = parser.parse("file:///java/lang/Object.smali", objectContent)!!
        index.indexFile(objectFile)
        
        // Cursor on "Ljava/lang/Object;" in invoke-virtual
        // Position: "    invoke-virtual {p0}, Ljava/lang/Object;-><init>()V"
        // 'L' is at position 25
        val hover = hoverProvider.provideHover(uri, Position(4, 26))
        assertNotNull(hover, "Should show class hover on class name in instruction")
        val content2 = if (hover.contents.isRight) hover.contents.right.value else hover.contents.left.get(0).left
        assertTrue(content2.contains("Class"), "Hover should be for class")
        assertTrue(content2.contains("Object"), "Hover should be for Object class")
    }
    
    @Test
    fun `hover on instruction - cursor on method name - should show method hover`() {
        val tempFile = java.io.File.createTempFile("TestMethodHover", ".smali")
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public onCreate()V
                invoke-virtual {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        tempFile.writeText(content)
        tempFile.deleteOnExit()
        
        val uri = tempFile.toURI().toString()
        val file = parser.parse(uri, content)
        assertNotNull(file)
        index.indexFile(file)
        
        // Index Object class with <init> method
        val objectContent = """
            .class public Ljava/lang/Object;
            .super Ljava/lang/Object;
            
            .method public <init>()V
                .locals 0
                return-void
            .end method
        """.trimIndent()
        val objectFile = parser.parse("file:///java/lang/Object.smali", objectContent)!!
        index.indexFile(objectFile)
        
        // Cursor on "<init>" in invoke-virtual
        // Position: "    invoke-virtual {p0}, Ljava/lang/Object;-><init>()V"
        // '<' is at position 45
        val hover = hoverProvider.provideHover(uri, Position(4, 46))
        assertNotNull(hover, "Should show method hover on method name in instruction")
        val content2 = if (hover.contents.isRight) hover.contents.right.value else hover.contents.left.get(0).left
        assertTrue(content2.contains("Method"), "Hover should be for method")
        assertTrue(content2.contains("<init>"), "Hover should be for <init> method")
    }
    
    @Test
    fun `hover on instruction - cursor on field name - should show field hover`() {
        val tempFile = java.io.File.createTempFile("TestFieldHover", ".smali")
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                iget-object v0, v1, LTest;->field:Ljava/lang/String;
                return-void
            .end method
            
            .field private field:Ljava/lang/String;
        """.trimIndent()
        tempFile.writeText(content)
        tempFile.deleteOnExit()
        
        val uri = tempFile.toURI().toString()
        val file = parser.parse(uri, content)
        assertNotNull(file)
        index.indexFile(file)
        
        // Cursor on "field" in iget-object
        // Position: "    iget-object v0, v1, LTest;->field:Ljava/lang/String;"
        // 'f' is at position 32
        val hover = hoverProvider.provideHover(uri, Position(4, 33))
        assertNotNull(hover, "Should show field hover on field name in instruction")
        val content2 = if (hover.contents.isRight) hover.contents.right.value else hover.contents.left.get(0).left
        assertTrue(content2.contains("Field"), "Hover should be for field")
        assertTrue(content2.contains("field"), "Hover should be for field named 'field'")
    }
    
    @Test
    fun `hover on locals directive should NOT show hover`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public onCreate()V
                .locals 1
                const/4 v0, 0x0
                return-void
            .end method
        """.trimIndent()
        
        val uri = "file:///test3.smali"
        val file = parser.parse(uri, content)
        assertNotNull(file)
        index.indexFile(file)
        
        // Cursor on ".locals 1" line
        val hover = hoverProvider.provideHover(uri, Position(4, 10))
        assertNull(hover, "Should NOT show method hover on .locals directive")
    }
}
