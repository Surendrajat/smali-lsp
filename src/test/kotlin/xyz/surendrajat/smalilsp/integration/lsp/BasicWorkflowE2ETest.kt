package xyz.surendrajat.smalilsp.integration.lsp

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName

import xyz.surendrajat.smalilsp.providers.DocumentSymbolProvider
import xyz.surendrajat.smalilsp.shared.TestWorkspace
import xyz.surendrajat.smalilsp.shared.withE2ETest
/**
 * E2E tests for basic LSP workflows
 */
@DisplayName("Basic Workflow E2E Tests")
class BasicWorkflowE2ETest {
    
    @Test
    @DisplayName("User opens file and navigates to method definition")
    fun `user opens file and navigates to method definition`() {
        // Create workspace with simple file
        val workspace = TestWorkspace.createTemp("""
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public foo()V
                .registers 1
                invoke-virtual {p0}, LTest;->bar()V
                return-void
            .end method
            
            .method public bar()V
                .registers 1
                return-void
            .end method
        """.trimIndent())
        
        withE2ETest(workspace) {
            // User opens file
            val uri = openFile("Test.smali")
            
            // User clicks on "bar" in method invocation "LTest;->bar()V"
            val pos = workspace.findPosition("Test.smali", "->bar()")
            
            // Move position to the "b" in "bar"
            val clickPos = Position(pos.line, pos.character + 2)
            
            // Perform goto definition
            val definitions = gotoDefinition(uri, clickPos)
            
            // Should find definition
            assertEquals(1, definitions.size, "Should find exactly one definition")
            
            // Verify it points to the bar method definition
            val defLine = definitions[0].range.start.line
            val defText = workspace.getLine("Test.smali", defLine)
            assertTrue(defText.contains(".method public bar()V"), 
                "Definition should point to bar method, but got: $defText")
        }
    }
    
    @Test
    @DisplayName("User navigates to field definition")
    fun `user navigates to field definition`() {
        val workspace = TestWorkspace.createTemp("""
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field private mValue:I
            
            .method public getValue()I
                .registers 2
                iget v0, p0, LTest;->mValue:I
                return v0
            .end method
            
            .method public setValue(I)V
                .registers 2
                iput p1, p0, LTest;->mValue:I
                return-void
            .end method
        """.trimIndent())
        
        withE2ETest(workspace) {
            val uri = openFile("Test.smali")
            
            // Click on "mValue" in iget instruction
            val pos = workspace.findPosition("Test.smali", "->mValue:")
            val clickPos = Position(pos.line, pos.character + 3)  // Click on "m"
            
            val definitions = gotoDefinition(uri, clickPos)
            
            assertEquals(1, definitions.size)
            
            val defLine = definitions[0].range.start.line
            val defText = workspace.getLine("Test.smali", defLine)
            assertTrue(defText.contains(".field private mValue:I"))
        }
    }
    
    @Test
    @DisplayName("User finds all references to a method")
    fun `user finds all references to a method`() {
        val workspace = TestWorkspace.createTemp("""
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public bar()V
                .registers 1
                return-void
            .end method
            
            .method public foo1()V
                .registers 1
                invoke-virtual {p0}, LTest;->bar()V
                return-void
            .end method
            
            .method public foo2()V
                .registers 1
                invoke-virtual {p0}, LTest;->bar()V
                return-void
            .end method
        """.trimIndent())
        
        withE2ETest(workspace) {
            val uri = openFile("Test.smali")
            
            // Find position of bar method definition
            val pos = workspace.findPosition("Test.smali", ".method public bar()")
            val methodPos = Position(pos.line, pos.character + 15)  // On "bar"
            
            // Find references
            val references = findReferences(uri, methodPos, includeDeclaration = true)
            
            val refLines = references.map { it.range.start.line }.sorted()
            assertEquals(listOf(3, 10, 16), refLines, "Should find exactly the definition and 2 call sites")
            println("Found references at lines: $refLines")
        }
    }
    
    @Test
    @DisplayName("User hovers over method to see signature")
    fun `user hovers over method to see signature`() {
        val workspace = TestWorkspace.createTemp("""
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public getValue()I
                .registers 2
                iget v0, p0, LTest;->mValue:I
                return v0
            .end method
        """.trimIndent())
        
        withE2ETest(workspace) {
            val uri = openFile("Test.smali")
            
            // Hover over "getValue" in method definition
            val pos = workspace.findPosition("Test.smali", "getValue")
            
            val hover = hover(uri, pos)
            
            assertNotNull(hover, "Hover should return result")
            
            val contents = hover!!.contents.right.value
            println("Hover contents: $contents")
            
            // Should contain method signature
            assertTrue(contents.contains("getValue") || contents.contains("method"),
                "Hover should contain method information")
        }
    }
    
    @Test
    @DisplayName("User gets document symbols for file outline")
    fun `user gets document symbols for file outline`() {
        val workspace = TestWorkspace.createTemp("""
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field private mValue:I
            .field public mName:Ljava/lang/String;
            
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            
            .method public getValue()I
                .registers 2
                iget v0, p0, LTest;->mValue:I
                return v0
            .end method
            
            .method public getName()Ljava/lang/String;
                .registers 2
                iget-object v0, p0, LTest;->mName:Ljava/lang/String;
                return-object v0
            .end method
        """.trimIndent())
        
        withE2ETest(workspace) {
            val uri = openFile("Test.smali")
            
            val symbols = documentSymbols(uri)
            
            // Should have one top-level symbol (the class)
            assertEquals(1, symbols.size, "Should have one class symbol")
            
            val classSymbol = symbols[0]
            // DocumentSymbolProvider returns full descriptor (LTest;), not simple name
            assertTrue(classSymbol.name == "Test" || classSymbol.name == "LTest;",
                "Class symbol should be 'Test' or 'LTest;', but was: ${classSymbol.name}")
            
            val children = classSymbol.children
            assertNotNull(children, "Class should have children (fields and methods)")
            
            // Should have 2 fields + 3 methods = 5 children
            assertTrue(children!!.size >= 5, 
                "Should have at least 5 children (2 fields + 3 methods), found ${children.size}")
            
            println("Document symbols:")
            println("  Class: ${classSymbol.name}")
            children.forEach { child ->
                println("    - ${child.name} (kind: ${child.kind})")
            }
        }
    }
    
    @Test
    @DisplayName("User edits file and sees changes reflected")
    fun `user edits file and sees changes reflected`() {
        val workspace = TestWorkspace.createTemp("""
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public foo()V
                .registers 1
                return-void
            .end method
        """.trimIndent())
        
        withE2ETest(workspace) {
            val uri = openFile("Test.smali")
            
            // Initial state - one method
            val symbols1 = documentSymbols(uri)
            assertEquals(1, symbols1[0].children!!.size, "Should have 1 method initially")
            
            // User adds another method
            val newContent = """
                .class public LTest;
                .super Ljava/lang/Object;
                
                .method public foo()V
                    .registers 1
                    return-void
                .end method
                
                .method public bar()V
                    .registers 1
                    return-void
                .end method
            """.trimIndent()
            
            changeFile(uri, newContent, 2)
            
            // Give server time to re-index
            Thread.sleep(200)
            
            // Should now have two methods
            val symbols2 = documentSymbols(uri)
            assertEquals(2, symbols2[0].children!!.size, "Should have 2 methods after edit")
        }
    }
}
