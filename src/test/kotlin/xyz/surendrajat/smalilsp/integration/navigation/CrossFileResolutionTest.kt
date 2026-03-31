package xyz.surendrajat.smalilsp.integration.navigation

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Location
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import java.io.File

/**
 * Integration tests for cross-file resolution.
 * 
 * These tests verify the CRITICAL functionality that was broken in v1:
 * - Class A defined in FileA.smali
 * - Class B references Class A in FileB.smali
 * - Can we find the definition of Class A from Class B's reference?
 * 
 * This is the real E2E test that validates Bug #2 from v1 is fixed.
 */
class CrossFileResolutionTest {
    
    @TempDir
    lateinit var tempDir: File
    
    private lateinit var index: WorkspaceIndex
    private lateinit var scanner: WorkspaceScanner
    
    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        scanner = WorkspaceScanner(index)
    }
    
    @Test
    fun `find class definition across files`() = runBlocking {
        // Create BaseClass in one file
        File(tempDir, "BaseClass.smali").writeText("""
            .class public Lcom/example/BaseClass;
            .super Ljava/lang/Object;
            
            .method public baseMethod()V
                return-void
            .end method
        """.trimIndent())
        
        // Create DerivedClass that extends BaseClass
        File(tempDir, "DerivedClass.smali").writeText("""
            .class public Lcom/example/DerivedClass;
            .super Lcom/example/BaseClass;
            
            .method public derivedMethod()V
                return-void
            .end method
        """.trimIndent())
        
        // Index both files
        scanner.scanDirectory(tempDir)
        
        // Verify both classes are indexed
        val baseClass = index.findClass("Lcom/example/BaseClass;")
        assertNotNull(baseClass, "BaseClass should be indexed")
        
        val derivedClass = index.findClass("Lcom/example/DerivedClass;")
        assertNotNull(derivedClass, "DerivedClass should be indexed")
        
        // Verify derived class knows its super class
        assertEquals("Lcom/example/BaseClass;", derivedClass?.classDefinition?.superClass)
        
        // Verify we can find BaseClass from DerivedClass's superclass reference
        val superClassDef = index.findClass(derivedClass?.classDefinition?.superClass!!)
        assertNotNull(superClassDef, "Should find BaseClass definition via superclass reference")
        assertEquals("Lcom/example/BaseClass;", superClassDef?.classDefinition?.name)
    }
    
    @Test
    fun `find method definition across files`() = runBlocking {
        // Create class with method definition
        File(tempDir, "Utils.smali").writeText("""
            .class public Lcom/example/Utils;
            .super Ljava/lang/Object;
            
            .method public static doSomething(I)V
                return-void
            .end method
            
            .method public static calculate(II)I
                const/4 v0, 0x0
                return v0
            .end method
        """.trimIndent())
        
        // Create caller class that invokes the method
        File(tempDir, "Caller.smali").writeText("""
            .class public Lcom/example/Caller;
            .super Ljava/lang/Object;
            
            .method public callUtil()V
                const/4 v0, 0x5
                invoke-static {v0}, Lcom/example/Utils;->doSomething(I)V
                return-void
            .end method
        """.trimIndent())
        
        // Index both files
        scanner.scanDirectory(tempDir)
        
        // Find the method definition
        val locations = index.findMethod("Lcom/example/Utils;", "doSomething", "(I)V")
        assertEquals(1, locations.size, "Should find exactly one method definition")
        
        val location = locations.first()
        assertTrue(location.uri.contains("Utils.smali"), "Method should be in Utils.smali")
        
        // Verify the other method is also found
        val calculateLocations = index.findMethod("Lcom/example/Utils;", "calculate", "(II)I")
        assertEquals(1, calculateLocations.size, "Should find calculate method")
    }
    
    @Test
    fun `find field definition across files`() = runBlocking {
        // Create class with field
        File(tempDir, "Config.smali").writeText("""
            .class public Lcom/example/Config;
            .super Ljava/lang/Object;
            
            .field public static MAX_VALUE:I
            .field public name:Ljava/lang/String;
        """.trimIndent())
        
        // Create class that accesses the field
        File(tempDir, "Reader.smali").writeText("""
            .class public Lcom/example/Reader;
            .super Ljava/lang/Object;
            
            .method public getMax()I
                sget v0, Lcom/example/Config;->MAX_VALUE:I
                return v0
            .end method
        """.trimIndent())
        
        // Index both files
        scanner.scanDirectory(tempDir)
        
        // Find the field definition
        val location = index.findField("Lcom/example/Config;", "MAX_VALUE")
        assertNotNull(location, "Should find field definition")
        assertTrue(location?.uri?.contains("Config.smali") == true, "Field should be in Config.smali")
        
        // Verify other field is also found
        val nameLocation = index.findField("Lcom/example/Config;", "name")
        assertNotNull(nameLocation, "Should find name field")
    }
    
    @Test
    fun `find interface implementation across files`() = runBlocking {
        // Create interface
        File(tempDir, "ICallback.smali").writeText("""
            .class public interface abstract Lcom/example/ICallback;
            .super Ljava/lang/Object;
            
            .method public abstract onComplete()V
            .end method
        """.trimIndent())
        
        // Create implementation
        File(tempDir, "CallbackImpl.smali").writeText("""
            .class public Lcom/example/CallbackImpl;
            .super Ljava/lang/Object;
            
            # interfaces
            .implements Lcom/example/ICallback;
            
            .method public onComplete()V
                return-void
            .end method
        """.trimIndent())
        
        // Index both files
        scanner.scanDirectory(tempDir)
        
        // Verify interface is indexed
        val interfaceDef = index.findClass("Lcom/example/ICallback;")
        assertNotNull(interfaceDef, "Interface should be indexed")
        
        // Verify implementation is indexed
        val implDef = index.findClass("Lcom/example/CallbackImpl;")
        assertNotNull(implDef, "Implementation should be indexed")
        
        // Verify implementation declares the interface
        assertTrue(
            implDef?.classDefinition?.interfaces?.contains("Lcom/example/ICallback;") == true,
            "Implementation should declare interface"
        )
        
        // Verify we can find usages of the interface
        val usages = index.findClassUsages("Lcom/example/ICallback;")
        assertTrue(usages.isNotEmpty(), "Should find interface usages")
        assertTrue(usages.any { it.contains("CallbackImpl.smali") }, "Should find implementation in usages")
    }
    
    @Test
    fun `find method overloads across files`() = runBlocking {
        // Create base class with overloaded methods
        File(tempDir, "Printer.smali").writeText("""
            .class public Lcom/example/Printer;
            .super Ljava/lang/Object;
            
            .method public print(I)V
                return-void
            .end method
            
            .method public print(Ljava/lang/String;)V
                return-void
            .end method
            
            .method public print(II)V
                return-void
            .end method
        """.trimIndent())
        
        // Index
        scanner.scanDirectory(tempDir)
        
        // Find each overload
        val intMethod = index.findMethod("Lcom/example/Printer;", "print", "(I)V")
        assertEquals(1, intMethod.size, "Should find print(int) method")
        
        val stringMethod = index.findMethod("Lcom/example/Printer;", "print", "(Ljava/lang/String;)V")
        assertEquals(1, stringMethod.size, "Should find print(String) method")
        
        val twoIntMethod = index.findMethod("Lcom/example/Printer;", "print", "(II)V")
        assertEquals(1, twoIntMethod.size, "Should find print(int, int) method")
        
        // Verify they're all different locations
        val allLocations = intMethod + stringMethod + twoIntMethod
        assertEquals(3, allLocations.toSet().size, "All three methods should have different locations")
    }
    
    @Test
    fun `test cross-file resolution with complex hierarchy`() = runBlocking {
        // Create a realistic class hierarchy
        
        // Base interface
        File(tempDir, "IBase.smali").writeText("""
            .class public interface abstract Lcom/example/IBase;
            .super Ljava/lang/Object;
            
            .method public abstract baseMethod()V
            .end method
        """.trimIndent())
        
        // Abstract class implementing interface
        File(tempDir, "AbstractImpl.smali").writeText("""
            .class public abstract Lcom/example/AbstractImpl;
            .super Ljava/lang/Object;
            
            # interfaces
            .implements Lcom/example/IBase;
            
            .method public abstract extraMethod()V
            .end method
        """.trimIndent())
        
        // Concrete implementation
        File(tempDir, "ConcreteImpl.smali").writeText("""
            .class public Lcom/example/ConcreteImpl;
            .super Lcom/example/AbstractImpl;
            
            .method public baseMethod()V
                return-void
            .end method
            
            .method public extraMethod()V
                return-void
            .end method
        """.trimIndent())
        
        // Index all files
        scanner.scanDirectory(tempDir)
        
        // Verify all classes are indexed
        assertNotNull(index.findClass("Lcom/example/IBase;"), "IBase should be indexed")
        assertNotNull(index.findClass("Lcom/example/AbstractImpl;"), "AbstractImpl should be indexed")
        assertNotNull(index.findClass("Lcom/example/ConcreteImpl;"), "ConcreteImpl should be indexed")
        
        // Verify relationships
        val abstractImpl = index.findClass("Lcom/example/AbstractImpl;")
        assertTrue(
            abstractImpl?.classDefinition?.interfaces?.contains("Lcom/example/IBase;") == true,
            "AbstractImpl should implement IBase"
        )
        
        val concreteImpl = index.findClass("Lcom/example/ConcreteImpl;")
        assertEquals(
            "Lcom/example/AbstractImpl;",
            concreteImpl?.classDefinition?.superClass,
            "ConcreteImpl should extend AbstractImpl"
        )
        
        // Verify usages
        val baseUsages = index.findClassUsages("Lcom/example/IBase;")
        assertTrue(baseUsages.any { it.contains("AbstractImpl.smali") }, "Should find IBase usage in AbstractImpl")
        
        val abstractUsages = index.findClassUsages("Lcom/example/AbstractImpl;")
        assertTrue(abstractUsages.any { it.contains("ConcreteImpl.smali") }, "Should find AbstractImpl usage in ConcreteImpl")
    }
}
