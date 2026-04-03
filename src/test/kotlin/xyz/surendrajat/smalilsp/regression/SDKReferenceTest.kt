package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.shared.TempTestWorkspace
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import kotlin.test.assertTrue

/**
 * Test SDK class method/field references
 */
class SDKReferenceTest {
    
    @Test
    fun `find references - SDK class method calls`() {
        val workspace = TempTestWorkspace.create()
        
        // Create custom class that calls SDK method
        workspace.addFile("MyClass.smali", """
            .class public LMyClass;
            .super Ljava/lang/Object;
            
            .method public test()V
                const-string v0, "Hello"
                invoke-virtual {v0}, Ljava/lang/String;->length()I
                move-result v1
                
                const-string v2, "World"
                invoke-virtual {v2}, Ljava/lang/String;->length()I
                move-result v3
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val uri = workspace.getUri("MyClass.smali")
        
        val file = index.findFileByUri(uri)!!
        val testMethod = file.methods.find { it.name == "test" }!!
        val firstInvoke = testMethod.instructions[1]  // First String.length() call
        
        // Click on the method name in the invoke instruction
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[firstInvoke.range.start.line]
        val methodNamePos = line.indexOf("->length")
        require(methodNamePos >= 0) { "Method name not found in line: $line" }
        
        val clickChar = methodNamePos + 3
        val refs = provider.findReferences(uri, Position(firstInvoke.range.start.line, clickChar), true)
        
        println("=== SDK Method References Test ===")
        println("Method: String.length()")
        println("Found ${refs.size} references:")
        refs.forEach { 
            println("  Line ${it.range.start.line}: ${it.uri.substringAfterLast("/")}")
        }
        
        // Should find 2 call sites (both String.length() calls)
        // Note: SDK classes are NOT in the workspace, so we won't find the definition
        assertTrue(refs.size >= 2, "Expected at least 2 call sites, found ${refs.size}")
        workspace.cleanup()
    }
    
    @Test
    fun `find references - SDK class field accesses`() {
        val workspace = TempTestWorkspace.create()
        
        workspace.addFile("MyClass.smali", """
            .class public LMyClass;
            .super Ljava/lang/Object;
            
            .method public test()V
                sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
                const-string v1, "Hello"
                invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
                
                sget-object v2, Ljava/lang/System;->out:Ljava/io/PrintStream;
                const-string v3, "World"
                invoke-virtual {v2, v3}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val uri = workspace.getUri("MyClass.smali")
        
        val file = index.findFileByUri(uri)!!
        val testMethod = file.methods.find { it.name == "test" }!!
        val firstSget = testMethod.instructions[0]  // First System.out access
        
        // Click on the field name
        val lines = java.io.File(java.net.URI(uri)).readLines()
        val line = lines[firstSget.range.start.line]
        val fieldNamePos = line.indexOf("->out")
        require(fieldNamePos >= 0) { "Field name not found in line: $line" }
        
        val clickChar = fieldNamePos + 3
        val refs = provider.findReferences(uri, Position(firstSget.range.start.line, clickChar), true)
        
        println("=== SDK Field References Test ===")
        println("Field: System.out")
        println("Found ${refs.size} references:")
        refs.forEach { 
            println("  Line ${it.range.start.line}: ${it.uri.substringAfterLast("/")}")
        }
        
        // Should find 2 access sites (both System.out accesses)
        assertTrue(refs.size >= 2, "Expected at least 2 accesses, found ${refs.size}")
        workspace.cleanup()
    }
    
    @Test
    fun `find references - mixed SDK and custom class`() {
        val workspace = TempTestWorkspace.create()
        
        // Create custom class with a method
        workspace.addFile("Utils.smali", """
            .class public LUtils;
            .super Ljava/lang/Object;
            
            .method public static helper()V
                return-void
            .end method
        """.trimIndent())
        
        // Create class that calls both SDK and custom methods
        workspace.addFile("Main.smali", """
            .class public LMain;
            .super Ljava/lang/Object;
            
            .method public test()V
                invoke-static {}, LUtils;->helper()V
                invoke-static {}, LUtils;->helper()V
                
                const-string v0, "test"
                invoke-virtual {v0}, Ljava/lang/String;->length()I
                move-result v1
                
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val mainUri = workspace.getUri("Main.smali")
        
        val file = index.findFileByUri(mainUri)!!
        val testMethod = file.methods.find { it.name == "test" }!!
        
        println("=== Test has ${testMethod.instructions.size} instructions ===")
        
        // Test custom class method (should find definition + 2 calls)
        val firstUtilsCall = testMethod.instructions[0]
        val lines1 = java.io.File(java.net.URI(mainUri)).readLines()
        val line1 = lines1[firstUtilsCall.range.start.line]
        val helperPos = line1.indexOf("->helper")
        require(helperPos >= 0) { "helper method not found" }
        
        val refs1 = provider.findReferences(mainUri, Position(firstUtilsCall.range.start.line, helperPos + 3), true)
        println("=== Custom Method References ===")
        println("Found ${refs1.size} references (expected: 3 = 1 def + 2 calls)")
        assertTrue(refs1.size >= 3, "Expected at least 3 (1 def + 2 calls), found ${refs1.size}")
        
        // Test SDK class method (should find 1 call, no definition)
        // Find the invoke-virtual instruction for String.length()
        val stringLengthCall = testMethod.instructions.find { 
            it is xyz.surendrajat.smalilsp.core.InvokeInstruction && 
            it.methodName == "length"
        }
        require(stringLengthCall != null) { "String.length() call not found" }
        
        val lines2 = java.io.File(java.net.URI(mainUri)).readLines()
        val line2 = lines2[stringLengthCall.range.start.line]
        val lengthPos = line2.indexOf("->length")
        require(lengthPos >= 0) { "length method not found" }
        
        val refs2 = provider.findReferences(mainUri, Position(stringLengthCall.range.start.line, lengthPos + 3), true)
        println("=== SDK Method References ===")
        println("Found ${refs2.size} references (expected: 1 = 1 call, no def)")
        assertTrue(refs2.size >= 1, "Expected at least 1 call site, found ${refs2.size}")
        
        workspace.cleanup()
    }
}
