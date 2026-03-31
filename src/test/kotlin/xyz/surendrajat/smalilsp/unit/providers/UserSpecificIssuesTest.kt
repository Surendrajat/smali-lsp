package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import kotlin.test.*
import xyz.surendrajat.smalilsp.integration.lsp.TempTestWorkspace

import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.integration.lsp.TestWorkspace
/**
 * Tests for user-specific issues reported on Nov 19, 2024.
 * 
 * Issue 1: Class references in declarations don't work
 * Example: .field public y:Lc1/s0;
 * Cursor on "Lc1/s0;" should find all references to class c1/s0, but only finds field references
 * 
 * Issue 2: Hover on array of custom class shows "not found"
 * Example: sget-object v0, La0/a3;->o:[La0/a3;
 * Hover on "[La0/a3;" should show array info, but shows "not found in workspace"
 */
class UserSpecificIssuesTest {

    @Test
    fun `issue 1 - find references on class type in field declaration`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("c1/s0.smali", """
            .class public Lc1/s0;
            .super Ljava/lang/Object;
            
            .field public value:I
        """.trimIndent())
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public y:Lc1/s0;
            .field public z:Lt3/m;
            
            .method public test()V
                .locals 1
                
                iget-object v0, p0, LTest;->y:Lc1/s0;
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val refProvider = ReferenceProvider(index)
        
        println("\n=== Testing class reference in field declaration ===")
        
        // Line: ".field public y:Lc1/s0;"
        // User wants: cursor on "Lc1/s0;" should find all references to class c1/s0
        val declarationLine = 3
        val lineText = ".field public y:Lc1/s0;"
        
        // Find position of class type "Lc1/s0;"
        val classTypeStart = lineText.indexOf("Lc1/s0;")
        println("Class type 'Lc1/s0;' starts at position: $classTypeStart")
        
        // Test cursor on the class type (on 'c' of Lc1/s0;)
        val pos = Position(declarationLine, classTypeStart + 1)
        val refs = refProvider.findReferences(uri, pos, true)
        
        println("\n=== Result: position ${classTypeStart + 1} (on class type) ===")
        println("Found ${refs.size} references")
        for (ref in refs) {
            println("  - Line ${ref.range.start.line}: ${ref.uri}")
        }
        
        // Should find:
        // 1. Declaration in c1/s0.smali
        // 2. Field declaration: .field public y:Lc1/s0; (line 3)
        // 3. Field usage: iget-object v0, p0, LTest;->y:Lc1/s0; (line 9)
        
        if (refs.size < 2) {
            fail("Expected at least 2 references to class c1/s0 (field type + usage type), got ${refs.size}")
        }
        
        val lines = refs.map { it.range.start.line }.sorted()
        println("Reference lines: $lines")
        
        // Should include line 3 (field declaration type) and line 9 (iget type)
        assertTrue(refs.size >= 2, "Should find at least 2 references to class type")
    }
    
    @Test
    fun `issue 1 - find references on class type in constructor parameters`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("c1/s0.smali", """
            .class public Lc1/s0;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        workspace.addFile("ut/a.smali", """
            .class public Lut/a;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public constructor <init>(Lut/a;Landroid/view/View;Lt3/c;Lc1/s0;Ljava/util/UUID;)V
                .locals 0
                
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val refProvider = ReferenceProvider(index)
        
        println("\n=== Testing class reference in constructor parameters ===")
        
        // Line: ".method public constructor <init>(Lut/a;Landroid/view/View;Lt3/c;Lc1/s0;Ljava/util/UUID;)V"
        // User wants: cursor on "Lc1/s0;" should find all references to class c1/s0
        val declarationLine = 3
        val lineText = ".method public constructor <init>(Lut/a;Landroid/view/View;Lt3/c;Lc1/s0;Ljava/util/UUID;)V"
        
        // Find position of "Lc1/s0;" in parameters
        val classTypeStart = lineText.indexOf("Lc1/s0;")
        println("Class type 'Lc1/s0;' starts at position: $classTypeStart")
        
        // Test cursor on the class type
        val pos = Position(declarationLine, classTypeStart + 2) // On '1' of Lc1/s0;
        val refs = refProvider.findReferences(uri, pos, true)
        
        println("\n=== Result: position ${classTypeStart + 2} (on class type in params) ===")
        println("Found ${refs.size} references")
        for (ref in refs) {
            println("  - Line ${ref.range.start.line}: ${ref.uri}")
        }
        
        // Should find references to class c1/s0 (declaration + usages)
        // Currently might only find method references, not class references
        
        if (refs.isEmpty()) {
            fail("Should find references when cursor is on class type in method parameters")
        }
    }
    
    @Test
    fun `issue 2 - hover on custom class array shows array info not not found`() {
        val workspace = TempTestWorkspace()
        
        workspace.addFile("a0/a3.smali", """
            .class public final enum La0/a3;
            .super Ljava/lang/Enum;
            
            .field public static final enum A:La0/a3;
            .field public static final synthetic o:[La0/a3;
            
            .method static constructor <clinit>()V
                .locals 1
                
                const/4 v0, 0x1
                new-array v0, v0, [La0/a3;
                sput-object v0, La0/a3;->o:[La0/a3;
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("a0/a3.smali")
        val hoverProvider = HoverProvider(index)
        
        println("\n=== Testing hover on custom class array [La0/a3; ===")
        
        // Line: "sput-object v0, La0/a3;->o:[La0/a3;"
        // User reports: hover on "[La0/a3;" shows "not found in workspace"
        val line = 11
        
        // Scan all positions to find where the array type is
        for (col in 0..60) {
            val hover = hoverProvider.provideHover(uri, Position(line, col))
            if (hover != null) {
                val content = hover.contents.right.value
                if (content.contains("a3") || content.contains("array") || content.contains("not found")) {
                    println("Position $col: ${content.take(100).replace("\n", " | ")}")
                }
            }
        }
        
        // Test at position on the array type [La0/a3;
        // Note: trimIndent() removes leading whitespace, but actual file has 4 spaces indentation
        val lineText = "    sput-object v0, La0/a3;->o:[La0/a3;"  // Include indentation!
        val arrayTypeStart = lineText.lastIndexOf("[La0/a3;")
        println("\nArray type '[La0/a3;' starts at position: $arrayTypeStart")
        
        val pos = Position(line, arrayTypeStart)
        val hover = hoverProvider.provideHover(uri, pos)
        
        println("\n=== Result at position $arrayTypeStart ===")
        
        // Verify class can be found in index
        val classDef = index.findClass("La0/a3;")
        assertTrue(classDef != null, "Class La0/a3; should be in index")
        
        if (hover == null) {
            fail("Should provide hover for [La0/a3; array type")
        } else {
            val content = hover.contents.right.value
            println("Content: $content")
            
            // Check if it says "not found"
            if (content.contains("not found", ignoreCase = true) || content.contains("Not found")) {
                fail("❌ USER ISSUE CONFIRMED: Hover shows 'not found' for custom class array: $content")
            }
            
            // Should show array or class info
            assertTrue(content.contains("a3") || content.contains("array") || content.contains("[]"),
                "Should show array or class info for [La0/a3;, got: $content")
        }
    }
}
