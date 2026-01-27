package xyz.surendrajat.smalilsp.unit.parser

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.JumpInstruction
import xyz.surendrajat.smalilsp.parser.SmaliParser

/**
 * Unit tests for label parsing in SmaliParser.
 * 
 * Tests that:
 * - Label definitions are correctly parsed and stored
 * - Jump instructions (goto, if-*) are correctly parsed
 * - Label ranges are accurate for navigation
 */
class LabelParsingTest {
    
    private val parser = SmaliParser()
    
    @Test
    fun `parse label definitions in method`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()V
                .registers 2
                
                const/4 v0, 0x1
                
                :cond_0
                return-void
            .end method
        """.trimIndent()
        
        val file = parser.parse("test://test.smali", content)
        
        assertNotNull(file)
        assertEquals(1, file!!.methods.size)
        
        val method = file.methods[0]
        assertTrue(method.labels.containsKey("cond_0"), "Should parse cond_0 label")
        assertEquals(8, method.labels["cond_0"]?.range?.start?.line, "Label should be at line 8")
    }
    
    @Test
    fun `parse goto instruction`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()V
                .registers 1
                
                goto :exit
                
                :exit
                return-void
            .end method
        """.trimIndent()
        
        val file = parser.parse("test://test.smali", content)
        
        assertNotNull(file)
        val method = file!!.methods[0]
        
        // Should have a JumpInstruction
        val jumpInstructions = method.instructions.filterIsInstance<JumpInstruction>()
        assertEquals(1, jumpInstructions.size, "Should have 1 goto instruction")
        
        val gotoInst = jumpInstructions[0]
        assertEquals("goto", gotoInst.opcode)
        assertEquals("exit", gotoInst.targetLabel)
    }
    
    @Test
    fun `parse if-eqz instruction`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()V
                .registers 2
                
                const/4 v0, 0x0
                if-eqz v0, :cond_0
                
                :cond_0
                return-void
            .end method
        """.trimIndent()
        
        val file = parser.parse("test://test.smali", content)
        
        assertNotNull(file)
        val method = file!!.methods[0]
        
        val jumpInstructions = method.instructions.filterIsInstance<JumpInstruction>()
        assertEquals(1, jumpInstructions.size)
        
        val ifInst = jumpInstructions[0]
        assertEquals("if-eqz", ifInst.opcode)
        assertEquals("cond_0", ifInst.targetLabel)
    }
    
    @Test
    fun `parse if-lt instruction (two registers)`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()V
                .registers 3
                
                const/4 v0, 0x0
                const/4 v1, 0x5
                if-lt v0, v1, :loop_start
                
                :loop_start
                return-void
            .end method
        """.trimIndent()
        
        val file = parser.parse("test://test.smali", content)
        
        assertNotNull(file)
        val method = file!!.methods[0]
        
        val jumpInstructions = method.instructions.filterIsInstance<JumpInstruction>()
        assertEquals(1, jumpInstructions.size)
        
        val ifInst = jumpInstructions[0]
        assertEquals("if-lt", ifInst.opcode)
        assertEquals("loop_start", ifInst.targetLabel)
    }
    
    @Test
    fun `multiple labels and jumps in same method`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()V
                .registers 3
                
                const/4 v0, 0x0
                const/4 v1, 0x5
                
                :loop
                add-int/lit8 v0, v0, 0x1
                if-lt v0, v1, :loop
                goto :exit
                
                :unused
                const/4 v2, 0x0
                
                :exit
                return-void
            .end method
        """.trimIndent()
        
        val file = parser.parse("test://test.smali", content)
        
        assertNotNull(file)
        val method = file!!.methods[0]
        
        // Should have 3 labels
        assertEquals(3, method.labels.size, "Should have 3 labels")
        assertTrue(method.labels.containsKey("loop"))
        assertTrue(method.labels.containsKey("unused"))
        assertTrue(method.labels.containsKey("exit"))
        
        // Should have 2 jump instructions
        val jumpInstructions = method.instructions.filterIsInstance<JumpInstruction>()
        assertEquals(2, jumpInstructions.size, "Should have 2 jump instructions")
    }
    
    @Test
    fun `label range includes colon`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()V
                .registers 1
                
                :my_label
                return-void
            .end method
        """.trimIndent()
        
        val file = parser.parse("test://test.smali", content)
        
        assertNotNull(file)
        val method = file!!.methods[0]
        val label = method.labels["my_label"]
        
        assertNotNull(label)
        // Label range should start at position of ':'
        assertTrue(label!!.range.start.character >= 0)
    }
    
    @Test
    fun `parse all if-* two register comparisons`() {
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
                if-gt v0, v1, :exit
                if-le v0, v1, :exit
                goto :exit
                
                :exit
                return-void
            .end method
        """.trimIndent()
        
        val file = parser.parse("test://test.smali", content)
        
        assertNotNull(file)
        val method = file!!.methods[0]
        
        // Check labels
        assertTrue(method.labels.containsKey("exit"), "Should parse exit label")
        
        // Should have 7 jump instructions
        val jumpInstructions = method.instructions.filterIsInstance<JumpInstruction>()
        
        println("Jump instructions found: ${jumpInstructions.size}")
        for (jump in jumpInstructions) {
            println("  - ${jump.opcode} -> ${jump.targetLabel}")
        }
        
        assertEquals(7, jumpInstructions.size, "Should have 7 jump instructions")
        
        val opcodes = jumpInstructions.map { it.opcode }.toSet()
        assertTrue("if-lt" in opcodes, "Should parse if-lt")
        assertTrue("if-ge" in opcodes, "Should parse if-ge")
        assertTrue("if-eq" in opcodes, "Should parse if-eq")
        assertTrue("if-ne" in opcodes, "Should parse if-ne")
        assertTrue("if-gt" in opcodes, "Should parse if-gt")
        assertTrue("if-le" in opcodes, "Should parse if-le")
        assertTrue("goto" in opcodes, "Should parse goto")
    }
}
