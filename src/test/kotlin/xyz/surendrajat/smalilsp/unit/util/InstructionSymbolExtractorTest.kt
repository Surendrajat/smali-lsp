package xyz.surendrajat.smalilsp.unit.util

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.util.InstructionSymbolExtractor
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Comprehensive tests for InstructionSymbolExtractor.
 * 
 * Tests:
 * - Invoke instructions (all types)
 * - Field access instructions (iget, iput, sget, sput)
 * - Type instructions (new-instance, check-cast, instance-of)
 * - Cursor positioning (exact symbol detection)
 * - Duplicate types (multiple params of same type)
 * - Edge cases (cursor before/after symbol, on whitespace)
 */
class InstructionSymbolExtractorTest {
    
    private val parser = SmaliParser()
    
    //
    // Invoke Instruction Tests
    //
    
    @Test
    fun `invoke-virtual - cursor on class name`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                invoke-virtual {v0}, LClass;->method()V
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as InvokeInstruction
        
        val line = "    invoke-virtual {v0}, LClass;->method()V"
        // Cursor on "LClass;" - character 27 is on 'L'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 27)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.ClassRef>(symbol)
        assertEquals("LClass;", symbol.className)
    }
    
    @Test
    fun `invoke-virtual - cursor on method name`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                invoke-virtual {v0}, LClass;->method()V
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as InvokeInstruction
        
        val line = "    invoke-virtual {v0}, LClass;->method()V"
        // Cursor on "method" - character 37 is on 'm'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 37)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.MethodRef>(symbol)
        assertEquals("LClass;", symbol.className)
        assertEquals("method", symbol.methodName)
    }
    
    @Test
    fun `invoke-virtual - cursor on parameter type`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                invoke-virtual {v0, v1}, LClass;->method(Ljava/lang/String;)V
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as InvokeInstruction
        
        val line = "    invoke-virtual {v0, v1}, LClass;->method(Ljava/lang/String;)V"
        // Cursor on "Ljava/lang/String;" in parameter - character 50 is on 'j'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 50)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.ClassRef>(symbol)
        assertEquals("Ljava/lang/String;", symbol.className)
    }
    
    @Test
    fun `invoke-virtual - cursor on return type`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                invoke-virtual {v0}, LClass;->method()Ljava/lang/Object;
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as InvokeInstruction
        
        val line = "    invoke-virtual {v0}, LClass;->method()Ljava/lang/Object;"
        // Cursor on "Ljava/lang/Object;" in return type - character 47 is on 'j'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 47)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.ClassRef>(symbol)
        assertEquals("Ljava/lang/Object;", symbol.className)
    }
    
    @Test
    fun `invoke-virtual - duplicate types - first occurrence`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                invoke-virtual {v0, v1, v2}, LClass;->concat(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as InvokeInstruction
        
        val line = "    invoke-virtual {v0, v1, v2}, LClass;->concat(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
        // Cursor on FIRST "Ljava/lang/String;" - character 53 is on first 'j'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 53)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.ClassRef>(symbol)
        assertEquals("Ljava/lang/String;", symbol.className)
    }
    
    @Test
    fun `invoke-virtual - duplicate types - second occurrence`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                invoke-virtual {v0, v1, v2}, LClass;->concat(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as InvokeInstruction
        
        val line = "    invoke-virtual {v0, v1, v2}, LClass;->concat(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
        // Cursor on SECOND "Ljava/lang/String;" - character 72 is on second 'j' 
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 72)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.ClassRef>(symbol)
        assertEquals("Ljava/lang/String;", symbol.className)
    }
    
    @Test
    fun `invoke-static - cursor on class name`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                invoke-static {}, LUtils;->staticMethod()V
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as InvokeInstruction
        
        val line = "    invoke-static {}, LUtils;->staticMethod()V"
        // Cursor on "LUtils;" - character 23 is on 'L'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 23)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.ClassRef>(symbol)
        assertEquals("LUtils;", symbol.className)
    }
    
    @Test
    fun `invoke-direct - cursor on method name (constructor)`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                invoke-direct {v0}, Ljava/lang/Object;-><init>()V
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as InvokeInstruction
        
        val line = "    invoke-direct {v0}, Ljava/lang/Object;-><init>()V"
        // Cursor on "<init>" - character 48 is on '<'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 48)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.MethodRef>(symbol)
        assertEquals("<init>", symbol.methodName)
    }
    
    @Test
    fun `invoke-virtual - cursor on whitespace - no match`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                invoke-virtual {v0}, LClass;->method()V
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as InvokeInstruction
        
        val line = "    invoke-virtual {v0}, LClass;->method()V"
        // Cursor on whitespace before "invoke" - character 2
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 2)
        
        assertNull(symbol, "Cursor on whitespace should return null")
    }
    
    @Test
    fun `invoke-virtual - cursor after arrow - no match`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                invoke-virtual {v0}, LClass;->method()V
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as InvokeInstruction
        
        val line = "    invoke-virtual {v0}, LClass;->method()V"
        // Cursor on ">" in "->" - character 33
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 33)
        
        assertNull(symbol, "Cursor on arrow should return null")
    }
    
    //
    // Field Access Instruction Tests
    //
    
    @Test
    fun `iget-object - cursor on class name`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                iget-object v0, v1, LClass;->field:Ljava/lang/String;
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as FieldAccessInstruction
        
        val line = "    iget-object v0, v1, LClass;->field:Ljava/lang/String;"
        // Cursor on "LClass;" - character 28 is on 'L'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 28)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.ClassRef>(symbol)
        assertEquals("LClass;", symbol.className)
    }
    
    @Test
    fun `iget-object - cursor on field name`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                iget-object v0, v1, LClass;->field:Ljava/lang/String;
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as FieldAccessInstruction
        
        val line = "    iget-object v0, v1, LClass;->field:Ljava/lang/String;"
        // Cursor on "field" - character 33 is on 'f'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 33)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.FieldRef>(symbol)
        assertEquals("LClass;", symbol.className)
        assertEquals("field", symbol.fieldName)
        assertEquals("Ljava/lang/String;", symbol.fieldType)
    }
    
    @Test
    fun `iget-object - cursor on field type`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                iget-object v0, v1, LClass;->field:Ljava/lang/String;
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as FieldAccessInstruction
        
        val line = "    iget-object v0, v1, LClass;->field:Ljava/lang/String;"
        // Cursor on "Ljava/lang/String;" - character 46 is on 'j'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 46)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.ClassRef>(symbol)
        assertEquals("Ljava/lang/String;", symbol.className)
    }
    
    @Test
    fun `sget - cursor on class name (static field)`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                sget-object v0, LConfig;->DEBUG:Z
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as FieldAccessInstruction
        
        val line = "    sget-object v0, LConfig;->DEBUG:Z"
        // Cursor on "LConfig;" - character 24 is on 'L'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 24)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.ClassRef>(symbol)
        assertEquals("LConfig;", symbol.className)
    }
    
    @Test
    fun `iput - cursor on field name`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                iput-object v0, v1, LClass;->field:Ljava/lang/String;
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as FieldAccessInstruction
        
        val line = "    iput-object v0, v1, LClass;->field:Ljava/lang/String;"
        // Cursor on "field" - character 33 is on 'f'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 33)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.FieldRef>(symbol)
        assertEquals("field", symbol.fieldName)
    }
    
    //
    // Type Instruction Tests
    //
    
    @Test
    fun `new-instance - cursor on class name`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                new-instance v0, Ljava/lang/StringBuilder;
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as TypeInstruction
        
        val line = "    new-instance v0, Ljava/lang/StringBuilder;"
        // Cursor on "Ljava/lang/StringBuilder;" - character 25 is on 'j'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 25)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.ClassRef>(symbol)
        assertEquals("Ljava/lang/StringBuilder;", symbol.className)
    }
    
    @Test
    fun `check-cast - cursor on class name`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                check-cast p1, Landroid/os/Bundle;
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as TypeInstruction
        
        val line = "    check-cast p1, Landroid/os/Bundle;"
        // Cursor on "Landroid/os/Bundle;" - character 24 is on 'n'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 24)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.ClassRef>(symbol)
        assertEquals("Landroid/os/Bundle;", symbol.className)
    }
    
    @Test
    fun `check-cast - array type`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                check-cast p1, [Ljava/lang/Object;
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as TypeInstruction
        
        val line = "    check-cast p1, [Ljava/lang/Object;"
        // Cursor on array type - should extract base class (Bug #2 fix)
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 24)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.ClassRef>(symbol)
        assertEquals("Ljava/lang/Object;", symbol.className, "Should strip array brackets to navigate to base class")
    }
    
    @Test
    fun `instance-of - cursor on class name`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                instance-of v0, p1, Ljava/lang/String;
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as TypeInstruction
        
        val line = "    instance-of v0, p1, Ljava/lang/String;"
        // Cursor on "Ljava/lang/String;" - character 30 is on 'j'
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 30)
        
        assertNotNull(symbol)
        assertIs<InstructionSymbolExtractor.Symbol.ClassRef>(symbol)
        assertEquals("Ljava/lang/String;", symbol.className)
    }
    
    //
    // Edge Cases
    //
    
    @Test
    fun `non-symbol instruction - returns null`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                const/4 v0, 0x0
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        
        // NOTE: const/4 is NOT parsed into AST (simple instructions not included)
        // This is expected behavior - only symbol-referencing instructions are in AST
        // So instructions list will be empty
        assertEquals(0, file.methods[0].instructions.size, "const/4 should not be in AST")
        
        // This test documents that non-symbol instructions are not in AST,
        // so extractSymbol is never called for them in real usage
    }
    
    @Test
    fun `cursor before symbol - no match`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                invoke-virtual {v0}, LClass;->method()V
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as InvokeInstruction
        
        val line = "    invoke-virtual {v0}, LClass;->method()V"
        // Cursor before "LClass;" - character 23 is on ','
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 23)
        
        assertNull(symbol, "Cursor before symbol should return null")
    }
    
    @Test
    fun `cursor after symbol - no match`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                invoke-virtual {v0}, LClass;->method()V
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as InvokeInstruction
        
        val line = "    invoke-virtual {v0}, LClass;->method()V"
        // Cursor after "method" - character 45 is on '('
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 45)
        
        assertNull(symbol, "Cursor after symbol should return null")
    }
    
    @Test
    fun `primitive array parameter - no navigation`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method test()V
                invoke-virtual {v0, v1}, LClass;->method([I)V
            .end method
        """.trimIndent()
        
        val file = parser.parse("test.smali", content)!!
        val instruction = file.methods[0].instructions[0] as InvokeInstruction
        
        val line = "    invoke-virtual {v0, v1}, LClass;->method([I)V"
        // Cursor on "[I" (primitive array) - character 50 is on '['
        // Primitive arrays should not be extracted as navigable symbols
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 50)
        
        // [I is not in the descriptor types list (extractTypesFromDescriptor skips primitives)
        assertNull(symbol, "Primitive arrays should not be navigable")
    }
}
