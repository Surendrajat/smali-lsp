package xyz.surendrajat.smalilsp.unit.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import xyz.surendrajat.smalilsp.core.*

import xyz.surendrajat.smalilsp.parser.SmaliParser
class InstructionParsingTest {
    
    private val parser = SmaliParser()
    
    @Test
    fun `parse invoke-virtual instruction`() {
        val smali = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 2
                
                new-instance v0, Ljava/lang/StringBuilder;
                invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
                
                const-string v1, "Hello"
                invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                
                return-void
            .end method
        """.trimIndent()
        
        val file = parser.parse("file:///test.smali", smali)
        assertNotNull(file)
        
        val method = file!!.methods.find { it.name == "test" }
        assertNotNull(method)
        
        val instructions = method!!.instructions
        assertTrue(instructions.size >= 2, "Expected at least 2 instructions, got ${instructions.size}")
        
        // Check for invoke-virtual instruction
        val invokeVirtual = instructions.filterIsInstance<InvokeInstruction>()
            .find { it.opcode == "invoke-virtual" && it.methodName == "append" }
        assertNotNull(invokeVirtual, "Should find invoke-virtual append instruction")
        assertEquals("Ljava/lang/StringBuilder;", invokeVirtual!!.className)
        assertEquals("(Ljava/lang/String;)Ljava/lang/StringBuilder;", invokeVirtual.descriptor)
        
        // Check for invoke-direct instruction
        val invokeDirect = instructions.filterIsInstance<InvokeInstruction>()
            .find { it.opcode == "invoke-direct" && it.methodName == "<init>" }
        assertNotNull(invokeDirect, "Should find invoke-direct <init> instruction")
        assertEquals("Ljava/lang/StringBuilder;", invokeDirect!!.className)
    }
    
    @Test
    fun `parse field access instructions`() {
        val smali = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .field private myField:I
            .field public static sValue:Ljava/lang/String;
            
            .method public test()V
                .locals 2
                
                iget v0, p0, Lcom/example/Test;->myField:I
                sget-object v1, Lcom/example/Test;->sValue:Ljava/lang/String;
                
                const v0, 0x2a
                iput v0, p0, Lcom/example/Test;->myField:I
                
                return-void
            .end method
        """.trimIndent()
        
        val file = parser.parse("file:///test.smali", smali)
        assertNotNull(file)
        
        val method = file!!.methods.find { it.name == "test" }
        assertNotNull(method)
        
        val instructions = method!!.instructions
        val fieldInstructions = instructions.filterIsInstance<FieldAccessInstruction>()
        assertTrue(fieldInstructions.size >= 3, "Expected at least 3 field access instructions")
        
        // Check for iget instruction
        val iget = fieldInstructions.find { it.opcode == "iget" }
        assertNotNull(iget, "Should find iget instruction")
        assertEquals("Lcom/example/Test;", iget!!.className)
        assertEquals("myField", iget.fieldName)
        assertEquals("I", iget.fieldType)
        
        // Check for sget-object instruction
        val sget = fieldInstructions.find { it.opcode == "sget-object" }
        assertNotNull(sget, "Should find sget-object instruction")
        assertEquals("Lcom/example/Test;", sget!!.className)
        assertEquals("sValue", sget.fieldName)
        assertEquals("Ljava/lang/String;", sget.fieldType)
        
        // Check for iput instruction
        val iput = fieldInstructions.find { it.opcode == "iput" }
        assertNotNull(iput, "Should find iput instruction")
        assertEquals("Lcom/example/Test;", iput!!.className)
        assertEquals("myField", iput.fieldName)
    }
    
    @Test
    fun `parse type instructions`() {
        val smali = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test(Ljava/lang/Object;)V
                .locals 1
                
                new-instance v0, Ljava/util/ArrayList;
                check-cast p1, Ljava/lang/String;
                instance-of v0, p1, Ljava/lang/CharSequence;
                
                return-void
            .end method
        """.trimIndent()
        
        val file = parser.parse("file:///test.smali", smali)
        assertNotNull(file)
        
        val method = file!!.methods.find { it.name == "test" }
        assertNotNull(method)
        
        val instructions = method!!.instructions
        val typeInstructions = instructions.filterIsInstance<TypeInstruction>()
        assertEquals(3, typeInstructions.size, "Expected 3 type instructions")
        
        // Check for new-instance instruction
        val newInstance = typeInstructions.find { it.opcode == "new-instance" }
        assertNotNull(newInstance, "Should find new-instance instruction")
        assertEquals("Ljava/util/ArrayList;", newInstance!!.className)
        
        // Check for check-cast instruction
        val checkCast = typeInstructions.find { it.opcode == "check-cast" }
        assertNotNull(checkCast, "Should find check-cast instruction")
        assertEquals("Ljava/lang/String;", checkCast!!.className)
        
        // Check for instance-of instruction
        val instanceOf = typeInstructions.find { it.opcode == "instance-of" }
        assertNotNull(instanceOf, "Should find instance-of instruction")
        assertEquals("Ljava/lang/CharSequence;", instanceOf!!.className)
    }
    
    @Test
    fun `parse real-world method with multiple instructions`() {
        val smali = """
            .class public Lcom/example/MainActivity;
            .super Landroid/app/Activity;
            
            .field private mButton:Landroid/widget/Button;
            
            .method protected onCreate(Landroid/os/Bundle;)V
                .locals 2
                
                invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V
                
                const v0, 0x7f030019
                invoke-virtual {p0, v0}, Lcom/example/MainActivity;->setContentView(I)V
                
                const v0, 0x7f080051
                invoke-virtual {p0, v0}, Lcom/example/MainActivity;->findViewById(I)Landroid/view/View;
                move-result-object v0
                check-cast v0, Landroid/widget/Button;
                iput-object v0, p0, Lcom/example/MainActivity;->mButton:Landroid/widget/Button;
                
                iget-object v0, p0, Lcom/example/MainActivity;->mButton:Landroid/widget/Button;
                new-instance v1, Lcom/example/MainActivity$1;
                invoke-direct {v1, p0}, Lcom/example/MainActivity$1;-><init>(Lcom/example/MainActivity;)V
                invoke-virtual {v0, v1}, Landroid/widget/Button;->setOnClickListener(Landroid/view/View${'$'}OnClickListener;)V
                
                return-void
            .end method
        """.trimIndent()
        
        val file = parser.parse("file:///MainActivity.smali", smali)
        assertNotNull(file)
        
        val method = file!!.methods.find { it.name == "onCreate" }
        assertNotNull(method)
        
        val instructions = method!!.instructions
        println("Found ${instructions.size} instructions")
        
        // Verify we have instructions
        assertTrue(instructions.isNotEmpty(), "Should have parsed instructions")
        
        // Count each type
        val invokeInstructions = instructions.filterIsInstance<InvokeInstruction>()
        val fieldInstructions = instructions.filterIsInstance<FieldAccessInstruction>()
        val typeInstructions = instructions.filterIsInstance<TypeInstruction>()
        
        println("Invoke: ${invokeInstructions.size}, Field: ${fieldInstructions.size}, Type: ${typeInstructions.size}")
        
        assertTrue(invokeInstructions.size >= 5, "Should have at least 5 invoke instructions")
        assertTrue(fieldInstructions.size >= 2, "Should have at least 2 field access instructions")
        assertTrue(typeInstructions.size >= 2, "Should have at least 2 type instructions")
        
        // Verify specific instructions
        assertTrue(invokeInstructions.any { it.methodName == "onCreate" })
        assertTrue(invokeInstructions.any { it.methodName == "setContentView" })
        assertTrue(invokeInstructions.any { it.methodName == "findViewById" })
        assertTrue(invokeInstructions.any { it.methodName == "setOnClickListener" })
        
        assertTrue(fieldInstructions.any { it.fieldName == "mButton" && it.opcode.startsWith("iget") })
        assertTrue(fieldInstructions.any { it.fieldName == "mButton" && it.opcode.startsWith("iput") })
        
        assertTrue(typeInstructions.any { it.opcode == "check-cast" })
        assertTrue(typeInstructions.any { it.opcode == "new-instance" })
    }
}
