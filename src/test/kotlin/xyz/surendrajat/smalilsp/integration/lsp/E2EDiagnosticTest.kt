package xyz.surendrajat.smalilsp.integration.lsp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

import xyz.surendrajat.smalilsp.integration.lsp.E2ETestHarness
import xyz.surendrajat.smalilsp.integration.lsp.TestWorkspace
/**
 * Diagnostic test to understand why E2E tests are being skipped
 */
@DisplayName("E2E Diagnostic Tests")
class E2EDiagnosticTest {
    
    @BeforeEach
    fun setup() {
        println("=== SETUP: E2EDiagnosticTest ===")
    }
    
    @AfterEach
    fun teardown() {
        println("=== TEARDOWN: E2EDiagnosticTest ===")
    }
    
    @Test
    @DisplayName("Simple test that should pass")
    fun simpleTestThatShouldPass() {
        println("=== RUNNING: simpleTestThatShouldPass ===")
        assertEquals(1, 1)
        println("=== SUCCESS: simpleTestThatShouldPass ===")
    }
    
    @Test
    @DisplayName("Test with backticks in function name")
    fun `test with backticks in function name`() {
        println("=== RUNNING: test with backticks ===")
        assertEquals(2, 2)
        println("=== SUCCESS: test with backticks ===")
    }
    
    @Test
    @DisplayName("Test that creates temp workspace")
    fun testThatCreatesTempWorkspace() {
        println("=== RUNNING: testThatCreatesTempWorkspace ===")
        
        try {
            val workspace = TestWorkspace.createTemp("""
                .class public LTest;
                .super Ljava/lang/Object;
            """.trimIndent())
            
            println("Created workspace at: ${workspace.baseDir}")
            println("Root URI: ${workspace.rootUri}")
            
            assertTrue(workspace.baseDir.exists(), "Workspace directory should exist")
            
            workspace.cleanup()
            println("=== SUCCESS: testThatCreatesTempWorkspace ===")
            
        } catch (e: Exception) {
            println("=== EXCEPTION in testThatCreatesTempWorkspace: ${e.message} ===")
            e.printStackTrace()
            throw e
        }
    }
    
    @Test
    @DisplayName("Test that initializes E2E harness")
    fun testThatInitializesE2EHarness() {
        println("=== RUNNING: testThatInitializesE2EHarness ===")
        
        var workspace: TestWorkspace? = null
        var harness: E2ETestHarness? = null
        
        try {
            println("Step 1: Creating workspace...")
            workspace = TestWorkspace.createTemp("""
                .class public LTest;
                .super Ljava/lang/Object;
            """.trimIndent())
            println("Step 1: SUCCESS - workspace created at ${workspace.baseDir}")
            
            println("Step 2: Creating E2E harness...")
            harness = E2ETestHarness(workspace)
            println("Step 2: SUCCESS - harness created")
            
            println("Step 3: Initializing harness...")
            harness.initialize()
            println("Step 3: SUCCESS - harness initialized")
            
            println("Step 4: Checking initialization status...")
            val isInit = harness.checkInitialized()
            println("Step 4: SUCCESS - checkInitialized() returned $isInit")
            
            println("Step 5: Asserting...")
            assertTrue(isInit, "Harness should be initialized")
            println("Step 5: SUCCESS - assertion passed")
            
            println("Step 6: Cleaning up...")
            harness.cleanup()
            println("Step 6: SUCCESS - cleanup complete")
            
            println("=== SUCCESS: testThatInitializesE2EHarness ===")
            
        } catch (e: Throwable) {
            println("=== EXCEPTION in testThatInitializesE2EHarness ===")
            println("Exception type: ${e.javaClass.name}")
            println("Exception message: ${e.message}")
            println("Stack trace:")
            println(e.stackTraceToString())
            println("=== END EXCEPTION ===")
            
            // Clean up on error
            harness?.cleanup()
            
            throw e
        }
    }
}
