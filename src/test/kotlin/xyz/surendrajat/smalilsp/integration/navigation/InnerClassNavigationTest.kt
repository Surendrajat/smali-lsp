package xyz.surendrajat.smalilsp.integration.navigation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.eclipse.lsp4j.Position
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider

/**
 * Test suite for inner class navigation bug.
 * 
 * User reported via stress test:
 * - 203/1472 inner class navigation failures (86.21% success rate)
 * - Failed examples: Lj1/q1$a;, Lorg/joinmastodon/android/ui/displayitems/g$b$a;
 * 
 * Inner classes use $ in the class name (e.g., OuterClass$InnerClass)
 * These should be indexed and navigable just like regular classes.
 */
class InnerClassNavigationTest {
    
    private lateinit var parser: SmaliParser
    private lateinit var workspaceIndex: WorkspaceIndex
    private lateinit var definitionProvider: DefinitionProvider
    
    @BeforeEach
    fun setup() {
        parser = SmaliParser()
        workspaceIndex = WorkspaceIndex()
        definitionProvider = DefinitionProvider(workspaceIndex)
    }
    
    @Test
    fun `inner class should be indexed with dollar sign`() {
        val innerClassContent = """
            .class Lcom/example/Outer${"$"}Inner;
            .super Ljava/lang/Object;
            
            .method public test()V
                return-void
            .end method
        """.trimIndent()
        
        val file = parser.parse("file:///Outer${"$"}Inner.smali", innerClassContent)
        assertNotNull(file, "Inner class should parse correctly")
        
        workspaceIndex.indexFile(file!!)
        
        // Should be able to find inner class by full name with $
        val found = workspaceIndex.findClass("Lcom/example/Outer${"$"}Inner;")
        assertNotNull(found, "Inner class Lcom/example/Outer${"$"}Inner; should be findable in index")
        assertEquals("Lcom/example/Outer${"$"}Inner;", found?.classDefinition?.name)
    }
    
    @Test
    fun `navigate from subclass to inner class superclass`() {
        // First, index the inner class (superclass)
        val innerClassContent = """
            .class public Lcom/example/Outer${"$"}Inner;
            .super Ljava/lang/Object;
            
            .method public test()V
                return-void
            .end method
        """.trimIndent()
        
        val innerClass = parser.parse("file:///Outer${"$"}Inner.smali", innerClassContent)!!
        workspaceIndex.indexFile(innerClass)
        
        // Then, create a subclass that extends the inner class
        // Write to temp file so DefinitionProvider can read it
        val tempFile = java.io.File.createTempFile("test_innerclass", ".smali")
        tempFile.deleteOnExit()
        
        val subclassContent = """
            .class public Lcom/example/MyClass;
            .super Lcom/example/Outer${"$"}Inner;
            
            .method public myMethod()V
                return-void
            .end method
        """.trimIndent()
        
        tempFile.writeText(subclassContent)
        val subclassUri = tempFile.toURI().toString()
        
        val subclass = parser.parse(subclassUri, subclassContent)!!
        workspaceIndex.indexFile(subclass)
        
        // Navigate from .super line (position on inner class name)
        // Line 1 is ".super Lcom/example/Outer$Inner;"
        val positions = Position(1, 20) // Position on "Outer" in superclass name
        val locations = definitionProvider.findDefinition(subclassUri, positions)
        
        assertFalse(locations.isEmpty(), "Should find definition for inner class superclass")
        assertEquals("file:///Outer${"$"}Inner.smali", locations[0].uri, 
            "Should navigate to inner class file")
    }
    
    @Test
    fun `deeply nested inner class navigation`() {
        // Lcom/example/Outer$Inner$VeryInner;
        val deeplyNestedContent = """
            .class Lcom/example/Outer${"$"}Inner${"$"}VeryInner;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val file = parser.parse("file:///Test.smali", deeplyNestedContent)!!
        workspaceIndex.indexFile(file)
        
        // Should be findable
        val found = workspaceIndex.findClass("Lcom/example/Outer${"$"}Inner${"$"}VeryInner;")
        assertNotNull(found, "Deeply nested inner class should be findable")
    }
    
    @Test
    fun `anonymous inner class navigation`() {
        // Anonymous inner classes use numeric suffixes: Outer$1, Outer$2, etc.
        val anonInnerContent = """
            .class Lcom/example/Outer${"$"}1;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val file = parser.parse("file:///Outer${"$"}1.smali", anonInnerContent)!!
        workspaceIndex.indexFile(file)
        
        val found = workspaceIndex.findClass("Lcom/example/Outer${"$"}1;")
        assertNotNull(found, "Anonymous inner class should be findable")
    }
    
    @Test
    fun `hasClass should return true for indexed inner class`() {
        val innerClassContent = """
            .class Lcom/example/Outer${"$"}Inner;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val file = parser.parse("file:///Test.smali", innerClassContent)!!
        workspaceIndex.indexFile(file)
        
        assertTrue(workspaceIndex.hasClass("Lcom/example/Outer${"$"}Inner;"),
            "hasClass should return true for inner class")
    }
    
    @Test
    fun `getUri should work for inner class`() {
        val innerClassContent = """
            .class Lcom/example/Outer${"$"}Inner;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val file = parser.parse("file:///Outer${"$"}Inner.smali", innerClassContent)!!
        workspaceIndex.indexFile(file)
        
        val uri = workspaceIndex.getUri("Lcom/example/Outer${"$"}Inner;")
        assertNotNull(uri, "getUri should return URI for inner class")
        assertEquals("file:///Outer${"$"}Inner.smali", uri)
    }
}
