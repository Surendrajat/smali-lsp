package xyz.surendrajat.smalilsp.integration.navigation

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import java.io.File
import java.nio.file.Files

/**
 * Tests for label navigation within methods.
 * Labels are local to the method they're defined in.
 * 
 * Features tested:
 * - Go to definition: Jump from :label reference to :label definition
 * - Find references: Find all jumps to a label
 * - Hover: Show label info with reference count
 */
class LabelNavigationTest {
    
    private lateinit var index: WorkspaceIndex
    private lateinit var parser: SmaliParser
    private lateinit var tempDir: File
    private lateinit var definitionProvider: DefinitionProvider
    private lateinit var referenceProvider: ReferenceProvider
    private lateinit var hoverProvider: HoverProvider
    
    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        parser = SmaliParser()
        tempDir = Files.createTempDirectory("label-test").toFile()
        definitionProvider = DefinitionProvider(index)
        referenceProvider = ReferenceProvider(index)
        hoverProvider = HoverProvider(index)
    }
    
    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }
    
    private fun createAndIndexFile(content: String, fileName: String = "Test.smali"): String {
        val file = File(tempDir, fileName)
        file.writeText(content)
        val uri = file.toURI().toString()
        val parsed = parser.parse(uri, content)
        if (parsed != null) {
            index.indexFile(parsed)
        }
        return uri
    }
    
    @Test
    fun `goto definition from if-eqz to label definition`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()V
                .registers 2
                
                const/4 v0, 0x1
                
                if-eqz v0, :cond_0
                
                const/4 v1, 0x0
                
                :cond_0
                return-void
            .end method
        """.trimIndent()
        
        val uri = createAndIndexFile(content)
        
        // Click on :cond_0 in the if-eqz instruction (line 8)
        // Line 8: "    if-eqz v0, :cond_0"
        val ifLine = 8  // 0-indexed
        val labelRefChar = content.lines()[ifLine].indexOf(":cond_0") + 1
        
        val definitions = definitionProvider.findDefinition(uri, Position(ifLine, labelRefChar))
        
        assertFalse(definitions.isEmpty(), "Should find label definition")
        assertEquals(1, definitions.size, "Should find exactly one definition")
        
        // Label definition is on line 12: "    :cond_0"
        val defLocation = definitions[0]
        assertEquals(uri, defLocation.uri)
        assertEquals(12, defLocation.range.start.line, "Label definition should be at line 12")
    }
    
    @Test
    fun `goto definition from goto to label definition`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()V
                .registers 1
                
                :loop_start
                goto :loop_start
            .end method
        """.trimIndent()
        
        val uri = createAndIndexFile(content)
        
        // Click on :loop_start in goto (line 7)
        val gotoLine = 7  // 0-indexed
        val labelRefChar = content.lines()[gotoLine].indexOf(":loop_start") + 1
        
        val definitions = definitionProvider.findDefinition(uri, Position(gotoLine, labelRefChar))
        
        assertFalse(definitions.isEmpty(), "Should find label definition")
        assertEquals(6, definitions[0].range.start.line, "Label definition should be at line 6")
    }
    
    @Test
    fun `find references from label definition`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()I
                .registers 3
                
                const/4 v0, 0x0
                const/4 v1, 0x5
                
                :loop_start
                add-int/lit8 v0, v0, 0x1
                if-lt v0, v1, :loop_start
                goto :exit
                
                :unused_label
                const/4 v2, 0x0
                
                :exit
                return v0
            .end method
        """.trimIndent()
        
        val uri = createAndIndexFile(content)
        
        // Click on :loop_start definition (line 9)
        val loopDefLine = 9
        val loopDefChar = content.lines()[loopDefLine].indexOf(":loop_start") + 1
        
        val loopRefs = referenceProvider.findReferences(uri, Position(loopDefLine, loopDefChar), true)
        
        // Should include: definition + 1 reference from if-lt
        assertEquals(2, loopRefs.size, "loop_start should have definition + 1 reference")
        
        // Click on :exit definition (line 17)
        val exitDefLine = 17
        val exitDefChar = content.lines()[exitDefLine].indexOf(":exit") + 1
        
        val exitRefs = referenceProvider.findReferences(uri, Position(exitDefLine, exitDefChar), true)
        
        // Should include: definition + 1 reference from goto
        assertEquals(2, exitRefs.size, "exit should have definition + 1 reference")
        
        // Click on :unused_label definition (line 14)
        val unusedDefLine = 14
        val unusedDefChar = content.lines()[unusedDefLine].indexOf(":unused_label") + 1
        
        val unusedRefs = referenceProvider.findReferences(uri, Position(unusedDefLine, unusedDefChar), true)
        
        // Should only include the definition (no references)
        assertEquals(1, unusedRefs.size, "unused_label should have only its definition")
    }
    
    @Test
    fun `hover on label definition shows reference count`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()V
                .registers 2
                
                const/4 v0, 0x1
                if-eqz v0, :cond_0
                if-nez v0, :cond_0
                goto :cond_0
                
                :cond_0
                return-void
            .end method
        """.trimIndent()
        
        val uri = createAndIndexFile(content)
        
        // Hover on :cond_0 definition (line 11)
        val labelDefLine = 11
        val labelDefChar = content.lines()[labelDefLine].indexOf(":cond_0") + 1
        
        val hover = hoverProvider.provideHover(uri, Position(labelDefLine, labelDefChar))
        
        assertNotNull(hover, "Should show hover for label")
        val hoverContent = hover?.contents?.right?.value ?: ""
        assertTrue(hoverContent.contains("cond_0"), "Hover should show label name")
        assertTrue(hoverContent.contains("3"), "Hover should show reference count (3 references)")
        assertTrue(hoverContent.contains("Conditional"), "Hover should identify cond_ as conditional label")
    }
    
    @Test
    fun `hover on jump instruction shows target label info`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()V
                .registers 1
                
                goto :goto_1
                
                :goto_1
                return-void
            .end method
        """.trimIndent()
        
        val uri = createAndIndexFile(content)
        
        // Hover on :goto_1 in goto instruction (line 6)
        val gotoLine = 6
        val labelRefChar = content.lines()[gotoLine].indexOf(":goto_1") + 1
        
        val hover = hoverProvider.provideHover(uri, Position(gotoLine, labelRefChar))
        
        assertNotNull(hover, "Should show hover for label reference")
        val hoverContent = hover?.contents?.right?.value ?: ""
        assertTrue(hoverContent.contains("goto_1"), "Hover should show label name")
    }
    
    @Test
    fun `labels are scoped to their method`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public method1()V
                .registers 1
                
                :cond_0
                const/4 v0, 0x1
                if-eqz v0, :cond_0
                return-void
            .end method
            
            .method public method2()V
                .registers 1
                
                :cond_0
                const/4 v0, 0x0
                if-eqz v0, :cond_0
                return-void
            .end method
        """.trimIndent()
        
        val uri = createAndIndexFile(content)
        
        // method1's :cond_0 definition is on line 6
        val method1LabelLine = 6
        val method1LabelChar = content.lines()[method1LabelLine].indexOf(":cond_0") + 1
        
        // method2's :cond_0 reference is on line 17
        val method2RefLine = 17
        val method2RefChar = content.lines()[method2RefLine].indexOf(":cond_0") + 1
        
        // Find references from method1's :cond_0 - should only find method1's references
        val method1Refs = referenceProvider.findReferences(uri, Position(method1LabelLine, method1LabelChar), true)
        
        // Should have 2: definition + 1 reference in method1
        // Should NOT include method2's :cond_0
        assertEquals(2, method1Refs.size, "method1 cond_0 should have 2 references (def + 1 jump)")
        
        // All references should be around method1's line range (6-10)
        for (ref in method1Refs) {
            assertTrue(ref.range.start.line <= 10, "Reference should be in method1 (line <= 10)")
        }
    }
    
    @Test
    fun `multiple jump types to same label`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test(II)V
                .registers 4
                
                move p1, v0
                move p2, v1
                
                if-lt v0, v1, :exit
                if-ge v0, v1, :exit
                if-eq v0, v1, :exit
                if-ne v0, v1, :exit
                goto :exit
                
                :exit
                return-void
            .end method
        """.trimIndent()
        
        val uri = createAndIndexFile(content)
        
        // Find references to :exit
        // Count lines: 0=.class, 1=.super, 2=empty, 3=.method, 4=.registers, 5=empty,
        // 6=move, 7=move, 8=empty, 9=if-lt, 10=if-ge, 11=if-eq, 12=if-ne, 13=goto,
        // 14=empty, 15=:exit, 16=return-void, 17=.end method
        val exitDefLine = 15
        val exitDefChar = content.lines()[exitDefLine].indexOf(":exit") + 1
        
        val exitRefs = referenceProvider.findReferences(uri, Position(exitDefLine, exitDefChar), true)
        
        // Should have 6: definition + 5 jump references
        assertEquals(6, exitRefs.size, "exit should have 6 references (def + 5 jumps)")
    }
    
    @Test
    fun `label parsing handles various label patterns`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()V
                .registers 2
                
                goto :goto_0
                goto :pswitch_0
                goto :sswitch_1
                goto :array_0
                goto :try_start_0
                goto :try_end_0
                goto :catch_0
                goto :catchall_0
                
                :goto_0
                :pswitch_0
                :sswitch_1
                :array_0
                :try_start_0
                :try_end_0
                :catch_0
                :catchall_0
                return-void
            .end method
        """.trimIndent()
        
        val uri = createAndIndexFile(content)
        val file = index.findFileByUri(uri)
        
        assertNotNull(file)
        val method = file!!.methods.first()
        
        // All 8 labels should be parsed
        assertEquals(8, method.labels.size, "Should parse all 8 labels")
        assertTrue("goto_0" in method.labels, "Should parse goto_ labels")
        assertTrue("pswitch_0" in method.labels, "Should parse pswitch_ labels")
        assertTrue("sswitch_1" in method.labels, "Should parse sswitch_ labels")
        assertTrue("array_0" in method.labels, "Should parse array_ labels")
        assertTrue("try_start_0" in method.labels, "Should parse try_start_ labels")
        assertTrue("try_end_0" in method.labels, "Should parse try_end_ labels")
        assertTrue("catch_0" in method.labels, "Should parse catch_ labels")
        assertTrue("catchall_0" in method.labels, "Should parse catchall_ labels")
        
        // All 8 jump instructions should be parsed
        val jumpInstructions = method.instructions.filterIsInstance<xyz.surendrajat.smalilsp.core.JumpInstruction>()
        assertEquals(8, jumpInstructions.size, "Should parse all 8 jump instructions")
    }
}
