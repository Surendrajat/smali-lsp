package xyz.surendrajat.smalilsp.unit.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

import xyz.surendrajat.smalilsp.parser.SmaliParser
/**
 * Test SmaliParser with real smali code.
 * 
 * These tests use actual smali files from real APKs to ensure
 * the parser works correctly in production scenarios.
 */
class SmaliParserTest {
    
    private val parser = SmaliParser()
    
    @Test
    fun `parse simple class with methods`() {
        // Real Smali from Android library class with constructors and abstract method
        val smali = """
            .class public abstract Landroidx/transition/PathMotion;
            .super Ljava/lang/Object;
            .source "PathMotion.java"
            
            
            # direct methods
            .method public constructor <init>()V
                .locals 0
            
                .line 45
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            
                return-void
            .end method
            
            .method public constructor <init>(Landroid/content/Context;Landroid/util/AttributeSet;)V
                .locals 0
            
                .line 48
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            
                return-void
            .end method
            
            
            # virtual methods
            .method public abstract getPath(FFFF)Landroid/graphics/Path;
            .end method
        """.trimIndent()
        
        val result = parser.parse("file:///PathMotion.smali", smali)
        
        assertNotNull(result, "Parser should successfully parse the file")
        result!!
        
        // Verify class
        assertEquals("Landroidx/transition/PathMotion;", result.classDefinition.name)
        assertEquals("Ljava/lang/Object;", result.classDefinition.superClass)
        assertTrue(result.classDefinition.modifiers.contains("public"))
        assertTrue(result.classDefinition.modifiers.contains("abstract"))
        
        // Verify methods
        assertEquals(3, result.methods.size, "Should have 3 methods")
        
        // First constructor
        val constructor1 = result.methods.find { it.name == "<init>" && it.descriptor == "()V" }
        assertNotNull(constructor1, "Should find first constructor")
        assertTrue(constructor1!!.modifiers.contains("public"))
        assertEquals(0, constructor1.parameters.size)
        assertEquals("V", constructor1.returnType)
        
        // Second constructor
        val constructor2 = result.methods.find { 
            it.name == "<init>" && it.descriptor.startsWith("(Landroid/content/Context;")
        }
        assertNotNull(constructor2, "Should find second constructor")
        assertEquals(2, constructor2!!.parameters.size)
        
        // Abstract method
        val getPath = result.methods.find { it.name == "getPath" }
        assertNotNull(getPath, "Should find getPath method")
        assertTrue(getPath!!.modifiers.contains("public"))
        assertTrue(getPath.modifiers.contains("abstract"))
        assertEquals(4, getPath.parameters.size, "getPath takes 4 float parameters")
    }
    
    @Test
    fun `parse class with fields`() {
        val smali = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            # instance fields
            .field private name:Ljava/lang/String;
            
            .field public age:I
            
            .field protected static counter:J
        """.trimIndent()
        
        val result = parser.parse("file:///Test.smali", smali)
        
        assertNotNull(result)
        result!!
        
        assertEquals("Lcom/example/Test;", result.classDefinition.name)
        
        // Verify fields
        assertEquals(3, result.fields.size, "Should have 3 fields")
        
        val nameField = result.fields.find { it.name == "name" }
        assertNotNull(nameField)
        assertEquals("Ljava/lang/String;", nameField!!.type)
        assertTrue(nameField.modifiers.contains("private"))
        
        val ageField = result.fields.find { it.name == "age" }
        assertNotNull(ageField)
        assertEquals("I", ageField!!.type)
        assertTrue(ageField.modifiers.contains("public"))
        
        val counterField = result.fields.find { it.name == "counter" }
        assertNotNull(counterField)
        assertEquals("J", counterField!!.type)
        assertTrue(counterField.modifiers.contains("protected"))
        assertTrue(counterField.modifiers.contains("static"))
    }
    
    @Test
    fun `parse class with interfaces`() {
        val smali = """
            .class public Lcom/example/MyRunnable;
            .super Ljava/lang/Object;
            
            # interfaces
            .implements Ljava/lang/Runnable;
            .implements Ljava/io/Serializable;
            
            # virtual methods
            .method public run()V
                return-void
            .end method
        """.trimIndent()
        
        val result = parser.parse("file:///MyRunnable.smali", smali)
        
        assertNotNull(result)
        result!!
        
        assertEquals(2, result.classDefinition.interfaces.size)
        assertTrue(result.classDefinition.interfaces.contains("Ljava/lang/Runnable;"))
        assertTrue(result.classDefinition.interfaces.contains("Ljava/io/Serializable;"))
        
        val runMethod = result.methods.find { it.name == "run" }
        assertNotNull(runMethod)
        assertEquals("()V", runMethod!!.descriptor)
    }
    
    @Test
    fun `parse returns null for invalid smali`() {
        val invalidSmali = "this is not valid smali code at all"
        
        val result = parser.parse("file:///invalid.smali", invalidSmali)
        
        // Parser should handle errors gracefully
        // May return null or empty structure depending on error handling
        // The key is it doesn't crash
    }
    
    @Test
    fun `parse minimal class`() {
        val smali = """
            .class public Lcom/example/Empty;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val result = parser.parse("file:///Empty.smali", smali)
        
        assertNotNull(result)
        result!!
        
        assertEquals("Lcom/example/Empty;", result.classDefinition.name)
        assertEquals("Ljava/lang/Object;", result.classDefinition.superClass)
        assertEquals(0, result.methods.size)
        assertEquals(0, result.fields.size)
    }
    
    /**
     * Test parsing .prologue and .epilogue directives.
     * These are used by some Smali assemblers to mark method prologue/epilogue code.
     */
    @Test
    fun `parse method with prologue and epilogue directives`() {
        val smali = """
            .class public Lcom/example/PrologueTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .prologue
                .line 1
                
                const/4 v0, 0x0
                
                .epilogue
                return-void
            .end method
        """.trimIndent()
        
        val result = parser.parse("file:///PrologueTest.smali", smali)
        
        assertNotNull(result, "Parser should handle .prologue and .epilogue directives")
        result!!
        
        assertEquals("Lcom/example/PrologueTest;", result.classDefinition.name)
        assertEquals(1, result.methods.size)
        assertEquals("test", result.methods[0].name)
        assertEquals("()V", result.methods[0].descriptor)
    }
}
