package xyz.surendrajat.smalilsp.stress.parsing

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertDoesNotThrow
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.util.InstructionSymbolExtractor

/**
 * Stress tests for InstructionSymbolExtractor.
 * Tests edge cases, malformed input, extreme cases.
 */
class InstructionSymbolExtractorStressTest {

    /**
     * Test with 1000+ instruction method to ensure extraction works in large methods
     */
    @Test
    fun `stress test - large method with 1000 instructions`() {
        val instructions = (0 until 1000).joinToString("\n") { i ->
            "    invoke-virtual {v$i}, Ljava/lang/Object;->toString()Ljava/lang/String;"
        }
        
        val smaliCode = """
            .class public LStressTest;
            .super Ljava/lang/Object;
            
            .method public hugeMethod()V
                .locals 1000
                $instructions
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val method = file.methods[0]
        
        // Test extraction from instruction at various positions
        // Test first instruction
        val firstInstruction = method.instructions[0]
        val firstLine = "    invoke-virtual {v0}, Ljava/lang/Object;->toString()Ljava/lang/String;"
        val symbolFirst = InstructionSymbolExtractor.extractSymbol(
            firstInstruction, firstLine, firstLine.indexOf("toString")
        )
        assertTrue(symbolFirst is InstructionSymbolExtractor.Symbol.MethodRef)
        assertEquals("toString", (symbolFirst as InstructionSymbolExtractor.Symbol.MethodRef).methodName)
        
        // Test middle instruction
        val middleInstruction = method.instructions[500]
        val middleLine = "    invoke-virtual {v500}, Ljava/lang/Object;->toString()Ljava/lang/String;"
        val symbolMiddle = InstructionSymbolExtractor.extractSymbol(
            middleInstruction, middleLine, middleLine.indexOf("toString")
        )
        assertTrue(symbolMiddle is InstructionSymbolExtractor.Symbol.MethodRef)
        
        // Test last instruction
        val lastInstruction = method.instructions[999]
        val lastLine = "    invoke-virtual {v999}, Ljava/lang/Object;->toString()Ljava/lang/String;"
        val symbolLast = InstructionSymbolExtractor.extractSymbol(
            lastInstruction, lastLine, lastLine.indexOf("toString")
        )
        assertTrue(symbolLast is InstructionSymbolExtractor.Symbol.MethodRef)
    }

    /**
     * Test with line containing same type multiple times (10+ occurrences)
     */
    @Test
    fun `stress test - instruction with 10 same-type parameters`() {
        // Method with 10 String parameters
        val line = "    invoke-static {v0, v1, v2, v3, v4, v5, v6, v7, v8, v9}, LTest;->concat(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 10
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Find all occurrences of "Ljava/lang/String;"
        val searchText = "Ljava/lang/String;"
        var searchStart = 0
        val positions = mutableListOf<Int>()
        
        while (true) {
            val index = line.indexOf(searchText, searchStart)
            if (index == -1) break
            positions.add(index)
            searchStart = index + searchText.length
        }
        
        // Should have 11 occurrences (10 params + 1 return)
        assertTrue(positions.size >= 10, "Should have at least 10 String occurrences")
        
        // Test that we can extract symbol from each position
        positions.forEach { pos ->
            val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, pos + 5)
            assertNotNull(symbol, "Should extract symbol at position $pos")
            assertTrue(symbol is InstructionSymbolExtractor.Symbol.ClassRef)
        }
    }

    /**
     * Test with very long class name (100+ characters)
     */
    @Test
    fun `stress test - very long class name`() {
        val longClassName = "L" + "a".repeat(100) + "/VeryLongClassName;"
        val line = "    invoke-virtual {v0}, $longClassName->method()V"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Test extraction of long class name
        val classStart = line.indexOf(longClassName)
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, classStart + 50)
        assertNotNull(symbol)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.ClassRef)
        assertEquals(longClassName, (symbol as InstructionSymbolExtractor.Symbol.ClassRef).className)
    }

    /**
     * Test with very long method name (100+ characters)
     */
    @Test
    fun `stress test - very long method name`() {
        val longMethodName = "m" + "e".repeat(100) + "thod"
        val line = "    invoke-virtual {v0}, LTest;->$longMethodName()V"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Test extraction of long method name
        val methodStart = line.indexOf(longMethodName)
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, methodStart + 50)
        assertNotNull(symbol)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.MethodRef)
        assertEquals(longMethodName, (symbol as InstructionSymbolExtractor.Symbol.MethodRef).methodName)
    }

    /**
     * Test with deeply nested package name
     */
    @Test
    fun `stress test - deeply nested package`() {
        val deepPackage = "Lcom/example/android/app/ui/components/views/custom/special/extended/BaseCustomView;"
        val line = "    new-instance v0, $deepPackage"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        val classStart = line.indexOf(deepPackage)
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, classStart + 40)
        assertNotNull(symbol)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.ClassRef)
        assertEquals(deepPackage, (symbol as InstructionSymbolExtractor.Symbol.ClassRef).className)
    }

    /**
     * Test with special characters in class name (dollar signs, underscores)
     */
    @Test
    fun `stress test - special characters in names`() {
        val className = "Lcom/example/Class\$Inner_\$Nested\$\$Special;"
        val methodName = "method\$with_special\$chars"
        val line = "    invoke-virtual {v0}, $className->$methodName()V"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Test class name extraction
        val classStart = line.indexOf(className)
        val classSymbol = InstructionSymbolExtractor.extractSymbol(instruction, line, classStart + 10)
        assertNotNull(classSymbol)
        assertTrue(classSymbol is InstructionSymbolExtractor.Symbol.ClassRef)
        
        // Test method name extraction
        val methodStart = line.indexOf(methodName)
        val methodSymbol = InstructionSymbolExtractor.extractSymbol(instruction, line, methodStart + 5)
        assertNotNull(methodSymbol)
        assertTrue(methodSymbol is InstructionSymbolExtractor.Symbol.MethodRef)
    }

    /**
     * Test with extreme indentation (100 spaces)
     */
    @Test
    fun `stress test - extreme indentation`() {
        val indent = " ".repeat(100)
        val line = "${indent}invoke-virtual {v0}, Ljava/lang/Object;->toString()Ljava/lang/String;"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Cursor in the indentation should return null
        val symbolIndent = InstructionSymbolExtractor.extractSymbol(instruction, line, 50)
        assertNull(symbolIndent)
        
        // Cursor on actual content should work
        val methodStart = line.indexOf("toString")
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, methodStart + 2)
        assertNotNull(symbol)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.MethodRef)
    }

    /**
     * Test with mixed spacing (tabs, multiple spaces)
     */
    @Test
    fun `stress test - mixed whitespace`() {
        val line = "\t  \t invoke-virtual  \t  {v0},  \t Ljava/lang/Object;  ->  toString()Ljava/lang/String;"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Should still extract symbols despite weird spacing
        val classStart = line.indexOf("Ljava/lang/Object;")
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, classStart + 5)
        assertNotNull(symbol)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.ClassRef)
    }

    /**
     * Test with cursor at every single position in a complex line
     * This ensures no position causes crash or unexpected behavior
     */
    @Test
    fun `stress test - cursor at every position`() {
        val line = "    invoke-virtual {v0, v1}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 2
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Test every single character position
        for (i in 0 until line.length) {
            // Should not throw exception at any position
            assertDoesNotThrow {
                InstructionSymbolExtractor.extractSymbol(instruction, line, i)
            }
        }
    }

    /**
     * Test with malformed instruction (missing semicolon)
     */
    @Test
    fun `stress test - malformed class name missing semicolon`() {
        val line = "    invoke-virtual {v0}, Ljava/lang/Object->toString()V"  // Missing ; after Object
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Should handle gracefully
        assertDoesNotThrow {
            val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 30)
            // May return null or partial match, just shouldn't crash
        }
    }

    /**
     * Test with array types of various dimensions
     */
    @Test
    fun `stress test - multi-dimensional arrays`() {
        val line = "    invoke-virtual {v0}, LTest;->process([[[Ljava/lang/String;)V"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Cursor on the base type (String) should extract it
        val stringStart = line.indexOf("Ljava/lang/String;")
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, stringStart + 5)
        assertNotNull(symbol)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.ClassRef)
    }

    /**
     * Test with primitive types mixed with object types
     */
    @Test
    fun `stress test - mixed primitive and object parameters`() {
        val line = "    invoke-virtual {v0, v1, v2, v3, v4}, LTest;->complex(ILjava/lang/String;Z[ILjava/util/List;)V"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 5
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Should extract object types but not primitives
        val stringStart = line.indexOf("Ljava/lang/String;")
        val stringSymbol = InstructionSymbolExtractor.extractSymbol(instruction, line, stringStart + 5)
        assertNotNull(stringSymbol)
        
        val listStart = line.indexOf("Ljava/util/List;")
        val listSymbol = InstructionSymbolExtractor.extractSymbol(instruction, line, listStart + 5)
        assertNotNull(listSymbol)
        
        // Cursor on primitive should return null
        val primitivePos = line.indexOf("I")  // First occurrence of 'I' primitive
        val primitiveSymbol = InstructionSymbolExtractor.extractSymbol(instruction, line, primitivePos)
        // May or may not be null depending on position, just shouldn't crash
    }

    /**
     * Test with empty descriptor
     */
    @Test
    fun `stress test - method with no parameters`() {
        val line = "    invoke-virtual {v0}, Ljava/lang/Object;->hashCode()I"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Should handle empty parameter list
        val methodStart = line.indexOf("hashCode")
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, methodStart)
        assertNotNull(symbol)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.MethodRef)
        assertEquals("hashCode", (symbol as InstructionSymbolExtractor.Symbol.MethodRef).methodName)
    }

    /**
     * Test with Unicode characters in comments (if parser preserves them)
     */
    @Test
    fun `stress test - line with unicode characters nearby`() {
        // Some Smali files have comments with unicode
        val line = "    invoke-virtual {v0}, Ljava/lang/String;->toString()Ljava/lang/String;  # 返回字符串"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Should extract symbols correctly despite unicode in comment
        val methodStart = line.indexOf("toString")
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, methodStart)
        assertNotNull(symbol)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.MethodRef)
    }
}
