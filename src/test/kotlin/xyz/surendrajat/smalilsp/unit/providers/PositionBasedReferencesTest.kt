package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import kotlin.test.*
import xyz.surendrajat.smalilsp.shared.TempTestWorkspace

import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.shared.TestWorkspace
/**
 * Test position-based find references on declarations.
 * 
 * User Issue: When cursor is on method/field NAME in .method or .field declaration line,
 * find references should show all references to that method/field, not just the declaration.
 */
class PositionBasedReferencesTest {

    @Test
    fun `find references on field NAME in declaration line`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public counter:I
            
            .method public test()V
                .locals 1
                
                iget v0, p0, LTest;->counter:I
                iput v0, p0, LTest;->counter:I
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val refProvider = ReferenceProvider(index)
        
        println("\n=== Testing find references on field NAME in declaration ===")
        
        // Line: ".field public counter:I"
        // Cursor on "counter" - the field name
        val declarationLine = 3
        
        // Find position of "counter" in the line
        val lineText = ".field public counter:I"
        val fieldNameStart = lineText.indexOf("counter")
        println("Field name 'counter' starts at position: $fieldNameStart")
        
        // Test at various positions on "counter"
        for (offset in 0..6) {
            val pos = Position(declarationLine, fieldNameStart + offset)
            val refs = refProvider.findReferences(uri, pos, true)
            
            println("Position ${fieldNameStart + offset} (char '${lineText[fieldNameStart + offset]}'): ${refs.size} references")
            if (refs.isNotEmpty()) {
                for (loc in refs) {
                    println("  - Line ${loc.range.start.line}: ${loc.uri}")
                }
            }
        }
        
        // Now test a position in the middle of "counter"
        val pos = Position(declarationLine, fieldNameStart + 3) // On 'n' of 'counter'
        val refs = refProvider.findReferences(uri, pos, true)
        
        println("\n=== Result: position ${fieldNameStart + 3} (middle of 'counter') ===")
        println("Found ${refs.size} references")
        
        if (refs.size < 3) {
            fail("Expected at least 3 references (1 declaration + 2 usages), got ${refs.size}")
        }
        
        // Should find:
        // 1. Declaration: .field public counter:I (line 3)
        // 2. iget v0, p0, LTest;->counter:I (line 8)
        // 3. iput v0, p0, LTest;->counter:I (line 9)
        val lines = refs.map { it.range.start.line }.sorted()
        println("Reference lines: $lines")
        
        assertTrue(lines.contains(3), "Should include declaration line 3")
        assertTrue(lines.contains(8), "Should include iget usage line 8")
        assertTrue(lines.contains(9), "Should include iput usage line 9")
    }
    
    @Test
    fun `find references on method NAME in declaration line`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public calculate(I)I
                .locals 1
                
                add-int/lit8 v0, p1, 0x1
                return v0
            .end method
            
            .method public main()V
                .locals 2
                
                const/4 v0, 0x5
                invoke-virtual {p0, v0}, LTest;->calculate(I)I
                move-result v1
                
                invoke-virtual {p0, v0}, LTest;->calculate(I)I
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val refProvider = ReferenceProvider(index)
        
        println("\n=== Testing find references on method NAME in declaration ===")
        
        // Line: ".method public calculate(I)I"
        // Cursor on "calculate" - the method name
        val declarationLine = 3
        
        val lineText = ".method public calculate(I)I"
        val methodNameStart = lineText.indexOf("calculate")
        println("Method name 'calculate' starts at position: $methodNameStart")
        
        // Test in middle of "calculate"
        val pos = Position(declarationLine, methodNameStart + 4) // On 'u' of 'calculate'
        val refs = refProvider.findReferences(uri, pos, true)
        
        println("\n=== Result: position ${methodNameStart + 4} (middle of 'calculate') ===")
        println("Found ${refs.size} references")
        for (loc in refs) {
            println("  - Line ${loc.range.start.line}")
        }
        
        if (refs.size < 3) {
            fail("Expected at least 3 references (1 declaration + 2 invocations), got ${refs.size}")
        }
        
        // Should find:
        // 1. Declaration: .method public calculate(I)I (line 3)
        // 2. invoke-virtual {p0, v0}, LTest;->calculate(I)I (line 14)
        // 3. invoke-virtual {p0, v0}, LTest;->calculate(I)I (line 17)
        val lines = refs.map { it.range.start.line }.sorted()
        println("Reference lines: $lines")
        
        assertTrue(lines.contains(3), "Should include declaration line 3")
        assertTrue(lines.contains(14), "Should include invoke line 14")
        assertTrue(lines.contains(17), "Should include invoke line 17")
    }
}
