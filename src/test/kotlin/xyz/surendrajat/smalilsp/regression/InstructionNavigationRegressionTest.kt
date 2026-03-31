package xyz.surendrajat.smalilsp.regression

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.util.InstructionSymbolExtractor
import xyz.surendrajat.smalilsp.core.Instruction

/**
 * Regression tests for bugs found during user testing.
 * These tests reproduce the exact failures reported by the user.
 * 
 * DO NOT DELETE OR MODIFY WITHOUT CORRESPONDING FIX.
 * Each test documents a real bug that caused production failures.
 */
class InstructionNavigationRegressionTest {

    /**
     * BUG: Wrong highlight for getClassLoader method name
     * Line: invoke-virtual {p0}, Ljava/lang/Class;->getClassLoader()Ljava/lang/ClassLoader;
     * Issue: "getClass" part is different color, "Loader" is different color
     * 
     * Root cause: Symbol extraction was splitting method name incorrectly
     * Expected: Cursor on "getClassLoader" should extract whole method name
     */
    @Test
    fun `bug - getClassLoader method name should be single symbol`() {
        val line = "    invoke-virtual {p0}, Ljava/lang/Class;->getClassLoader()Ljava/lang/ClassLoader;"
        
        // Parse to get instruction
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
        
        // Test that cursor anywhere on "getClassLoader" extracts the full method name
        val methodNameStart = line.indexOf("getClassLoader")
        
        // Cursor at start: 'g'
        var symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, methodNameStart)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.MethodRef)
        assertEquals("getClassLoader", (symbol as InstructionSymbolExtractor.Symbol.MethodRef).methodName)
        
        // Cursor in middle: 'Class' part
        symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, methodNameStart + 8)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.MethodRef)
        assertEquals("getClassLoader", (symbol as InstructionSymbolExtractor.Symbol.MethodRef).methodName)
        
        // Cursor at end: 'r'
        symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, methodNameStart + 13)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.MethodRef)
        assertEquals("getClassLoader", (symbol as InstructionSymbolExtractor.Symbol.MethodRef).methodName)
    }

    /**
     * BUG: Wrong highlight for setExtrasClassLoader method name
     * Line: invoke-virtual {p2, p3}, Landroid/content/Intent;->setExtrasClassLoader(Ljava/lang/ClassLoader;)V
     * Same issue as getClassLoader
     */
    @Test
    fun `bug - setExtrasClassLoader method name should be single symbol`() {
        val line = "    invoke-virtual {p2, p3}, Landroid/content/Intent;->setExtrasClassLoader(Ljava/lang/ClassLoader;)V"
        
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
        
        val methodNameStart = line.indexOf("setExtrasClassLoader")
        
        // Test anywhere on method name extracts full name
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, methodNameStart + 10)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.MethodRef)
        assertEquals("setExtrasClassLoader", (symbol as InstructionSymbolExtractor.Symbol.MethodRef).methodName)
    }

    /**
     * BUG: No hover info for <init>() references
     * Line: invoke-direct {p0}, Ljava/lang/Object;-><init>()V
     * Issue: Cursor on <init> should show method hover, but shows nothing
     */
    @Test
    fun `bug - constructor init should be extractable as method`() {
        val line = "    invoke-direct {p0}, Ljava/lang/Object;-><init>()V"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public constructor <init>()V
                .locals 0
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        val initStart = line.indexOf("<init>")
        
        // Cursor on <init> should extract method reference
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, initStart + 3)
        assertNotNull(symbol, "Should extract symbol for <init>")
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.MethodRef)
        assertEquals("<init>", (symbol as InstructionSymbolExtractor.Symbol.MethodRef).methodName)
        assertEquals("Ljava/lang/Object;", symbol.className)
    }

    /**
     * BUG: Primitive array check-cast extraction
     * Line: check-cast p1, [I
     * Issue: Primitive array types should extract symbol (for hover) but not be navigable
     * Updated: Now extracts ClassRef for hover support, navigation can choose not to navigate
     */
    @Test
    fun `bug - primitive array check-cast should extract class symbol for hover`() {
        val line = "    check-cast p1, [I"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test(Ljava/lang/Object;)V
                .locals 0
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        val arrayTypeStart = line.indexOf("[I")
        
        // Cursor on [I SHOULD extract class symbol (for hover) even though it's primitive array
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, arrayTypeStart)
        assertNotNull(symbol, "Primitive array types should be extracted for hover support")
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.ClassRef, "Should be ClassRef")
        assertEquals("[I", (symbol as InstructionSymbolExtractor.Symbol.ClassRef).className, "Should preserve full array type")
    }

    /**
     * BUG: Object array check-cast shows false diagnostic
     * Line: check-cast p1, [Ljava/lang/Object;
     * Issue: Shows "Class '[Ljava/lang/Object;' not found" but should navigate to Object class
     */
    @Test
    fun `bug - object array check-cast should extract base class`() {
        val line = "    check-cast p1, [Ljava/lang/Object;"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test(Ljava/lang/Object;)V
                .locals 0
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Cursor on the class part (after [) should extract Object class
        val classStart = line.indexOf("Ljava/lang/Object;")
        
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, classStart + 5)
        assertNotNull(symbol, "Object array type should be navigable to base class")
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.ClassRef)
        assertEquals("Ljava/lang/Object;", (symbol as InstructionSymbolExtractor.Symbol.ClassRef).className)
    }

    /**
     * BUG: Cursor on whitespace in instruction should not extract symbol
     * This was working but document to prevent regression
     */
    @Test
    fun `bug - cursor on whitespace should return null`() {
        val line = "    invoke-virtual {p0}, Ljava/lang/Class;->getClassLoader()Ljava/lang/ClassLoader;"
        
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
        
        // Cursor on leading whitespace
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, 0)
        assertNull(symbol, "Whitespace should not extract symbol")
        
        // Cursor between } and ,
        val commaIndex = line.indexOf("},")
        val symbolBetween = InstructionSymbolExtractor.extractSymbol(instruction, line, commaIndex)
        assertNull(symbolBetween, "Whitespace between tokens should not extract symbol")
    }

    /**
     * BUG: Cursor on instruction keyword should not extract symbol
     * Line: invoke-virtual {p0}, ...
     * Cursor on "invoke-virtual" should return null (it's the instruction, not a reference)
     */
    @Test
    fun `bug - cursor on instruction keyword should return null`() {
        val line = "    invoke-virtual {p0}, Ljava/lang/Class;->getClassLoader()Ljava/lang/ClassLoader;"
        
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
        
        // Cursor on "invoke-virtual" keyword
        val keywordStart = line.indexOf("invoke-virtual")
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, keywordStart + 5)
        assertNull(symbol, "Instruction keyword should not be navigable")
    }

    /**
     * BUG: Cursor on register should not extract symbol
     * Line: invoke-virtual {p0}, ...
     * Cursor on "{p0}" should return null
     */
    @Test
    fun `bug - cursor on register should return null`() {
        val line = "    invoke-virtual {p0}, Ljava/lang/Class;->getClassLoader()Ljava/lang/ClassLoader;"
        
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
        
        // Cursor on {p0}
        val registerStart = line.indexOf("{p0}")
        val symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, registerStart + 2)
        assertNull(symbol, "Register should not be navigable")
    }

    /**
     * BUG: Multiple same-type parameters should extract correct one based on cursor
     * Line: invoke-virtual {v0, v1, v2}, Ljava/lang/String;->concat(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
     * Should handle receiver type + 2 params + return type (all String)
     */
    @Test
    fun `bug - duplicate parameter types should extract correct occurrence`() {
        // BUG: When method has multiple parameters of same type like (String, String),
        // and cursor is on second String, extractFromInvoke should find correct occurrence
        // Using method that has 4 occurrences of String: receiver + 2 params + return
        val line = "    invoke-virtual {v0, v1, v2}, Ljava/lang/String;->concat(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 3
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Find all 4 occurrences of Ljava/lang/String;
        val firstStringStart = line.indexOf("Ljava/lang/String;")
        val secondStringStart = line.indexOf("Ljava/lang/String;", firstStringStart + 1)
        val thirdStringStart = line.indexOf("Ljava/lang/String;", secondStringStart + 1)
        val fourthStringStart = line.indexOf("Ljava/lang/String;", thirdStringStart + 1)
        
        // Cursor on receiver type String (before ->)
        var symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, firstStringStart + 5)
        assertNotNull(symbol)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.ClassRef)
        
        // Cursor on first String parameter (different position)
        symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, secondStringStart + 5)
        assertNotNull(symbol)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.ClassRef)
        
        // Cursor on second String parameter (different position)
        symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, thirdStringStart + 5)
        assertNotNull(symbol)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.ClassRef)
        
        // Cursor on return type String (different position)
        symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, fourthStringStart + 5)
        assertNotNull(symbol)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.ClassRef)
    }

    /**
     * BUG: Field access with same class and field type should extract correct symbol
     * Line: iget-object v0, v1, LClass;->field:LClass;
     * If cursor on first LClass (before ->), should extract class
     * If cursor on second LClass (after :), should extract field type
     */
    @Test
    fun `bug - field access with duplicate types should extract correct one`() {
        val line = "    iget-object v0, v1, Ljava/lang/String;->CASE_INSENSITIVE_ORDER:Ljava/lang/String;"
        
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
        
        // Find first occurrence (class name) and second occurrence (field type)
        val firstStringStart = line.indexOf("Ljava/lang/String;")
        val secondStringStart = line.indexOf("Ljava/lang/String;", firstStringStart + 1)
        
        // Cursor on first occurrence (class name before ->)
        var symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, firstStringStart + 5)
        assertNotNull(symbol)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.ClassRef, "First occurrence should be class ref")
        
        // Cursor on second occurrence (field type after :)
        symbol = InstructionSymbolExtractor.extractSymbol(instruction, line, secondStringStart + 5)
        assertNotNull(symbol)
        assertTrue(symbol is InstructionSymbolExtractor.Symbol.ClassRef, "Second occurrence should also be extractable")
    }

    /**
     * EDGE CASE: Very long method name with camelCase
     * Should handle method names of any length correctly
     */
    @Test
    fun `edge case - very long method name extraction`() {
        val longMethodName = "thisIsAVeryLongMethodNameWithManyWordsInCamelCaseThatShouldStillBeExtractedCorrectly"
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
        
        // Test extraction at start, middle, and end of long method name
        val methodStart = line.indexOf(longMethodName)
        
        val symbolStart = InstructionSymbolExtractor.extractSymbol(instruction, line, methodStart)
        assertTrue(symbolStart is InstructionSymbolExtractor.Symbol.MethodRef)
        assertEquals(longMethodName, (symbolStart as InstructionSymbolExtractor.Symbol.MethodRef).methodName)
        
        val symbolMiddle = InstructionSymbolExtractor.extractSymbol(instruction, line, methodStart + 40)
        assertTrue(symbolMiddle is InstructionSymbolExtractor.Symbol.MethodRef)
        assertEquals(longMethodName, (symbolMiddle as InstructionSymbolExtractor.Symbol.MethodRef).methodName)
    }

    /**
     * EDGE CASE: Method with complex descriptor
     * Line: invoke-virtual {v0, v1, v2}, LTest;->complex([[Ljava/lang/String;Ljava/util/List;)[[Ljava/lang/Object;
     * Should handle nested arrays and multiple types
     */
    @Test
    fun `edge case - complex descriptor with nested arrays`() {
        val line = "    invoke-virtual {v0, v1, v2}, LTest;->complex([[Ljava/lang/String;Ljava/util/List;)[[Ljava/lang/Object;"
        
        val smaliCode = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 3
                $line
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val file = parser.parse("test.smali", smaliCode)!!
        val instruction = file.methods[0].instructions[0]
        
        // Cursor on String in parameter
        val stringStart = line.indexOf("Ljava/lang/String;")
        val symbolString = InstructionSymbolExtractor.extractSymbol(instruction, line, stringStart + 5)
        assertNotNull(symbolString)
        assertTrue(symbolString is InstructionSymbolExtractor.Symbol.ClassRef)
        assertEquals("Ljava/lang/String;", (symbolString as InstructionSymbolExtractor.Symbol.ClassRef).className)
        
        // Cursor on List in parameter
        val listStart = line.indexOf("Ljava/util/List;")
        val symbolList = InstructionSymbolExtractor.extractSymbol(instruction, line, listStart + 5)
        assertNotNull(symbolList)
        assertTrue(symbolList is InstructionSymbolExtractor.Symbol.ClassRef)
        assertEquals("Ljava/util/List;", (symbolList as InstructionSymbolExtractor.Symbol.ClassRef).className)
        
        // Cursor on Object in return type
        val objectStart = line.indexOf("Ljava/lang/Object;")
        val symbolObject = InstructionSymbolExtractor.extractSymbol(instruction, line, objectStart + 5)
        assertNotNull(symbolObject)
        assertTrue(symbolObject is InstructionSymbolExtractor.Symbol.ClassRef)
        assertEquals("Ljava/lang/Object;", (symbolObject as InstructionSymbolExtractor.Symbol.ClassRef).className)
    }
}
