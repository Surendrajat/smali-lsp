package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for find references false positive bug.
 * 
 * Issue: Find refs on SDK class methods (e.g., Ljava/lang/Enum;-><init>(Ljava/lang/String;I)V)
 * incorrectly matches unrelated classes with same method signature 
 * (e.g., Lcom/example/MyClass;-><init>(Ljava/lang/String;I)V).
 * 
 * Root Cause: When finding references to SDK class methods, the code can't determine
 * subclasses (not in index), so it should ONLY match exact class name. But currently
 * it's matching all methods with same name+descriptor regardless of class.
 */
class FindRefsFalsePositiveTest {
    
    @Test
    fun `find refs on SDK Enum init should not match unrelated class with same signature`() {
        // Create temp directory
        val tempDir = Files.createTempDirectory("smali-lsp-test").toFile()
        
        try {
            // File 1: Calls Enum.<init>
            val file1Content = """
                .class public Lcom/example/ClassA;
                .super Ljava/lang/Object;
                
                .method public test()V
                    .locals 2
                    invoke-direct {v0, v1, v2}, Ljava/lang/Enum;-><init>(Ljava/lang/String;I)V
                    return-void
                .end method
            """.trimIndent()
            
            // File 2: Calls unrelated class with same init signature
            val file2Content = """
                .class public Lcom/example/ClassB;
                .super Ljava/lang/Object;
                
                .method public test()V
                    .locals 2
                    invoke-direct {v0, v1, v2}, Lcom/sun/jna/ELFAnalyser${'$'}ArmAeabiAttributesTag${'$'}ParameterType;-><init>(Ljava/lang/String;I)V
                    return-void
                .end method
            """.trimIndent()
            
            // File 3: Actual Enum subclass (should match)
            val file3Content = """
                .class public final enum Lcom/example/MyEnum;
                .super Ljava/lang/Enum;
                
                .method private constructor <init>(Ljava/lang/String;I)V
                    .locals 0
                    invoke-direct {p0, p1, p2}, Ljava/lang/Enum;-><init>(Ljava/lang/String;I)V
                    return-void
                .end method
            """.trimIndent()
            
            // Write files
            File(tempDir, "ClassA.smali").writeText(file1Content)
            File(tempDir, "ClassB.smali").writeText(file2Content)
            File(tempDir, "MyEnum.smali").writeText(file3Content)
            
            // Index files
            val parser = SmaliParser()
            val index = WorkspaceIndex()
            
            tempDir.listFiles()?.filter { it.extension == "smali" }?.forEach { file ->
                val uri = file.toURI().toString()
                val content = file.readText()
                val parsed = parser.parse(uri, content)
                if (parsed != null) {
                    index.indexFile(parsed)
                }
            }
            
            val provider = ReferenceProvider(index)
            
            // Click on Enum.<init> in file1, line 5
            // Find the position of "<init>" in the line
            val file1Lines = File(tempDir, "ClassA.smali").readLines()
            val enumInitLine = file1Lines[5]
            val initPos = enumInitLine.indexOf("<init>") + 2 // Click in middle of "<init>"
            val file1Uri = File(tempDir, "ClassA.smali").toURI().toString()
            val refs = provider.findReferences(file1Uri, Position(5, initPos), false)
            
            // With new SDK class behavior: Only find DIRECT calls (not polymorphic subclass calls)
            // Should find:
            // 1. invoke-direct {}, Ljava/lang/Enum;-><init>(...) in ClassA.test() (line 5 of file1)
            // 2. invoke-direct {p0, p1, p2}, Ljava/lang/Enum;-><init>(...) in MyEnum.<init>() (line 5 of file3)
            //    - This is MyEnum constructor calling super(), which directly invokes Ljava/lang/Enum;-><init>
            //    - Acceptable: These super() calls are boilerplate but still valid direct calls
            // Should NOT find:
            // 3. invoke-direct {}, ParameterType;-><init>(...) in ClassB.test() (line 5 of file2) 
            //    - This calls ParameterType.<init>, NOT Enum.<init>, so different class!
            
            // Get the locations as strings for easier assertion
            val refStrings = refs.map { loc ->
                val uri = loc.uri
                val fileName = uri.substringAfterLast("/")
                val line = loc.range.start.line
                "$fileName:$line"
            }.sorted()
            
            println("Found ${refs.size} references:")
            refStrings.forEach { println("  $it") }
            
            // Assert exactly 2 references (ClassA + MyEnum super(), NOT ClassB)
            // Note: MyEnum super() call is acceptable - it does invoke Ljava/lang/Enum;-><init> directly
            assertEquals(2, refs.size, "Expected exactly 2 references (ClassA + MyEnum super()), but got ${refs.size}")
            
            assertTrue(
                refStrings.any { it.contains("ClassA.smali") },
                "Should include direct reference from ClassA calling Enum.<init>"
            )
            assertTrue(
                refStrings.any { it.contains("MyEnum.smali") },
                "Should include MyEnum constructor calling super() - acceptable direct call"
            )
            assertTrue(
                refStrings.none { it.contains("ClassB.smali") },
                "Should NOT include reference from ClassB (calls ParameterType.<init>, not Enum.<init>)"
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun `find refs on workspace class method should not match unrelated class with same signature`() {
        println("\n\n===== STARTING TEST 2 =====\n")
        val tempDir = Files.createTempDirectory("smali-lsp-test").toFile()
        
        try {
            // Base class
            val baseContent = """
                .class public Lcom/example/Base;
                .super Ljava/lang/Object;
                
                .method public doWork(Ljava/lang/String;I)V
                    .locals 0
                    return-void
                .end method
            """.trimIndent()
            
            // Subclass that extends Base
            val subclassContent = """
                .class public Lcom/example/Subclass;
                .super Lcom/example/Base;
                
                .method public test()V
                    .locals 2
                    invoke-virtual {v0, v1, v2}, Lcom/example/Base;->doWork(Ljava/lang/String;I)V
                    return-void
                .end method
            """.trimIndent()
            
            // Unrelated class with same method signature
            val unrelatedContent = """
                .class public Lcom/example/Unrelated;
                .super Ljava/lang/Object;
                
                .method public doWork(Ljava/lang/String;I)V
                    .locals 0
                    return-void
                .end method
                
                .method public test()V
                    .locals 2
                    invoke-virtual {v0, v1, v2}, Lcom/example/Unrelated;->doWork(Ljava/lang/String;I)V
                    return-void
                .end method
            """.trimIndent()
            
            // Write files
            File(tempDir, "Base.smali").writeText(baseContent)
            File(tempDir, "Subclass.smali").writeText(subclassContent)
            File(tempDir, "Unrelated.smali").writeText(unrelatedContent)
            
            // Index files
            val parser = SmaliParser()
            val index = WorkspaceIndex()
            
            tempDir.listFiles()?.filter { it.extension == "smali" }?.forEach { file ->
                val uri = file.toURI().toString()
                val content = file.readText()
                val parsed = parser.parse(uri, content)
                if (parsed != null) {
                    index.indexFile(parsed)
                }
            }
            
            val provider = ReferenceProvider(index)
            
            // Click on Base.doWork in Subclass.test(), line 5
            // Find the position of "doWork" in the line
            val subclassLines = File(tempDir, "Subclass.smali").readLines()
            val invokeLine = subclassLines[5]
            val doWorkPos = invokeLine.indexOf("doWork") + 2 // Click in middle of "doWork"
            val subclassUri = File(tempDir, "Subclass.smali").toURI().toString()
            val refs = provider.findReferences(subclassUri, Position(5, doWorkPos), true)
            
            // Should find:
            // 1. Base.doWork definition (line 3 of Base.smali)
            // 2. invoke-virtual in Subclass.test() (line 5 of Subclass.smali)
            // Should NOT find:
            // 3. Unrelated.doWork definition or calls
            
            val refStrings = refs.map { loc ->
                val uri = loc.uri
                val fileName = uri.substringAfterLast("/")
                val line = loc.range.start.line
                "$fileName:$line"
            }.sorted()
            
            // Assert exactly 2 references
            assertEquals(2, refs.size, "Expected exactly 2 references (Base definition + Subclass call), but got ${refs.size}")
            
            assertTrue(
                refStrings.any { it.contains("Base.smali") },
                "Should include Base.doWork definition"
            )
            assertTrue(
                refStrings.any { it.contains("Subclass.smali") },
                "Should include call from Subclass"
            )
            assertTrue(
                refStrings.none { it.contains("Unrelated.smali") },
                "Should NOT include Unrelated.doWork (different class with same signature)"
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun `find refs when clicking on parameter type should find method refs not class refs`() {
        val tempDir = Files.createTempDirectory("smali-lsp-test").toFile()
        
        try {
            // File with method call that has Ljava/lang/String; parameter
            val fileContent = """
                .class public Lcom/example/TestClass;
                .super Ljava/lang/Object;
                
                .method public doWork(Ljava/lang/String;I)V
                    .locals 0
                    return-void
                .end method
                
                .method public caller1()V
                    .locals 2
                    invoke-virtual {v0, v1, v2}, Lcom/example/TestClass;->doWork(Ljava/lang/String;I)V
                    return-void
                .end method
                
                .method public caller2()V
                    .locals 2
                    invoke-virtual {v0, v1, v2}, Lcom/example/TestClass;->doWork(Ljava/lang/String;I)V
                    return-void
                .end method
                
                .method public unrelated(Ljava/lang/String;)V
                    .locals 0
                    return-void
                .end method
            """.trimIndent()
            
            File(tempDir, "TestClass.smali").writeText(fileContent)
            
            val parser = SmaliParser()
            val index = WorkspaceIndex()
            
            tempDir.listFiles()?.filter { it.extension == "smali" }?.forEach { file ->
                val uri = file.toURI().toString()
                val content = file.readText()
                val parsed = parser.parse(uri, content)
                if (parsed != null) {
                    index.indexFile(parsed)
                }
            }
            
            val provider = ReferenceProvider(index)
            
            // Click on "Ljava/lang/String;" in the descriptor on line 10 (caller1)
            // This should find references to doWork METHOD, not String class
            val testUri = File(tempDir, "TestClass.smali").toURI().toString()
            val lines = File(tempDir, "TestClass.smali").readLines()
            val invokeLine = lines[10]
            val stringPos = invokeLine.indexOf("Ljava/lang/String;") + 5 // Click in middle of String class
            val refs = provider.findReferences(testUri, Position(10, stringPos), true)
            
            // Should find:
            // 1. doWork definition (line 3)
            // 2. invoke in caller1 (line 10)
            // 3. invoke in caller2 (line 15)
            // Should NOT find:
            // 4. unrelated method that also has String parameter (line 20)
            
            val refStrings = refs.map { loc ->
                val uri = loc.uri
                val fileName = uri.substringAfterLast("/")
                val line = loc.range.start.line
                "$fileName:$line"
            }.sorted()
            
            // Assert exactly 3 references (definition + 2 calls)
            assertEquals(3, refs.size, "Expected 3 references (definition + 2 calls), but got ${refs.size}")
            
            // Verify it found method references, not class references
            // Method ranges are multi-line (3-6, 8-12, etc), invocation ranges are single-line
            val hasDefinition = refs.any { it.range.start.line == 3 && it.range.end.line == 6 }
            val hasInvoke1 = refs.any { it.range.start.line == 10 && it.range.end.line == 10 }
            val hasInvoke2 = refs.any { it.range.start.line == 16 && it.range.end.line == 16 }
            
            assertTrue(hasDefinition, "Should include doWork definition at line 3")
            assertTrue(hasInvoke1, "Should include invoke in caller1 at line 10")
            assertTrue(hasInvoke2, "Should include invoke in caller2 at line 16")
            assertTrue(
                refStrings.none { it.contains(":20") },
                "Should NOT include unrelated method (different method signature)"
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun `find refs on Object init should match all workspace classes but not SDK classes`() {
        val tempDir = Files.createTempDirectory("smali-lsp-test").toFile()
        
        try {
            // Class that calls Object.<init> directly (every class constructor does this)
            val classAContent = """
                .class public Lcom/example/ClassA;
                .super Ljava/lang/Object;
                
                .method public constructor <init>()V
                    .locals 0
                    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                    return-void
                .end method
            """.trimIndent()
            
            val classBContent = """
                .class public Lcom/example/ClassB;
                .super Ljava/lang/Object;
                
                .method public constructor <init>()V
                    .locals 0
                    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                    return-void
                .end method
            """.trimIndent()
            
            // Write files
            File(tempDir, "ClassA.smali").writeText(classAContent)
            File(tempDir, "ClassB.smali").writeText(classBContent)
            
            // Index files
            val parser = SmaliParser()
            val index = WorkspaceIndex()
            
            tempDir.listFiles()?.filter { it.extension == "smali" }?.forEach { file ->
                val uri = file.toURI().toString()
                val content = file.readText()
                val parsed = parser.parse(uri, content)
                if (parsed != null) {
                    index.indexFile(parsed)
                }
            }
            
            val provider = ReferenceProvider(index)
            
            // Click on Object.<init> in ClassA, line 5
            // Find the position of "<init>" in the line
            val classALines = File(tempDir, "ClassA.smali").readLines()
            val objectInitLine = classALines[5]
            val initPos = objectInitLine.indexOf("<init>") + 2 // Click in middle of "<init>"
            val classAUri = File(tempDir, "ClassA.smali").toURI().toString()
            val refs = provider.findReferences(classAUri, Position(5, initPos), false)
            
            // With new SDK class behavior: Only find DIRECT calls (not subclass calls)
            // The invoke is "invoke-direct {p0}, Ljava/lang/Object;-><init>()"
            // So it's calling Object.<init>, not ClassA.<init> or ClassB.<init>
            // Should find both ClassA and ClassB (both have direct calls to Object.<init>)
            
            val refStrings = refs.map { loc ->
                val uri = loc.uri
                val fileName = uri.substringAfterLast("/")
                val line = loc.range.start.line
                "$fileName:$line"
            }.sorted()
            
            println("Found ${refs.size} references:")
            refStrings.forEach { println("  $it") }
            
            // Should have exactly 2 references (both workspace classes calling Object.<init> directly)
            assertEquals(2, refs.size, "Expected 2 references (ClassA + ClassB both call Object.<init> directly), but got ${refs.size}")
            assertTrue(refStrings.any { it.contains("ClassA.smali") })
            assertTrue(refStrings.any { it.contains("ClassB.smali") })
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
