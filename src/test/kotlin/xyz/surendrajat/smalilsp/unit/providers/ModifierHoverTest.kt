package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import kotlin.test.assertNotNull
import kotlin.test.assertNull

import xyz.surendrajat.smalilsp.providers.HoverProvider
/**
 * Tests for Issue #4: Method/Field modifier hover fix.
 * Verifies that hover only shows on method/field NAME, not on modifiers.
 */
class ModifierHoverTest {
    
    private val parser = SmaliParser()
    private val index = WorkspaceIndex()
    private val hoverProvider = HoverProvider(index)
    private val tempFiles = mutableListOf<java.io.File>()
    
    /**
     * Helper to create temp file with content and parse it.
     * Returns the URI.
     */
    private fun parseContent(content: String): String {
        val tempFile = kotlin.io.path.createTempFile("test", ".smali").toFile()
        tempFile.writeText(content)
        tempFiles.add(tempFile)
        
        val uri = tempFile.toURI().toString()
        val file = parser.parse(uri, content)
        assertNotNull(file)
        index.indexFile(file)
        
        return uri
    }
    
    @org.junit.jupiter.api.AfterEach
    fun cleanup() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }
    
    @Test
    fun `hover on method name shows info`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                return-void
            .end method
        """.trimIndent()
        
        val uri = parseContent(content)
        
        // Hover on "test" (method name) - character position on 's' in 'test'
        // Line: ".method public test()V"
        //                       ^      <- position 16 (on 's' in 'test')
        val hoverOnName = hoverProvider.provideHover(uri, Position(3, 16))
        assertNotNull(hoverOnName, "Should show hover on method name 'test'")
        
        println("✅ Hover on method name works")
    }
    
    @Test
    fun `hover on method keyword returns nothing`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                return-void
            .end method
        """.trimIndent()
        
        val uri = parseContent(content)
        
        // Hover on ".method" - character position on 'm' in '.method'
        // Line: ".method public test()V"
        //        ^       <- position 2 (on 'm' in '.method')
        val hoverOnKeyword = hoverProvider.provideHover(uri, Position(3, 2))
        assertNull(hoverOnKeyword, "Should NOT show hover on '.method' keyword")
        
        println("✅ Hover on .method keyword returns nothing")
    }
    
    @Test
    fun `hover on method modifier returns nothing`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                return-void
            .end method
        """.trimIndent()
        
        val uri = parseContent(content)
        
        // Hover on "public" - character position on 'b' in 'public'
        // Line: ".method public test()V"
        //                ^      <- position 10 (on 'b' in 'public')
        val hoverOnModifier = hoverProvider.provideHover(uri, Position(3, 10))
        assertNull(hoverOnModifier, "Should NOT show hover on 'public' modifier")
        
        println("✅ Hover on public modifier returns nothing")
    }
    
    @Test
    fun `hover on field name shows info`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public myField:Ljava/lang/String;
        """.trimIndent()
        
        val uri = parseContent(content)
        
        // Hover on "myField" (field name) - character position on 'F' in 'myField'
        // Line: ".field public myField:Ljava/lang/String;"
        //                       ^      <- position 19 (on 'F' in 'myField')
        val hoverOnName = hoverProvider.provideHover(uri, Position(3, 19))
        assertNotNull(hoverOnName, "Should show hover on field name 'myField'")
        
        println("✅ Hover on field name works")
    }
    
    @Test
    fun `hover on field keyword returns nothing`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public myField:Ljava/lang/String;
        """.trimIndent()
        
        val uri = parseContent(content)
        
        // Hover on ".field" - character position on 'f' in '.field'
        // Line: ".field public myField:Ljava/lang/String;"
        //        ^     <- position 2 (on 'f' in '.field')
        val hoverOnKeyword = hoverProvider.provideHover(uri, Position(3, 2))
        assertNull(hoverOnKeyword, "Should NOT show hover on '.field' keyword")
        
        println("✅ Hover on .field keyword returns nothing")
    }
    
    @Test
    fun `hover on field modifier returns nothing`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public myField:Ljava/lang/String;
        """.trimIndent()
        
        val uri = parseContent(content)
        
        // Hover on "public" - character position on 'b' in 'public'
        // Line: ".field public myField:Ljava/lang/String;"
        //               ^      <- position 9 (on 'b' in 'public')
        val hoverOnModifier = hoverProvider.provideHover(uri, Position(3, 9))
        assertNull(hoverOnModifier, "Should NOT show hover on 'public' modifier")
        
        println("✅ Hover on field modifier returns nothing")
    }
    
    @Test
    fun `hover on private method name works`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method private static myPrivateMethod(Ljava/lang/String;)I
                return-void
            .end method
        """.trimIndent()
        
        val uri = parseContent(content)
        
        // Hover on "myPrivateMethod" - character position on 'P' in 'myPrivateMethod'
        // Line: ".method private static myPrivateMethod(Ljava/lang/String;)I"
        //                                ^              <- position 28 (on 'P')
        val hoverOnName = hoverProvider.provideHover(uri, Position(3, 28))
        assertNotNull(hoverOnName, "Should show hover on method name")
        
        println("✅ Hover on private static method name works")
    }
    
    @Test
    fun `hover on constructor name works`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public constructor <init>()V
                return-void
            .end method
        """.trimIndent()
        
        val uri = parseContent(content)
        
        // Hover on "<init>" (constructor name) - character position on 'i' in '<init>'
        // Line: ".method public constructor <init>()V"
        //                                 ^     <- position 32 (on 'i' in '<init>')
        val hoverOnName = hoverProvider.provideHover(uri, Position(3, 32))
        assertNotNull(hoverOnName, "Should show hover on constructor name")
        
        println("✅ Hover on constructor name works")
    }
}
