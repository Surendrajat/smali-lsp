package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test directive references using REAL files (not virtual URIs).
 * This tests the complete flow including file reading for cursor-based extraction.
 *
 * Test fixtures live in src/test/resources/directive-refs/ and are always available in CI.
 */
class DirectiveReferencesRealFileTest {

    /** Resolve the directive-refs directory from the test classpath resource. */
    private fun getTestDir(): File {
        val resource = DirectiveReferencesRealFileTest::class.java.classLoader
            .getResource("directive-refs")
            ?: error("directive-refs resource directory not found on classpath")
        return File(resource.toURI())
    }
    @Test
    fun `find references from class declaration includes directive references`() {
        // Setup: Parse all test files
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        
        val testDir = getTestDir()
        assertTrue(testDir.exists() && testDir.isDirectory, "Test directory should exist")
        
        testDir.listFiles()?.filter { it.extension == "smali" }?.forEach { file ->
            val uri = file.toURI().toString()
            val content = file.readText()
            val parsed = parser.parse(uri, content)
            if (parsed != null) {
                index.indexFile(parsed)
            }
        }
        
        val provider = ReferenceProvider(index)
        
        // Test 1: Find references to TestClass (should find .method params/return, .field, const-class)
        val testClassUri = File(getTestDir(), "TestClass.smali").toURI().toString()
        val refs = provider.findReferences(testClassUri, Position(0, 18), true)
        
        println("\n=== TestClass References ===")
        println("Total: ${refs.size} references")
        refs.forEach { 
            println("  ${it.uri.substringAfterLast('/')} line ${it.range.start.line}")
        }
        
        // Should find: declaration, method signature (param + return - counted as one line), field type, const-class
        assertTrue(refs.size >= 4, "Should find at least 4 references to TestClass (found ${refs.size})")
    }
    
    @Test
    fun `find references from super directive click`() {
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        
        val testDir = getTestDir()
        testDir.listFiles()?.filter { it.extension == "smali" }?.forEach { file ->
            val uri = file.toURI().toString()
            val content = file.readText()
            val parsed = parser.parse(uri, content)
            if (parsed != null) {
                index.indexFile(parsed)
            }
        }
        
        val provider = ReferenceProvider(index)
        
        // Test 2: Click on ".super LBaseClass;" in DerivedClass.smali
        val derivedUri = File(getTestDir(), "DerivedClass.smali").toURI().toString()
        // Line 1: .super LBaseClass;
        // Position at character 7-16 (LBaseClass)
        val refs = provider.findReferences(derivedUri, Position(1, 10), true)
        
        println("\n=== BaseClass References (clicked on .super directive) ===")
        println("Total: ${refs.size} references")
        refs.forEach { 
            println("  ${it.uri.substringAfterLast('/')} line ${it.range.start.line}")
        }
        
        // Should find: BaseClass declaration + DerivedClass .super directive
        assertTrue(refs.size >= 2, "Should find BaseClass references including .super (found ${refs.size})")
    }
    
    @Test
    fun `find references from method signature click`() {
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        
        val testDir = getTestDir()
        testDir.listFiles()?.filter { it.extension == "smali" }?.forEach { file ->
            val uri = file.toURI().toString()
            val content = file.readText()
            val parsed = parser.parse(uri, content)
            if (parsed != null) {
                index.indexFile(parsed)
            }
        }
        
        val provider = ReferenceProvider(index)
        
        // Test 3: Click on "LTestClass;" in method parameter/return
        val testClassUri = File(getTestDir(), "TestClass.smali").toURI().toString()
        // Line 3: .method public test(LTestClass;)LTestClass;
        // Position at character 24 (in parameter type)
        val refs = provider.findReferences(testClassUri, Position(3, 24), true)
        
        println("\n=== TestClass References (clicked on method parameter) ===")
        println("Total: ${refs.size} references")
        refs.forEach { 
            println("  ${it.uri.substringAfterLast('/')} line ${it.range.start.line}")
        }
        
        // Should find all TestClass references
        assertTrue(refs.size >= 4, "Should find TestClass references from method signature click (found ${refs.size})")
    }
    
    @Test
    fun `find references from field type click`() {
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        
        val testDir = getTestDir()
        testDir.listFiles()?.filter { it.extension == "smali" }?.forEach { file ->
            val uri = file.toURI().toString()
            val content = file.readText()
            val parsed = parser.parse(uri, content)
            if (parsed != null) {
                index.indexFile(parsed)
            }
        }
        
        val provider = ReferenceProvider(index)
        
        // Test 4: Click on "LTestClass;" in field type
        val testClassUri = File(getTestDir(), "TestClass.smali").toURI().toString()
        // Line 9: .field private myField:LTestClass;
        // Position at character 27 (in type)
        val refs = provider.findReferences(testClassUri, Position(9, 27), true)
        
        println("\n=== TestClass References (clicked on field type) ===")
        println("Total: ${refs.size} references")
        refs.forEach { 
            println("  ${it.uri.substringAfterLast('/')} line ${it.range.start.line}")
        }
        
        // Should find all TestClass references
        assertTrue(refs.size >= 4, "Should find TestClass references from field type click (found ${refs.size})")
    }
}
