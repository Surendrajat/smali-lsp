package xyz.surendrajat.smalilsp.integration.navigation

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for GoTo Definition precision - should only navigate from references, not from names.
 */
class GotoDefinitionPrecisionTest {
    
    private lateinit var parser: SmaliParser
    private lateinit var index: WorkspaceIndex
    private lateinit var definitionProvider: DefinitionProvider
    
    @BeforeEach
    fun setup() {
        parser = SmaliParser()
        index = WorkspaceIndex()
        definitionProvider = DefinitionProvider(index)
    }
    
    @Test
    fun `GoTo Definition on field DECLARATION name should NOT navigate`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field private static final TAG:Ljava/lang/String; = "ArrayMap"
        """.trimIndent()
        
        // Write to actual file so position-based navigation can read it
        val tempFile = java.io.File.createTempFile("Test", ".smali")
        tempFile.writeText(content)
        tempFile.deleteOnExit()
        
        val uri = tempFile.toURI().toString()
        val file = parser.parse(uri, content)!!
        index.indexFile(file)
        
        // Cursor on "TAG" in the field declaration - should NOT navigate
        // (TAG is the field name, not a reference)
        // Position 29 is on 'A' in "TAG"
        val locations = definitionProvider.findDefinition(uri, Position(3, 29))
        
        println("GoTo on field name 'TAG': ${locations.size} locations")
        
        // Should return empty - field names themselves don't navigate
        assertEquals(0, locations.size, "Field declaration name should not navigate")
    }
    
    @Test
    fun `GoTo Definition on field TYPE should navigate to String class`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field private static final TAG:Lcom/example/MyString; = "ArrayMap"
        """.trimIndent()
        
        // Write to actual file
        val tempFile = java.io.File.createTempFile("Test", ".smali")
        tempFile.writeText(content)
        tempFile.deleteOnExit()
        
        val uri = tempFile.toURI().toString()
        val file = parser.parse(uri, content)!!
        index.indexFile(file)
        
        // Create MyString class to navigate to
        val stringContent = """
            .class public final Lcom/example/MyString;
            .super Ljava/lang/Object;
        """.trimIndent()
        val stringFile = parser.parse("file:///MyString.smali", stringContent)!!
        index.indexFile(stringFile)
        
        // Cursor on "Lcom/example/MyString;" in the field type (position 40 is on 'p' in 'example')
        val locations = definitionProvider.findDefinition(uri, Position(3, 40))
        
        println("GoTo on field type 'Lcom/example/MyString;': ${locations.size} locations")
        
        // Should navigate to MyString class
        assertTrue(locations.isNotEmpty(), "Field type should navigate to class")
        assertTrue(locations[0].uri.contains("MyString"), "Should navigate to MyString class")
    }
    
    @Test
    fun `GoTo Definition on method NAME should NOT navigate`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public onCreate(Landroid/os/Bundle;)V
                .locals 0
                return-void
            .end method
        """.trimIndent()
        
        // Write to actual file so position-based navigation can read it
        val tempFile = java.io.File.createTempFile("Test", ".smali")
        tempFile.writeText(content)
        tempFile.deleteOnExit()
        
        val uri = tempFile.toURI().toString()
        val file = parser.parse(uri, content)!!
        index.indexFile(file)
        
        // Cursor on "onCreate" method name - should NOT navigate
        val locations = definitionProvider.findDefinition(uri, Position(3, 19))
        
        println("GoTo on method name 'onCreate': ${locations.size} locations")
        
        // Method names themselves don't navigate
        assertEquals(0, locations.size, "Method name should not navigate")
    }
    
    @Test
    fun `GoTo Definition on method PARAMETER type should navigate`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public onCreate(Lcom/example/Data;)V
                .locals 0
                return-void
            .end method
        """.trimIndent()
        
        // Write to actual file
        val tempFile = java.io.File.createTempFile("Test", ".smali")
        tempFile.writeText(content)
        tempFile.deleteOnExit()
        
        val uri = tempFile.toURI().toString()
        val file = parser.parse(uri, content)!!
        index.indexFile(file)
        
        // Create Data class to navigate to
        val dataContent = """
            .class public Lcom/example/Data;
            .super Ljava/lang/Object;
        """.trimIndent()
        val dataFile = parser.parse("file:///Data.smali", dataContent)!!
        index.indexFile(dataFile)
        
        // Cursor on "Lcom/example/Data;" in parameter (position 28 is on 'e' in 'example')
        val locations = definitionProvider.findDefinition(uri, Position(3, 28))
        
        println("GoTo on parameter type 'Lcom/example/Data;': ${locations.size} locations")
        
        // Should navigate to Data class
        assertTrue(locations.isNotEmpty(), "Parameter type should navigate")
        assertTrue(locations[0].uri.contains("Data"), "Should navigate to Data class")
    }
    
    @Test
    fun `GoTo Definition on invoke-virtual METHOD should navigate to definition`() {
        val contentA = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public myMethod()V
                .locals 0
                return-void
            .end method
        """.trimIndent()
        
        val contentB = """
            .class public LCaller;
            .super Ljava/lang/Object;
            
            .method public callMethod()V
                .locals 1
                invoke-virtual {v0}, LTest;->myMethod()V
                return-void
            .end method
        """.trimIndent()
        
        val uriA = "file:///Test.smali"
        val uriB = "file:///Caller.smali"
        val fileA = parser.parse(uriA, contentA)!!
        val fileB = parser.parse(uriB, contentB)!!
        index.indexFile(fileA)
        index.indexFile(fileB)
        
        // Cursor on "myMethod" in invoke-virtual
        val locations = definitionProvider.findDefinition(uriB, Position(5, 36))
        
        println("GoTo on invoke method name 'myMethod': ${locations.size} locations")
        
        // Should navigate to method definition
        assertTrue(locations.isNotEmpty(), "Method reference should navigate")
        assertEquals(uriA, locations[0].uri, "Should navigate to Test class")
    }
}
