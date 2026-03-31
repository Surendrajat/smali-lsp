package xyz.surendrajat.smalilsp.integration.navigation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.eclipse.lsp4j.Position
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.parser.SmaliParser
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for SDK class navigation behavior
 * 
 * User requirement: "SDK Class should not be clickable or have any definitions"
 * 
 * SDK classes are standard Android framework classes like:
 * - java/lang/Object
 * - android/app/Activity
 * - java/util/ArrayList
 * 
 * These should NOT be navigable since they're not part of the user's codebase.
 */
class SdkClassNavigationTest {
    
    @TempDir
    lateinit var tempDir: Path
    
    private lateinit var parser: SmaliParser
    private lateinit var workspaceIndex: WorkspaceIndex
    private lateinit var definitionProvider: DefinitionProvider
    
    @BeforeEach
    fun setup() {
        parser = SmaliParser()
        workspaceIndex = WorkspaceIndex()
        definitionProvider = DefinitionProvider(workspaceIndex)
    }
    
    @Test
    fun `SDK class java lang Object should not be navigable`() {
        // Create a simple class that extends Object
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public constructor <init>()V
                .locals 0
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        
        val testFile = tempDir.resolve("Test.smali").toFile()
        testFile.writeText(content)
        val uri = testFile.toURI().toString()
        
        val smaliFile = parser.parse(uri, content)!!
        workspaceIndex.indexFile(smaliFile)
        
        // Position on line 1, column 7 (on "Ljava/lang/Object")
        val locations = definitionProvider.findDefinition(uri, Position(1, 7))
        
        // Should return empty - SDK classes are not navigable
        assertTrue(locations.isEmpty(), "SDK class java/lang/Object should not be navigable")
    }
    
    @Test
    fun `SDK class android app Activity should not be navigable`() {
        val content = """
            .class public Lcom/example/MainActivity;
            .super Landroid/app/Activity;
            
            .method public onCreate()V
                .locals 0
                invoke-super {p0}, Landroid/app/Activity;->onCreate()V
                return-void
            .end method
        """.trimIndent()
        
        val testFile = tempDir.resolve("MainActivity.smali").toFile()
        testFile.writeText(content)
        val uri = testFile.toURI().toString()
        
        val smaliFile = parser.parse(uri, content)!!
        workspaceIndex.indexFile(smaliFile)
        
        // Position on line 1, column 7 (on "Landroid/app/Activity")
        val locations = definitionProvider.findDefinition(uri, Position(1, 7))
        
        assertTrue(locations.isEmpty(), "SDK class android/app/Activity should not be navigable")
    }
    
    @Test
    fun `SDK class java util ArrayList should not be navigable`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .field private myList:Ljava/util/ArrayList;
            
            .method public test()V
                .locals 1
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
                return-void
            .end method
        """.trimIndent()
        
        val testFile = tempDir.resolve("Test.smali").toFile()
        testFile.writeText(content)
        val uri = testFile.toURI().toString()
        
        val smaliFile = parser.parse(uri, content)!!
        workspaceIndex.indexFile(smaliFile)
        
        // Position on line 7, column 24 (on "Ljava/util/ArrayList")
        val locations = definitionProvider.findDefinition(uri, Position(7, 24))
        
        assertTrue(locations.isEmpty(), "SDK class java/util/ArrayList should not be navigable")
    }
    
    @Test
    fun `user class should be navigable`() {
        // Create two files - one references the other
        val myClassContent = """
            .class public Lcom/example/MyClass;
            .super Ljava/lang/Object;
            
            .method public foo()V
                .locals 0
                return-void
            .end method
        """.trimIndent()
        
        val testContent = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .field private obj:Lcom/example/MyClass;
            
            .method public test()V
                .locals 1
                new-instance v0, Lcom/example/MyClass;
                return-void
            .end method
        """.trimIndent()
        
        val myClassFile = tempDir.resolve("MyClass.smali").toFile()
        myClassFile.writeText(myClassContent)
        val myClassUri = myClassFile.toURI().toString()
        
        val testFile = tempDir.resolve("Test.smali").toFile()
        testFile.writeText(testContent)
        val testUri = testFile.toURI().toString()
        
        val myClass = parser.parse(myClassUri, myClassContent)!!
        workspaceIndex.indexFile(myClass)
        
        val testClass = parser.parse(testUri, testContent)!!
        workspaceIndex.indexFile(testClass)
        
        // Position on line 7, column 24 (on "Lcom/example/MyClass")
        val locations = definitionProvider.findDefinition(testUri, Position(7, 24))
        
        // User class SHOULD be navigable
        assertEquals(1, locations.size, "User class should be navigable")
        assertTrue(locations[0].uri.contains("MyClass.smali"), "Should navigate to MyClass.smali")
    }
    
    @Test
    fun `SDK method invocation should not navigate to SDK class`() {
        val content = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                new-instance v0, Ljava/lang/StringBuilder;
                invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
                const-string v1, "test"
                invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                return-void
            .end method
        """.trimIndent()
        
        val testFile = tempDir.resolve("Test.smali").toFile()
        testFile.writeText(content)
        val uri = testFile.toURI().toString()
        
        val smaliFile = parser.parse(uri, content)!!
        workspaceIndex.indexFile(smaliFile)
        
        // Position on line 6, column 24 (on "Ljava/lang/StringBuilder")
        val locations = definitionProvider.findDefinition(uri, Position(6, 24))
        
        assertTrue(locations.isEmpty(), "SDK method invocation should not navigate to SDK class")
    }
    
    @Test
    fun `mixed SDK and user classes - only user class navigable`() {
        // Create a user class
        val userClassContent = """
            .class public Lcom/example/UserClass;
            .super Ljava/lang/Object;
            
            .method public foo()V
                .locals 0
                return-void
            .end method
        """.trimIndent()
        
        // Create test that references both SDK and user classes
        val testContent = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .field private sdkField:Ljava/util/ArrayList;
            .field private userField:Lcom/example/UserClass;
            
            .method public test()V
                .locals 0
                return-void
            .end method
        """.trimIndent()
        
        val userClassFile = tempDir.resolve("UserClass.smali").toFile()
        userClassFile.writeText(userClassContent)
        val userClassUri = userClassFile.toURI().toString()
        
        val testFile = tempDir.resolve("Test.smali").toFile()
        testFile.writeText(testContent)
        val testUri = testFile.toURI().toString()
        
        val userClass = parser.parse(userClassUri, userClassContent)!!
        workspaceIndex.indexFile(userClass)
        
        val testClass = parser.parse(testUri, testContent)!!
        workspaceIndex.indexFile(testClass)
        
        // Test SDK class (line 3, column 24)
        val sdkLocations = definitionProvider.findDefinition(testUri, Position(3, 24))
        assertTrue(sdkLocations.isEmpty(), "SDK class ArrayList should not be navigable")
        
        // Test user class (line 4, column 25)
        val userLocations = definitionProvider.findDefinition(testUri, Position(4, 25))
        assertEquals(1, userLocations.size, "User class should be navigable")
        assertTrue(userLocations[0].uri.contains("UserClass.smali"), "Should navigate to UserClass.smali")
    }
}
