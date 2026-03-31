package xyz.surendrajat.smalilsp.integration.navigation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.eclipse.lsp4j.Position
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ACTUAL TEST FOR USER ISSUE #6: [[ array navigation broken
 */
class ArrayNavigationActualTest {
    
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
    fun `double array navigation - user reported issue`() {
        // Create the target class Lt5/d;
        val targetContent = """
            .class public Lt5/d;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val targetFile = java.io.File.createTempFile("t5_d", ".smali")
        targetFile.writeText(targetContent)
        targetFile.deleteOnExit()
        val targetUri = targetFile.toURI().toString()
        
        val targetSmaliFile = parser.parse(targetUri, targetContent)!!
        workspaceIndex.indexFile(targetSmaliFile)
        
        // Create the source file with [[Lt5/d;
        val sourceContent = """
            .class public LTestClass;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 10
                filled-new-array/range {v0 .. v9}, [[Lt5/d;
                return-void
            .end method
        """.trimIndent()
        
        val sourceFile = java.io.File.createTempFile("TestClass", ".smali")
        sourceFile.writeText(sourceContent)
        sourceFile.deleteOnExit()
        val sourceUri = sourceFile.toURI().toString()
        
        val sourceSmaliFile = parser.parse(sourceUri, sourceContent)!!
        workspaceIndex.indexFile(sourceSmaliFile)
        
        println("DEBUG: Source indexed")
        println("DEBUG: Source file has ${sourceSmaliFile.methods.size} methods")
        val method = sourceSmaliFile.methods.first()
        println("DEBUG: Method has ${method.instructions.size} instructions")
        method.instructions.forEach { insn ->
            println("DEBUG: Instruction type: ${insn::class.simpleName}, range: ${insn.range}")
        }
        println("DEBUG: Workspace has class Lt5/d;? ${workspaceIndex.hasClass("Lt5/d;")}")
        println("DEBUG: Workspace has class t5/d;? ${workspaceIndex.hasClass("t5/d;")}")
        
        // Get the actual line to see what we're clicking on
        val line5 = sourceContent.lines()[5]
        println("DEBUG: Line 5 content: '$line5'")
        println("DEBUG: Line 5 length: ${line5.length}")
        
        // Position on [[Lt5/d; in line 5
        // Line: "    filled-new-array/range {v0 .. v9}, [[Lt5/d;"
        // Find the actual position of Lt5/d;
        val classIndex = line5.indexOf("[[Lt5/d;")
        println("DEBUG: [[Lt5/d; starts at: $classIndex")
        val position = Position(5, classIndex + 3) // On the 't' in Lt5/d (after [[L)
        
        println("DEBUG: Testing navigation at position ${position.line}, ${position.character}")
        println("DEBUG: Character at position: '${line5.getOrNull(position.character)}'")
        val locations = definitionProvider.findDefinition(sourceUri, position)
        
        println("DEBUG: Found ${locations.size} locations")
        locations.forEach { println("DEBUG: Location: ${it.uri}") }
        
        // CRITICAL: This MUST work!
        assertTrue(locations.isNotEmpty(), "[[Lt5/d; MUST navigate to Lt5/d; - USER REPORTED BUG!")
        assertTrue(locations[0].uri.contains("t5_d"), "Should navigate to t5/d class")
    }
    
    @Test
    fun `single array navigation works`() {
        // Create the target class
        val targetContent = """
            .class public Lcom/example/MyClass;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val targetFile = java.io.File.createTempFile("MyClass", ".smali")
        targetFile.writeText(targetContent)
        targetFile.deleteOnExit()
        val targetUri = targetFile.toURI().toString()
        
        val targetSmaliFile = parser.parse(targetUri, targetContent)!!
        workspaceIndex.indexFile(targetSmaliFile)
        
        // Create source with single array
        val sourceContent = """
            .class public LTestClass;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 5
                filled-new-array {v0, v1, v2, v3, v4}, [Lcom/example/MyClass;
                return-void
            .end method
        """.trimIndent()
        
        val sourceFile = java.io.File.createTempFile("TestClass2", ".smali")
        sourceFile.writeText(sourceContent)
        sourceFile.deleteOnExit()
        val sourceUri = sourceFile.toURI().toString()
        
        val sourceSmaliFile = parser.parse(sourceUri, sourceContent)!!
        workspaceIndex.indexFile(sourceSmaliFile)
        
        val position = Position(5, 50)
        val locations = definitionProvider.findDefinition(sourceUri, position)
        
        assertTrue(locations.isNotEmpty(), "[Lcom/example/MyClass; should navigate")
        assertTrue(locations[0].uri.contains("MyClass"), "Should navigate to MyClass")
    }
    
    @Test
    fun `SDK single array blocked`() {
        val sourceContent = """
            .class public LTestClass;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 5
                filled-new-array {v0, v1, v2, v3, v4}, [Ljava/lang/String;
                return-void
            .end method
        """.trimIndent()
        
        val sourceFile = java.io.File.createTempFile("TestClass3", ".smali")
        sourceFile.writeText(sourceContent)
        sourceFile.deleteOnExit()
        val sourceUri = sourceFile.toURI().toString()
        
        val sourceSmaliFile = parser.parse(sourceUri, sourceContent)!!
        workspaceIndex.indexFile(sourceSmaliFile)
        
        val position = Position(5, 50)
        val locations = definitionProvider.findDefinition(sourceUri, position)
        
        assertTrue(locations.isEmpty(), "[Ljava/lang/String; should be blocked (SDK)")
    }
    
    @Test
    fun `SDK double array blocked`() {
        val sourceContent = """
            .class public LTestClass;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 5
                filled-new-array {v0, v1, v2, v3, v4}, [[Ljava/lang/Object;
                return-void
            .end method
        """.trimIndent()
        
        val sourceFile = java.io.File.createTempFile("TestClass4", ".smali")
        sourceFile.writeText(sourceContent)
        sourceFile.deleteOnExit()
        val sourceUri = sourceFile.toURI().toString()
        
        val sourceSmaliFile = parser.parse(sourceUri, sourceContent)!!
        workspaceIndex.indexFile(sourceSmaliFile)
        
        val position = Position(5, 50)
        val locations = definitionProvider.findDefinition(sourceUri, position)
        
        assertTrue(locations.isEmpty(), "[[Ljava/lang/Object; should be blocked (SDK)")
    }
}
