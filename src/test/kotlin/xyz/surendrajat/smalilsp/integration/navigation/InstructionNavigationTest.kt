package xyz.surendrajat.smalilsp.integration.navigation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.eclipse.lsp4j.Position
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.parser.SmaliParser
import java.io.File

/**
 * Integration test for instruction-level navigation.
 * Tests that clicking on invoke-virtual, iget, sget, etc. navigates to the correct definition.
 */
class InstructionNavigationTest {
    
    private lateinit var index: WorkspaceIndex
    private lateinit var definitionProvider: DefinitionProvider
    private lateinit var referenceProvider: ReferenceProvider
    private val parser = SmaliParser()
    
    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        definitionProvider = DefinitionProvider(index)
        referenceProvider = ReferenceProvider(index)
    }
    
    @Test
    fun `navigate from invoke-virtual to method definition`() {
        // Define the helper class
        val helperSmali = """
            .class public Lcom/example/Helper;
            .super Ljava/lang/Object;
            
            .method public doSomething(Ljava/lang/String;)V
                .locals 0
                return-void
            .end method
        """.trimIndent()
        
        // Define the main class that calls the helper
        val mainSmali = """
            .class public Lcom/example/Main;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 2
                
                new-instance v0, Lcom/example/Helper;
                invoke-direct {v0}, Lcom/example/Helper;-><init>()V
                
                const-string v1, "test"
                invoke-virtual {v0, v1}, Lcom/example/Helper;->doSomething(Ljava/lang/String;)V
                
                return-void
            .end method
        """.trimIndent()
        
        // Index both files
        val helperFile = parser.parse("file:///Helper.smali", helperSmali)
        val mainFile = parser.parse("file:///Main.smali", mainSmali)
        assertNotNull(helperFile)
        assertNotNull(mainFile)
        
        index.indexFile(helperFile!!)
        index.indexFile(mainFile!!)
        
        // Find the invoke-virtual instruction in the Main.test method
        val testMethod = mainFile.methods.find { it.name == "test" }
        assertNotNull(testMethod, "Should find test method")
        
        // Find the invoke-virtual instruction
        val invokeInstruction = testMethod!!.instructions
            .filterIsInstance<xyz.surendrajat.smalilsp.core.InvokeInstruction>()
            .find { it.opcode == "invoke-virtual" && it.methodName == "doSomething" }
        assertNotNull(invokeInstruction, "Should find invoke-virtual instruction")
        
        // Use the instruction's range to find definition
        val position = Position(
            invokeInstruction!!.range.start.line,
            invokeInstruction.range.start.character + 5
        )
        
        // Get definition
        val definitions = definitionProvider.findDefinition("file:///Main.smali", position)
        
        // Should navigate to doSomething method in Helper class
        assertEquals(1, definitions.size, "Should find exactly one definition")
        val def = definitions[0]
        assertTrue(def.uri.contains("Helper.smali"), "Should navigate to Helper.smali")
        
        // Verify it's the doSomething method
        val helperFileForVerification = index.findFileByUri("file:///Helper.smali")
        assertNotNull(helperFileForVerification)
        val method = helperFileForVerification!!.methods.find { it.name == "doSomething" }
        assertNotNull(method)
        assertEquals(def.range.start.line, method!!.range.start.line)
    }
    
    @Test
    fun `navigate from iget to field definition`() {
        val smali = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .field private myField:I
            
            .method public getValue()I
                .locals 1
                
                iget v0, p0, Lcom/example/Test;->myField:I
                return v0
            .end method
            
            .method public setValue(I)V
                .locals 0
                
                iput p1, p0, Lcom/example/Test;->myField:I
                return-void
            .end method
        """.trimIndent()
        
        val file = parser.parse("file:///Test.smali", smali)
        assertNotNull(file)
        index.indexFile(file!!)
        
        // Test iget navigation (line 8 - iget instruction)
        val igetPosition = Position(8, 20)
        val igetDefs = definitionProvider.findDefinition("file:///Test.smali", igetPosition)
        
        assertEquals(1, igetDefs.size, "Should find field definition from iget")
        val igetDef = igetDefs[0]
        assertTrue(igetDef.uri.contains("Test.smali"))
        
        // Verify it's the myField field (line 3)
        assertEquals(3, igetDef.range.start.line, "Should navigate to field definition at line 3")
        
        // Test iput navigation (line 15 - iput instruction)
        val iputPosition = Position(15, 20)
        val iputDefs = definitionProvider.findDefinition("file:///Test.smali", iputPosition)
        
        assertEquals(1, iputDefs.size, "Should find field definition from iput")
        assertEquals(3, iputDefs[0].range.start.line, "Should navigate to same field definition")
    }
    
    @Test
    fun `navigate from sget to static field definition`() {
        val smali = """
            .class public Lcom/example/Config;
            .super Ljava/lang/Object;
            
            .field public static DEBUG:Z
            .field public static VERSION:I
            
            .method public static isDebug()Z
                .locals 1
                
                sget-boolean v0, Lcom/example/Config;->DEBUG:Z
                return v0
            .end method
            
            .method public static getVersion()I
                .locals 1
                
                sget v0, Lcom/example/Config;->VERSION:I
                return v0
            .end method
        """.trimIndent()
        
        val file = parser.parse("file:///Config.smali", smali)
        assertNotNull(file)
        index.indexFile(file!!)
        
        // Test sget-boolean navigation
        val debugPosition = Position(9, 25)
        val debugDefs = definitionProvider.findDefinition("file:///Config.smali", debugPosition)
        
        assertEquals(1, debugDefs.size, "Should find DEBUG field")
        assertEquals(3, debugDefs[0].range.start.line, "Should navigate to DEBUG field at line 3")
        
        // Test sget navigation
        val versionPosition = Position(16, 25)
        val versionDefs = definitionProvider.findDefinition("file:///Config.smali", versionPosition)
        
        assertEquals(1, versionDefs.size, "Should find VERSION field")
        assertEquals(4, versionDefs[0].range.start.line, "Should navigate to VERSION field at line 4")
    }
    
    @Test
    fun `navigate from new-instance to class definition`() {
        // Define the class to be instantiated
        val listClassSmali = """
            .class public Lcom/example/MyList;
            .super Ljava/lang/Object;
            
            .method public constructor <init>()V
                .locals 0
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        
        // Define the class that creates instances
        val factorySmali = """
            .class public Lcom/example/Factory;
            .super Ljava/lang/Object;
            
            .method public create()Lcom/example/MyList;
                .locals 1
                
                new-instance v0, Lcom/example/MyList;
                invoke-direct {v0}, Lcom/example/MyList;-><init>()V
                
                return-object v0
            .end method
        """.trimIndent()
        
        val listFile = parser.parse("file:///MyList.smali", listClassSmali)
        val factoryFile = parser.parse("file:///Factory.smali", factorySmali)
        assertNotNull(listFile)
        assertNotNull(factoryFile)
        
        index.indexFile(listFile!!)
        index.indexFile(factoryFile!!)
        
        // Navigate from new-instance (line 6)
        val position = Position(6, 25)
        val definitions = definitionProvider.findDefinition("file:///Factory.smali", position)
        
        assertEquals(1, definitions.size, "Should find MyList class definition")
        val def = definitions[0]
        assertTrue(def.uri.contains("MyList.smali"), "Should navigate to MyList.smali")
        assertEquals(0, def.range.start.line, "Should navigate to class definition at line 0")
    }
    
    @Test
    fun `navigate from check-cast to class definition`() {
        val baseSmali = """
            .class public Lcom/example/Base;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val derivedSmali = """
            .class public Lcom/example/Derived;
            .super Lcom/example/Base;
        """.trimIndent()
        
        val testSmali = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public cast(Lcom/example/Base;)Lcom/example/Derived;
                .locals 0
                
                check-cast p1, Lcom/example/Derived;
                return-object p1
            .end method
        """.trimIndent()
        
        val baseFile = parser.parse("file:///Base.smali", baseSmali)
        val derivedFile = parser.parse("file:///Derived.smali", derivedSmali)
        val testFile = parser.parse("file:///Test.smali", testSmali)
        
        index.indexFile(baseFile!!)
        index.indexFile(derivedFile!!)
        index.indexFile(testFile!!)
        
        // Navigate from check-cast (line 6)
        val position = Position(6, 25)
        val definitions = definitionProvider.findDefinition("file:///Test.smali", position)
        
        assertEquals(1, definitions.size, "Should find Derived class definition")
        val def = definitions[0]
        assertTrue(def.uri.contains("Derived.smali"), "Should navigate to Derived.smali")
        assertEquals(0, def.range.start.line, "Should navigate to class definition")
    }
    
    @Test
    fun `cross-file invoke navigation works`() {
        // Create a more realistic scenario with multiple files
        val utilsSmali = """
            .class public Lcom/example/Utils;
            .super Ljava/lang/Object;
            
            .method public static format(Ljava/lang/String;)Ljava/lang/String;
                .locals 0
                return-object p0
            .end method
            
            .method public static log(Ljava/lang/String;)V
                .locals 0
                return-void
            .end method
        """.trimIndent()
        
        val activitySmali = """
            .class public Lcom/example/MainActivity;
            .super Landroid/app/Activity;
            
            .method protected onCreate(Landroid/os/Bundle;)V
                .locals 1
                
                invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V
                
                const-string v0, "Activity created"
                invoke-static {v0}, Lcom/example/Utils;->log(Ljava/lang/String;)V
                
                return-void
            .end method
        """.trimIndent()
        
        val utilsFile = parser.parse("file:///Utils.smali", utilsSmali)
        val activityFile = parser.parse("file:///MainActivity.smali", activitySmali)
        
        index.indexFile(utilsFile!!)
        index.indexFile(activityFile!!)
        
        // Navigate from invoke-static to Utils.log (line 9)
        val position = Position(9, 30)
        val definitions = definitionProvider.findDefinition("file:///MainActivity.smali", position)
        
        assertEquals(1, definitions.size, "Should find Utils.log method")
        val def = definitions[0]
        assertTrue(def.uri.contains("Utils.smali"), "Should navigate to Utils.smali")
        
        // Verify it's the log method (line 8)
        val utilsFileForVerification = index.findFileByUri("file:///Utils.smali")
        val logMethod = utilsFileForVerification!!.methods.find { it.name == "log" }
        assertNotNull(logMethod)
        assertEquals(def.range.start.line, logMethod!!.range.start.line)
    }
    
    @Test
    fun `instruction count is reasonable for real method`() {
        // Test that instruction parsing doesn't explode memory or miss instructions
        val complexSmali = """
            .class public Lcom/example/Complex;
            .super Ljava/lang/Object;
            
            .field private mCount:I
            .field private static sInstance:Lcom/example/Complex;
            
            .method public process(Ljava/lang/String;)Ljava/lang/String;
                .locals 5
                
                new-instance v0, Ljava/lang/StringBuilder;
                invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
                
                iget v1, p0, Lcom/example/Complex;->mCount:I
                const v2, 0xa
                if-ge v1, v2, :cond_0
                
                invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                const-string v3, " - "
                invoke-virtual {v0, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
                
                :cond_0
                sget-object v4, Lcom/example/Complex;->sInstance:Lcom/example/Complex;
                if-eqz v4, :cond_1
                
                invoke-virtual {v4}, Lcom/example/Complex;->toString()Ljava/lang/String;
                move-result-object v3
                invoke-virtual {v0, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                
                :cond_1
                invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v0
                
                return-object v0
            .end method
        """.trimIndent()
        
        val file = parser.parse("file:///Complex.smali", complexSmali)
        assertNotNull(file)
        
        val method = file!!.methods.find { it.name == "process" }
        assertNotNull(method)
        
        val instructions = method!!.instructions
        
        // Count instruction types
        val invokeCount = instructions.count { it is xyz.surendrajat.smalilsp.core.InvokeInstruction }
        val fieldCount = instructions.count { it is xyz.surendrajat.smalilsp.core.FieldAccessInstruction }
        val typeCount = instructions.count { it is xyz.surendrajat.smalilsp.core.TypeInstruction }
        
        println("Complex method has $invokeCount invokes, $fieldCount field accesses, $typeCount type instructions")
        println("Total instructions: ${instructions.size}")
        
        // We expect:
        // - At least 6 invoke instructions (1x invoke-direct, 5x invoke-virtual)
        // - At least 2 field access (1x iget, 1x sget-object)
        // - At least 1 type instruction (1x new-instance)
        
        assertTrue(invokeCount >= 6, "Should have at least 6 invoke instructions, got $invokeCount")
        assertTrue(fieldCount >= 2, "Should have at least 2 field accesses, got $fieldCount")
        assertTrue(typeCount >= 1, "Should have at least 1 type instruction, got $typeCount")
        assertTrue(instructions.size >= 9, "Should have at least 9 total instructions, got ${instructions.size}")
    }
}
