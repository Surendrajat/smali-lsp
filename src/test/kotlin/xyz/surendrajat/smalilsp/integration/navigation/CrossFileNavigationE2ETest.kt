package xyz.surendrajat.smalilsp.integration.navigation

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag

import xyz.surendrajat.smalilsp.integration.lsp.TestWorkspace
import xyz.surendrajat.smalilsp.integration.lsp.E2ETestHarness
import xyz.surendrajat.smalilsp.integration.lsp.withE2ETest
/**
 * E2E tests for cross-file navigation
 */
@DisplayName("Cross-File Navigation E2E Tests")
class CrossFileNavigationE2ETest {
    
    @Test
    @DisplayName("User navigates from ClassA to ClassB definition")
    fun `user navigates from ClassA to ClassB definition`() {
        val workspace = TestWorkspace.createTemp(mapOf(
            "src/ClassA.smali" to """
                .class public LClassA;
                .super Ljava/lang/Object;
                
                .method public foo()V
                    .registers 2
                    new-instance v0, LClassB;
                    invoke-direct {v0}, LClassB;-><init>()V
                    return-void
                .end method
            """.trimIndent(),
            
            "src/ClassB.smali" to """
                .class public LClassB;
                .super Ljava/lang/Object;
                
                .method public constructor <init>()V
                    .registers 1
                    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                    return-void
                .end method
            """.trimIndent()
        ))
        
        withE2ETest(workspace) {
            // Open ClassA
            val uriA = openFile("src/ClassA.smali")
            
            // Also open ClassB to ensure it's indexed
            openFile("src/ClassB.smali")
            
            // Wait for indexing
            waitForIndexing(1)
            
            // Click on "LClassB;" in new-instance instruction
            val pos = workspace.findPosition("src/ClassA.smali", "LClassB;")
            
            val definitions = gotoDefinition(uriA, pos)
            
            // Should jump to ClassB
            assertEquals(1, definitions.size, "Should find ClassB definition")
            assertTrue(definitions[0].uri.contains("ClassB.smali"), 
                "Definition should be in ClassB.smali")
            
            // Verify it points to the .class directive
            val defLine = definitions[0].range.start.line
            val uriB = definitions[0].uri
            
            // Read from workspace using the URI
            val classBContent = workspace.readFile("src/ClassB.smali")
            val defText = classBContent.lines()[defLine]
            assertTrue(defText.contains(".class public LClassB;"),
                "Definition should point to class declaration")
        }
    }
    
    @Test
    @DisplayName("User finds references across multiple files")
    fun `user finds references across multiple files`() {
        val workspace = TestWorkspace.createTemp(mapOf(
            "src/Utils.smali" to """
                .class public LUtils;
                .super Ljava/lang/Object;
                
                .method public static helper()V
                    .registers 1
                    return-void
                .end method
            """.trimIndent(),
            
            "src/ClassA.smali" to """
                .class public LClassA;
                .super Ljava/lang/Object;
                
                .method public foo()V
                    .registers 1
                    invoke-static {}, LUtils;->helper()V
                    return-void
                .end method
            """.trimIndent(),
            
            "src/ClassB.smali" to """
                .class public LClassB;
                .super Ljava/lang/Object;
                
                .method public bar()V
                    .registers 1
                    invoke-static {}, LUtils;->helper()V
                    return-void
                .end method
            """.trimIndent(),
            
            "src/ClassC.smali" to """
                .class public LClassC;
                .super Ljava/lang/Object;
                
                .method public baz()V
                    .registers 1
                    invoke-static {}, LUtils;->helper()V
                    return-void
                .end method
            """.trimIndent()
        ))
        
        withE2ETest(workspace) {
            // Open all files
            openFile("src/Utils.smali")
            openFile("src/ClassA.smali")
            openFile("src/ClassB.smali")
            openFile("src/ClassC.smali")
            
            // Wait for indexing
            waitForIndexing(2)
            
            // Find references to helper method
            val utilsUri = workspace.getFileUri("src/Utils.smali")
            val pos = workspace.findPosition("src/Utils.smali", "helper()")
            
            val references = findReferences(utilsUri, pos, includeDeclaration = true)
            
            // Should find: 1 definition + 3 calls = 4 total
            assertTrue(references.size >= 4, 
                "Should find at least 4 references (1 def + 3 calls), found ${references.size}")
            
            // Verify references span multiple files
            val files = references.map { it.uri }.toSet()
            assertTrue(files.size >= 3, 
                "References should span at least 3 files, found ${files.size}")
            
            println("Found ${references.size} references across ${files.size} files")
        }
    }
    
    @Test
    @DisplayName("User navigates through class hierarchy")
    fun `user navigates through class hierarchy`() {
        val workspace = TestWorkspace.createTemp(mapOf(
            "src/Base.smali" to """
                .class public LBase;
                .super Ljava/lang/Object;
                
                .method public doSomething()V
                    .registers 1
                    return-void
                .end method
            """.trimIndent(),
            
            "src/Child.smali" to """
                .class public LChild;
                .super LBase;
                
                .method public doSomethingElse()V
                    .registers 1
                    return-void
                .end method
            """.trimIndent(),
            
            "src/GrandChild.smali" to """
                .class public LGrandChild;
                .super LChild;
                
                .method public doMoreThings()V
                    .registers 1
                    return-void
                .end method
            """.trimIndent()
        ))
        
        withE2ETest(workspace) {
            openFile("src/Base.smali")
            openFile("src/Child.smali")
            openFile("src/GrandChild.smali")
            
            waitForIndexing(1)
            
            // From GrandChild, navigate to LChild
            val grandChildUri = workspace.getFileUri("src/GrandChild.smali")
            val pos1 = workspace.findPosition("src/GrandChild.smali", "LChild;")
            
            val defs1 = gotoDefinition(grandChildUri, pos1)
            assertEquals(1, defs1.size)
            assertTrue(defs1[0].uri.contains("Child.smali"))
            
            // From Child, navigate to LBase
            val childUri = workspace.getFileUri("src/Child.smali")
            val pos2 = workspace.findPosition("src/Child.smali", "LBase;")
            
            val defs2 = gotoDefinition(childUri, pos2)
            assertEquals(1, defs2.size)
            assertTrue(defs2[0].uri.contains("Base.smali"))
        }
    }
    
    @Test
    @DisplayName("User navigates to method in another file via invocation")
    fun `user navigates to method in another file via invocation`() {
        val workspace = TestWorkspace.createTemp(mapOf(
            "src/Service.smali" to """
                .class public LService;
                .super Ljava/lang/Object;
                
                .method public processData(Ljava/lang/String;)I
                    .registers 3
                    const/4 v0, 0x1
                    return v0
                .end method
            """.trimIndent(),
            
            "src/Activity.smali" to """
                .class public LActivity;
                .super Ljava/lang/Object;
                
                .field private mService:LService;
                
                .method public doWork()V
                    .registers 3
                    iget-object v0, p0, LActivity;->mService:LService;
                    const-string v1, "test"
                    invoke-virtual {v0, v1}, LService;->processData(Ljava/lang/String;)I
                    return-void
                .end method
            """.trimIndent()
        ))
        
        withE2ETest(workspace) {
            openFile("src/Service.smali")
            openFile("src/Activity.smali")
            
            waitForIndexing(1)
            
            // Click on "processData" in the invoke-virtual
            val activityUri = workspace.getFileUri("src/Activity.smali")
            val pos = workspace.findPosition("src/Activity.smali", "->processData(")
            val clickPos = Position(pos.line, pos.character + 3)  // On "p" of processData
            
            val definitions = gotoDefinition(activityUri, clickPos)
            
            assertEquals(1, definitions.size, "Should find processData method")
            assertTrue(definitions[0].uri.contains("Service.smali"),
                "Should navigate to Service.smali")
            
            // Verify correct method
            val serviceContent = workspace.readFile("src/Service.smali")
            val defLine = definitions[0].range.start.line
            val defText = serviceContent.lines()[defLine]
            assertTrue(defText.contains("processData"))
        }
    }
    
    @Test
    @DisplayName("User navigates to field in another file")
    fun `user navigates to field in another file`() {
        val workspace = TestWorkspace.createTemp(mapOf(
            "src/Config.smali" to """
                .class public LConfig;
                .super Ljava/lang/Object;
                
                .field public static DEBUG:Z
                .field public static API_URL:Ljava/lang/String;
            """.trimIndent(),
            
            "src/App.smali" to """
                .class public LApp;
                .super Ljava/lang/Object;
                
                .method public init()V
                    .registers 2
                    sget-boolean v0, LConfig;->DEBUG:Z
                    if-eqz v0, :cond_0
                    :cond_0
                    return-void
                .end method
            """.trimIndent()
        ))
        
        withE2ETest(workspace) {
            openFile("src/Config.smali")
            openFile("src/App.smali")
            
            waitForIndexing(1)
            
            // Click on "DEBUG" in sget-boolean
            val appUri = workspace.getFileUri("src/App.smali")
            val pos = workspace.findPosition("src/App.smali", "->DEBUG:")
            val clickPos = Position(pos.line, pos.character + 3)
            
            val definitions = gotoDefinition(appUri, clickPos)
            
            assertEquals(1, definitions.size, "Should find DEBUG field")
            assertTrue(definitions[0].uri.contains("Config.smali"),
                "Should navigate to Config.smali")
        }
    }
}
