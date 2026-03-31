package xyz.surendrajat.smalilsp.regression

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.integration.lsp.TempTestWorkspace
import xyz.surendrajat.smalilsp.core.FieldAccessInstruction
import kotlin.test.assertTrue

/**
 * Test that instruction ranges include the full instruction line
 */
class InstructionRangeBugTest {
    
    @Test
    fun `instruction range should include field reference`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("Test.smali", """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .field public value:I
            
            .method public test()V
                iget v0, p0, LTest;->value:I
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val uri = workspace.getUri("Test.smali")
        val file = index.findFileByUri(uri)!!
        
        val testMethod = file.methods.find { it.name == "test" }!!
        val igetInstruction = testMethod.instructions[0] as FieldAccessInstruction
        
        // Get the actual line text
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val instructionLine = lines[igetInstruction.range.start.line]
        
        println("=== Instruction Range Bug Test ===")
        println("Line text: '$instructionLine'")
        println("Line length: ${instructionLine.length}")
        println("Instruction range: char ${igetInstruction.range.start.character} to ${igetInstruction.range.end.character}")
        println("Field reference starts at: ${instructionLine.indexOf("LTest;")}")
        
        // The range should include "LTest;->value:I" which starts around char 14
        val fieldRefStart = instructionLine.indexOf("LTest;")
        assertTrue(fieldRefStart >= 0, "Field reference not found in line")
        
        assertTrue(
            igetInstruction.range.end.character >= fieldRefStart + "LTest;->value:I".length,
            "Instruction range (${igetInstruction.range.end.character}) should include full field reference ending at ${fieldRefStart + "LTest;->value:I".length}"
        )
        
        workspace.cleanup()
    }
}
