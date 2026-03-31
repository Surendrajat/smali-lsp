package xyz.surendrajat.smalilsp

import org.eclipse.lsp4j.Position
import xyz.surendrajat.smalilsp.TestUtils
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Diagnose the new issues reported in user-testing.md:
 * 
 * Issue 7: Goto def works on method declarations (should not)
 * Issue 8: Duplicates in definitions
 * Issue 5: Find references is slow (multi-seconds)
 */
@Timeout(15, unit = TimeUnit.MINUTES)
class NewIssuesDiagnosticTest {
    
    @Test
    fun `issue 7 - goto def on method declaration should only work on method name`() {
        println("\n=== ISSUE 7: Goto Def on Method Declaration ===\n")
        
        val code = """
            .class public LTestClass;
            .super Ljava/lang/Object;
            
            .method public testMethod(Ljava/lang/String;)V
                .locals 1
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val workspaceIndex = WorkspaceIndex()
        val uri = "file:///test.smali"
        
        val file = parser.parse(uri, code)
        if (file != null) {
            workspaceIndex.indexFile(file)
        }
        
        val definitionProvider = DefinitionProvider(workspaceIndex)
        
        val lines = code.lines()
        val methodLine = lines.indexOfFirst { it.contains(".method public testMethod") }
        
        println("Method declaration line $methodLine: ${lines[methodLine]}")
        
        // Test various positions on the method declaration line
        val testPositions = listOf(
            Pair(0, ".method keyword"),
            Pair(8, "public modifier"),
            Pair(15, "method name 'testMethod'"),
            Pair(25, "method name end"),
            Pair(26, "opening paren"),
            Pair(27, "parameter type start"),
            Pair(43, "closing paren"),
            Pair(44, "return type")
        )
        
        println("\nTesting definition at various positions:")
        for ((charPos, description) in testPositions) {
            val position = Position(methodLine, charPos)
            val definitions = definitionProvider.findDefinition(uri, position)
            
            println("  Char $charPos ($description): ${definitions.size} definitions")
            if (definitions.isNotEmpty()) {
                definitions.forEach { def ->
                    println("    -> ${def.uri} at line ${def.range.start.line}")
                }
            }
        }
        
        println("\n**Expected**: Only 'method name' position should have definition (to itself)")
        println("**Expected**: Other positions (keyword, modifier, params, return) should have 0 or navigate to TYPES")
    }
    
    @Test
    fun `issue 8 - check for duplicate definitions`() {
        println("\n=== ISSUE 8: Duplicate Definitions ===\n")
        
        val code = """
            .class public LTestClass;
            .super Ljava/lang/Object;
            
            .method public testMethod()V
                .locals 1
                
                invoke-virtual {p0}, LTestClass;->testMethod()V
                
                return-void
            .end method
        """.trimIndent()
        
        val parser = SmaliParser()
        val workspaceIndex = WorkspaceIndex()
        val uri = "file:///test.smali"
        
        val file = parser.parse(uri, code)
        if (file != null) {
            workspaceIndex.indexFile(file)
        }
        
        val definitionProvider = DefinitionProvider(workspaceIndex)
        
        val lines = code.lines()
        
        // Test 1: Goto def on class name in .class directive
        val classLine = lines.indexOfFirst { it.contains(".class public") }
        val classNamePos = lines[classLine].indexOf("LTestClass;") + 1
        val classPosition = Position(classLine, classNamePos)
        
        println("Test 1: Class definition on .class line")
        println("  Line: ${lines[classLine]}")
        println("  Position: char $classNamePos")
        
        val classDefs = definitionProvider.findDefinition(uri, classPosition)
        println("  Definitions found: ${classDefs.size}")
        classDefs.forEach { def ->
            println("    -> ${def.uri} at line ${def.range.start.line}")
        }
        if (classDefs.size > 1) {
            println("  ❌ DUPLICATE! Should be 1 definition, found ${classDefs.size}")
        }
        
        // Test 2: Goto def on class reference in invoke
        val invokeLine = lines.indexOfFirst { it.contains("invoke-virtual") }
        val invokeClassPos = lines[invokeLine].indexOf("LTestClass;") + 1
        val invokeClassPosition = Position(invokeLine, invokeClassPos)
        
        println("\nTest 2: Class definition on invoke line")
        println("  Line: ${lines[invokeLine]}")
        println("  Position: char $invokeClassPos")
        
        val invokeClassDefs = definitionProvider.findDefinition(uri, invokeClassPosition)
        println("  Definitions found: ${invokeClassDefs.size}")
        invokeClassDefs.forEach { def ->
            println("    -> ${def.uri} at line ${def.range.start.line}")
        }
        if (invokeClassDefs.size > 1) {
            println("  ❌ DUPLICATE! Should be 1 definition, found ${invokeClassDefs.size}")
        }
        
        // Test 3: Goto def on method name in invoke
        val invokeMethodPos = lines[invokeLine].indexOf("testMethod") + 1
        val invokeMethodPosition = Position(invokeLine, invokeMethodPos)
        
        println("\nTest 3: Method definition on invoke line")
        println("  Line: ${lines[invokeLine]}")
        println("  Position: char $invokeMethodPos")
        
        val invokeMethodDefs = definitionProvider.findDefinition(uri, invokeMethodPosition)
        println("  Definitions found: ${invokeMethodDefs.size}")
        invokeMethodDefs.forEach { def ->
            println("    -> ${def.uri} at line ${def.range.start.line}")
        }
        if (invokeMethodDefs.size > 1) {
            println("  ❌ DUPLICATE! Should be 1 definition, found ${invokeMethodDefs.size}")
        }
    }
    
    @Test
    fun `issue 5 - find references performance regression`() {
        println("\n=== ISSUE 5: Find References Performance ===\n")
        
        val apkDir = TestUtils.getProtonMailApk() ?: return
        if (!apkDir.exists()) {
            println("ProtonMail APK not found, skipping test")
            return
        }
        
        println("Indexing ProtonMail APK...")
        val startIndex = System.currentTimeMillis()
        
        val parser = SmaliParser()
        val workspaceIndex = WorkspaceIndex()
        
        val files = apkDir.walkTopDown()
            .filter { it.extension == "smali" }
            .take(1000)  // Test with first 1000 files
            .toList()
        
        files.forEach { file ->
            try {
                val content = file.readText()
                val uri = file.toURI().toString()
                val smaliFile = parser.parse(uri, content)
                if (smaliFile != null) {
                    workspaceIndex.indexFile(smaliFile)
                }
            } catch (e: Exception) {
                // Continue
            }
        }
        
        val indexTime = System.currentTimeMillis() - startIndex
        println("Indexed ${files.size} files in ${indexTime}ms (${files.size * 1000 / indexTime} files/sec)")
        
        val referenceProvider = ReferenceProvider(workspaceIndex)
        
        // Test find references on 10 random classes
        println("\nTesting find references performance on 10 samples:")
        
        val testSamples = files.take(10)
        val timings = mutableListOf<Long>()
        
        for ((idx, file) in testSamples.withIndex()) {
            val lines = file.readLines()
            val classLine = lines.indexOfFirst { it.contains(".class") }
            if (classLine < 0) continue
            
            val classMatch = Regex("""(L[a-zA-Z0-9/\$]+;)""").find(lines[classLine])
            if (classMatch == null) continue
            
            val className = classMatch.value
            val position = Position(classLine, classMatch.range.first)
            val uri = file.toURI().toString()
            
            val start = System.currentTimeMillis()
            val refs = referenceProvider.findReferences(uri, position, true)
            val duration = System.currentTimeMillis() - start
            
            timings.add(duration)
            
            println("  ${idx + 1}. $className: ${refs.size} refs in ${duration}ms")
            
            if (duration > 1000) {
                println("    ⚠️ SLOW! Over 1 second")
            }
        }
        
        if (timings.isNotEmpty()) {
            val avgTime = timings.average()
            val maxTime = timings.maxOrNull() ?: 0
            
            println("\nPerformance Summary:")
            println("  Average: ${avgTime.toInt()}ms")
            println("  Max: ${maxTime}ms")
            println("  Samples over 100ms: ${timings.count { it > 100 }}")
            println("  Samples over 1000ms: ${timings.count { it > 1000 }}")
            
            if (avgTime > 100) {
                println("\n❌ REGRESSION! Average should be <100ms, found ${avgTime.toInt()}ms")
            }
            if (maxTime > 1000) {
                println("❌ REGRESSION! Max should be <1000ms, found ${maxTime}ms")
            }
        }
    }
}
