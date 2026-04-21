package xyz.surendrajat.smalilsp.stress.queries

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.shared.TempTestWorkspace
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import kotlin.test.assertEquals

/**
 * Aggressive stress test for Find References.
 * Tests ACTUAL instruction-level reference finding, not just API calls.
 */
class FindReferencesStressTest {
    
    @Test
    fun `find references - method called from multiple instructions`() {
        val workspace = TempTestWorkspace.create()
        
        workspace.addFile("Target.smali", """
            .class public LTarget;
            .super Ljava/lang/Object;
            
            .method public targetMethod()V
                return-void
            .end method
        """.trimIndent())
        
        workspace.addFile("Caller1.smali", """
            .class public LCaller1;
            .super Ljava/lang/Object;
            
            .method public callTarget()V
                new-instance v0, LTarget;
                invoke-direct {v0}, LTarget;-><init>()V
                invoke-virtual {v0}, LTarget;->targetMethod()V
                invoke-virtual {v0}, LTarget;->targetMethod()V
                invoke-virtual {v0}, LTarget;->targetMethod()V
                return-void
            .end method
        """.trimIndent())
        
        workspace.addFile("Caller2.smali", """
            .class public LCaller2;
            .super Ljava/lang/Object;
            
            .method public alsoCallsTarget()V
                new-instance v0, LTarget;
                invoke-direct {v0}, LTarget;-><init>()V
                invoke-virtual {v0}, LTarget;->targetMethod()V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val targetUri = workspace.getUri("Target.smali")
        
        // Find targetMethod definition
        val targetFile = index.findFileByUri(targetUri)!!
        val targetMethod = targetFile.methods.find { it.name == "targetMethod" }!!
        
        // Find all references to targetMethod
        val refs = provider.findReferences(
            targetUri, 
            Position(targetMethod.range.start.line, 20),
            includeDeclaration = false
        )
        
        println("Found ${refs.size} references to targetMethod")
        refs.forEach { ref ->
            val fileName = ref.uri.substringAfterLast("/")
            println("  - $fileName @ line ${ref.range.start.line}")
        }
        
        val actualReferences = refs.map { it.uri.substringAfterLast("/") to it.range.start.line }.toSet()
        val expectedReferences = setOf(
            "Caller1.smali" to 6,
            "Caller1.smali" to 7,
            "Caller1.smali" to 8,
            "Caller2.smali" to 6,
        )

        assertEquals(expectedReferences, actualReferences, "Should find the exact targetMethod call sites")
        
        workspace.cleanup()
    }
    
    @Test
    fun `find references - field accessed from multiple instructions`() {
        val workspace = TempTestWorkspace.create()
        
        workspace.addFile("Data.smali", """
            .class public LData;
            .super Ljava/lang/Object;
            
            .field public counter:I
        """.trimIndent())
        
        workspace.addFile("User.smali", """
            .class public LUser;
            .super Ljava/lang/Object;
            
            .method public useCounter()V
                new-instance v0, LData;
                iget v1, v0, LData;->counter:I
                iput v1, v0, LData;->counter:I
                iget v2, v0, LData;->counter:I
                return-void
            .end method
            
            .method public anotherUser()V
                new-instance v0, LData;
                iget v1, v0, LData;->counter:I
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val dataUri = workspace.getUri("Data.smali")
        
        // Find counter field definition
        val dataFile = index.findFileByUri(dataUri)!!
        val counterField = dataFile.fields.find { it.name == "counter" }!!
        
        // Find all references to counter field
        val refs = provider.findReferences(
            dataUri,
            Position(counterField.range.start.line, 20),
            includeDeclaration = false
        )
        
        println("Found ${refs.size} references to counter field")
        refs.forEach { ref ->
            println("  - line ${ref.range.start.line}")
        }
        
        assertEquals(setOf(5, 6, 7, 13), refs.map { it.range.start.line }.toSet(), "Should find the exact counter field access sites")
        
        workspace.cleanup()
    }
    
    @Test
    fun `find references - class used in multiple contexts`() {
        val workspace = TempTestWorkspace.create()
        
        workspace.addFile("MyClass.smali", """
            .class public LMyClass;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        workspace.addFile("UsesMyClass.smali", """
            .class public LUsesMyClass;
            .super Ljava/lang/Object;
            
            .field public instance:LMyClass;
            
            .method public test(LMyClass;)LMyClass;
                new-instance v0, LMyClass;
                check-cast v0, LMyClass;
                return-object v0
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val myClassUri = workspace.getUri("MyClass.smali")
        
        // Find MyClass definition
        val myClassFile = index.findFileByUri(myClassUri)!!
        
        // Find all references to MyClass
        val refs = provider.findReferences(
            myClassUri,
            Position(myClassFile.classDefinition.range.start.line, 15),
            includeDeclaration = false
        )
        
        println("Found ${refs.size} references to MyClass")
        refs.forEach { ref ->
            println("  - line ${ref.range.start.line}")
        }
        
        assertEquals(setOf(3, 5, 6, 7), refs.map { it.range.start.line }.toSet(), "Should find the exact MyClass reference sites")
        
        workspace.cleanup()
    }
    
    @Test
    fun `find references - inherited method calls`() {
        val workspace = TempTestWorkspace.create()
        
        workspace.addFile("Base.smali", """
            .class public LBase;
            .super Ljava/lang/Object;
            
            .method public baseMethod()V
                return-void
            .end method
        """.trimIndent())
        
        workspace.addFile("Derived.smali", """
            .class public LDerived;
            .super LBase;
        """.trimIndent())
        
        workspace.addFile("Caller.smali", """
            .class public LCaller;
            .super Ljava/lang/Object;
            
            .method public test()V
                new-instance v0, LBase;
                invoke-virtual {v0}, LBase;->baseMethod()V
                
                new-instance v1, LDerived;
                invoke-virtual {v1}, LDerived;->baseMethod()V
                invoke-virtual {v1}, LBase;->baseMethod()V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val baseUri = workspace.getUri("Base.smali")
        
        // Find baseMethod definition
        val baseFile = index.findFileByUri(baseUri)!!
        val baseMethod = baseFile.methods.find { it.name == "baseMethod" }!!
        
        // Find all references to baseMethod
        val refs = provider.findReferences(
            baseUri,
            Position(baseMethod.range.start.line, 20),
            includeDeclaration = false
        )
        
        println("Found ${refs.size} references to baseMethod")
        
        val actualReferences = refs.map { it.uri.substringAfterLast("/") to it.range.start.line }.toSet()
        val expectedReferences = setOf(
            "Caller.smali" to 5,
            "Caller.smali" to 8,
            "Caller.smali" to 9,
        )

        assertEquals(expectedReferences, actualReferences, "Should find the exact inherited baseMethod call sites")
        
        workspace.cleanup()
    }
    
    @Test
    fun `find references - static field accessed everywhere`() {
        val workspace = TempTestWorkspace.create()
        
        workspace.addFile("Constants.smali", """
            .class public LConstants;
            .super Ljava/lang/Object;
            
            .field public static final MAX_SIZE:I = 0x64
        """.trimIndent())
        
        workspace.addFile("User1.smali", """
            .class public LUser1;
            .super Ljava/lang/Object;
            
            .method public getMax()I
                sget v0, LConstants;->MAX_SIZE:I
                sget v1, LConstants;->MAX_SIZE:I
                return v0
            .end method
        """.trimIndent())
        
        workspace.addFile("User2.smali", """
            .class public LUser2;
            .super Ljava/lang/Object;
            
            .method public useMax()V
                sget v0, LConstants;->MAX_SIZE:I
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val constUri = workspace.getUri("Constants.smali")
        
        // Find MAX_SIZE field
        val constFile = index.findFileByUri(constUri)!!
        val maxSizeField = constFile.fields.find { it.name == "MAX_SIZE" }!!
        
        // Find all references
        val refs = provider.findReferences(
            constUri,
            Position(maxSizeField.range.start.line, 30),
            includeDeclaration = false
        )
        
        println("Found ${refs.size} references to MAX_SIZE")
        
        val actualReferences = refs.map { it.uri.substringAfterLast("/") to it.range.start.line }.toSet()
        val expectedReferences = setOf(
            "User1.smali" to 4,
            "User1.smali" to 5,
            "User2.smali" to 4,
        )

        assertEquals(expectedReferences, actualReferences, "Should find the exact MAX_SIZE access sites")
        
        workspace.cleanup()
    }
    
    @Test
    fun `find references - overloaded methods all found`() {
        val workspace = TempTestWorkspace.create()
        
        workspace.addFile("Overloaded.smali", """
            .class public LOverloaded;
            .super Ljava/lang/Object;
            
            .method public test()V
                return-void
            .end method
            
            .method public test(I)V
                return-void
            .end method
            
            .method public test(Ljava/lang/String;)V
                return-void
            .end method
        """.trimIndent())
        
        workspace.addFile("Caller.smali", """
            .class public LCaller;
            .super Ljava/lang/Object;
            
            .method public callAll()V
                new-instance v0, LOverloaded;
                invoke-virtual {v0}, LOverloaded;->test()V
                invoke-virtual {v0, v1}, LOverloaded;->test(I)V
                invoke-virtual {v0, v2}, LOverloaded;->test(Ljava/lang/String;)V
                return-void
            .end method
        """.trimIndent())
        
        val index = workspace.buildIndex()
        val provider = ReferenceProvider(index)
        val overloadedUri = workspace.getUri("Overloaded.smali")
        
        // Find each overload and count its references
        val overloadedFile = index.findFileByUri(overloadedUri)!!
        
        val test0 = overloadedFile.methods.find { it.name == "test" && it.descriptor == "()V" }!!
        val refs0 = provider.findReferences(
            overloadedUri,
            Position(test0.range.start.line, 20),
            includeDeclaration = false
        )
        
        val test1 = overloadedFile.methods.find { it.name == "test" && it.descriptor == "(I)V" }!!
        val refs1 = provider.findReferences(
            overloadedUri,
            Position(test1.range.start.line, 20),
            includeDeclaration = false
        )
        
        val test2 = overloadedFile.methods.find { it.name == "test" && it.descriptor == "(Ljava/lang/String;)V" }!!
        val refs2 = provider.findReferences(
            overloadedUri,
            Position(test2.range.start.line, 20),
            includeDeclaration = false
        )
        
        println("test()V: ${refs0.size} refs")
        println("test(I)V: ${refs1.size} refs")
        println("test(Ljava/lang/String;)V: ${refs2.size} refs")
        
        // Each should find exactly 1 reference (not mixing them up)
        assertEquals(1, refs0.size, "test()V should find exactly 1 reference")
        assertEquals(1, refs1.size, "test(I)V should find exactly 1 reference")
        assertEquals(1, refs2.size, "test(Ljava/lang/String;)V should find exactly 1 reference")
        
        workspace.cleanup()
    }
}
