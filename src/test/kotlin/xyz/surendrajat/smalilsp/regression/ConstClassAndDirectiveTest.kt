package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test const-class and find references on directives.
 */
class ConstClassAndDirectiveTest {
    
    @Test
    fun `const-class hover should work`() {
        val code = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                const-class v0, Ljava/lang/String;
                return-void
            .end method
        """.trimIndent()
        
        // Write to temp file so HoverProvider can read line content
        val tempFile = java.io.File.createTempFile("test", ".smali")
        tempFile.writeText(code)
        tempFile.deleteOnExit()
        val uri = tempFile.toURI().toString()
        
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        val file = parser.parse(uri, code)
        assertNotNull(file)
        index.indexFile(file)
        
        val provider = HoverProvider(index)
        
        // Line 4 is: const-class v0, Ljava/lang/String;
        // Position at "Ljava/lang/String;"
        val hover = provider.provideHover(uri, Position(4, 23))
        
        println("const-class hover result: ${if (hover != null) "FOUND" else "NULL"}")
        assertNotNull(hover, "Hover should work on const-class class reference")
    }
    
    @Test
    fun `const-class definition should work`() {
        val code = """
            .class public LTestClass;
            .super Ljava/lang/Object;
            
            .method public test()V
                const-class v0, LTestClass;
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        val file = parser.parse("file:///test.smali", code)
        assertNotNull(file)
        index.indexFile(file)
        
        val provider = DefinitionProvider(index)
        
        // Line 4 is: const-class v0, LTestClass;
        // Position at "LTestClass"
        val defs = provider.findDefinition("file:///test.smali", Position(4, 23))
        
        println("const-class definition result: ${defs.size} definitions")
        assertTrue(defs.isNotEmpty(), "Definition should work on const-class class reference")
    }
    
    @Test
    fun `const-class find references should work`() {
        val code = """
            .class public LTestClass;
            .super Ljava/lang/Object;
            
            .method public test()V
                const-class v0, LTestClass;
                const-class v1, LTestClass;
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        val file = parser.parse("file:///test.smali", code)
        assertNotNull(file)
        index.indexFile(file)
        
        val provider = ReferenceProvider(index)
        
        // Find references from the class declaration
        val refs = provider.findReferences("file:///test.smali", Position(0, 18), true)
        
        println("const-class find references result: ${refs.size} references")
        // Should find 2 const-class references
        assertTrue(refs.size >= 2, "Should find const-class references (found ${refs.size})")
    }
    
    @Test
    fun `find references on method directive class reference`() {
        val testClassCode = """
            .class public LTestClass;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val otherClassCode = """
            .class public LOtherClass;
            .super Ljava/lang/Object;
            
            .method public test(LTestClass;)LTestClass;
                .locals 1
                return-object p1
            .end method
            
            .field private myField:LTestClass;
        """.trimIndent()
        
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        
        // Parse and index both files separately
        val testClassFile = parser.parse("file:///TestClass.smali", testClassCode)
        val otherClassFile = parser.parse("file:///OtherClass.smali", otherClassCode)
        assertNotNull(testClassFile)
        assertNotNull(otherClassFile)
        index.indexFile(testClassFile)
        index.indexFile(otherClassFile)
        
        val provider = ReferenceProvider(index)
        
        // Find references from TestClass declaration (line 0, char 18 = "LTestClass")
        val refs = provider.findReferences("file:///TestClass.smali", Position(0, 18), true)
        
        println("Directive find references result: ${refs.size} references")
        println("References:")
        refs.forEach { println("  Line ${it.range.start.line}: ${it.uri}") }
        
        // Should find:
        // - Line 2: method with parameter type LTestClass; and return type LTestClass;
        // - Line 7: field type LTestClass;
        // Note: May find method line twice (once for param, once for return) or field line
        assertTrue(refs.size >= 2, "Should find references in .method and .field directives (found ${refs.size})")
    }
    
    @Test
    fun `find references on super directive`() {
        val baseCode = """
            .class public LBaseClass;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val derivedCode = """
            .class public LDerivedClass;
            .super LBaseClass;
        """.trimIndent()
        
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        
        // Parse and index both files separately (as they would be in real world)
        val baseFile = parser.parse("file:///BaseClass.smali", baseCode)
        val derivedFile = parser.parse("file:///DerivedClass.smali", derivedCode)
        assertNotNull(baseFile)
        assertNotNull(derivedFile)
        index.indexFile(baseFile)
        index.indexFile(derivedFile)
        
        val provider = ReferenceProvider(index)
        
        // Find references from BaseClass declaration (line 0, char 18 = "LBaseClass")
        val refs = provider.findReferences("file:///BaseClass.smali", Position(0, 18), true)
        
        println("Super directive find references result: ${refs.size} references")
        // Should find .super LBaseClass; reference in DerivedClass.smali
        assertTrue(refs.size >= 1, "Should find reference in .super directive (found ${refs.size})")
    }
    
    @Test
    fun `find references on interface directive`() {
        val interfaceCode = """
            .class public interface LMyInterface;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val implementorCode = """
            .class public LImplementor;
            .super Ljava/lang/Object;
            .implements LMyInterface;
        """.trimIndent()
        
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        
        // Parse and index both files separately
        val interfaceFile = parser.parse("file:///MyInterface.smali", interfaceCode)
        val implementorFile = parser.parse("file:///Implementor.smali", implementorCode)
        assertNotNull(interfaceFile)
        assertNotNull(implementorFile)
        index.indexFile(interfaceFile)
        index.indexFile(implementorFile)
        
        val provider = ReferenceProvider(index)
        
        // Find references from MyInterface declaration (line 0, char 28 = "LMyInterface")
        val refs = provider.findReferences("file:///MyInterface.smali", Position(0, 28), true)
        
        println("Interface directive find references result: ${refs.size} references")
        // Should find .implements LMyInterface; reference in Implementor.smali
        assertTrue(refs.size >= 1, "Should find reference in .implements directive (found ${refs.size})")
    }
}
