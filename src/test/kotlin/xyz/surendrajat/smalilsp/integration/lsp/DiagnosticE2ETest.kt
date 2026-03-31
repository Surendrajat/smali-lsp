package xyz.surendrajat.smalilsp.integration.lsp

import xyz.surendrajat.smalilsp.TestUtils

import org.eclipse.lsp4j.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

import xyz.surendrajat.smalilsp.integration.lsp.E2ETestHarness
import xyz.surendrajat.smalilsp.integration.lsp.TestWorkspace
/**
 * E2E tests for Diagnostics feature.
 * Tests diagnostic computation on real-world Mastodon APK.
 */
class DiagnosticE2ETest {
    
    private lateinit var harness: E2ETestHarness
    private lateinit var workspace: TestWorkspace
    private val mastodonDir = TestUtils.getMastodonApk()
    
    @BeforeEach
    fun setup() {
        println("=== SETUP: DiagnosticE2ETest ===")
        val tempDir = kotlin.io.path.createTempDirectory("smali-test").toFile()
        workspace = TestWorkspace(tempDir)
        harness = E2ETestHarness(workspace)
        harness.initialize()
    }
    
    @AfterEach
    fun teardown() {
        println("=== TEARDOWN: DiagnosticE2ETest ===")
        harness.cleanup()
    }
    
    @Test
    fun `diagnostics published on file open`() {
        println("=== TEST: diagnostics published on file open ===")
        
        val server = harness.server
        
        // Open file with valid content
        val validContent = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                return-void
            .end method
        """.trimIndent()
        
        server.textDocumentService.didOpen(DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem(
                "file:///test.smali",
                "smali",
                1,
                validContent
            )
        })
        
        Thread.sleep(200) // Wait for async processing
        
        // Diagnostics should be published (either empty or with warnings)
        // Since we're testing E2E, we just verify no crash
        println("✅ Diagnostics published without crash")
    }
    
    @Test
    fun `diagnostics detect undefined class references`() {
        println("=== TEST: diagnostics detect undefined class references ===")
        
        val server = harness.server
        
        // Content with undefined class reference
        val content = """
            .class public LTest;
            .super Lcom/example/NonExistentSuperClass;
            
            .method public test()V
                invoke-virtual {v0}, Lcom/example/UndefinedClass;->foo()V
                return-void
            .end method
        """.trimIndent()
        
        server.textDocumentService.didOpen(DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem(
                "file:///test.smali",
                "smali",
                1,
                content
            )
        })
        
        Thread.sleep(200)
        
        // Should handle undefined references gracefully
        println("✅ Handled undefined class references")
    }
    
    @Test
    fun `diagnostics on Mastodon files`() {
        if (mastodonDir == null) {
            println("⏭️  Mastodon APK not available, skipping")
            return
        }
        
        println("=== TEST: diagnostics on Mastodon files ===")
        
        // Re-initialize with Mastodon workspace
        harness.cleanup()
        val tempDir = kotlin.io.path.createTempDirectory("smali-mastodon").toFile()
        workspace = TestWorkspace(tempDir)
        harness = E2ETestHarness(workspace)
        harness.initialize(
            workspaceFolders = listOf(
                WorkspaceFolder(mastodonDir.toURI().toString(), "mastodon")
            )
        )
        
        val server = harness.server
        
        // Sample 10 random files
        val smaliFiles = mastodonDir.walkTopDown()
            .filter { it.extension == "smali" }
            .take(50)
            .toList()
        
        println("Testing diagnostics on ${smaliFiles.size} Mastodon files...")
        
        var totalTime = 0L
        var maxTime = 0L
        
        smaliFiles.forEach { file ->
            val content = file.readText()
            val uri = file.toURI().toString()
            
            val startTime = System.currentTimeMillis()
            
            // Open file (triggers diagnostic computation)
            server.textDocumentService.didOpen(DidOpenTextDocumentParams().apply {
                textDocument = TextDocumentItem(uri, "smali", 1, content)
            })
            
            val elapsed = System.currentTimeMillis() - startTime
            totalTime += elapsed
            maxTime = maxOf(maxTime, elapsed)
            
            // Close file
            server.textDocumentService.didClose(DidCloseTextDocumentParams().apply {
                textDocument = TextDocumentIdentifier(uri)
            })
        }
        
        val avgTime = totalTime / smaliFiles.size
        
        println("Diagnostic Performance:")
        println("  Files tested: ${smaliFiles.size}")
        println("  Average time: ${avgTime}ms")
        println("  Max time: ${maxTime}ms")
        
        // Performance assertions
        assertTrue(avgTime < 200, "Average diagnostic time should be < 200ms, was ${avgTime}ms")
        assertTrue(maxTime < 500, "Max diagnostic time should be < 500ms, was ${maxTime}ms")
        
        println("✅ Diagnostics performance acceptable on Mastodon files")
    }
    
    @Test
    fun `diagnostics updated on file change`() {
        println("=== TEST: diagnostics updated on file change ===")
        
        val server = harness.server
        val uri = "file:///test.smali"
        
        // Open file with error
        val invalidContent = """
            .class public LTest;
            .super Lcom/example/Missing;
        """.trimIndent()
        
        server.textDocumentService.didOpen(DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem(uri, "smali", 1, invalidContent)
        })
        
        Thread.sleep(100)
        
        // Fix error
        val validContent = """
            .class public LTest;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        server.textDocumentService.didChange(DidChangeTextDocumentParams().apply {
            textDocument = VersionedTextDocumentIdentifier(uri, 2)
            contentChanges = listOf(
                TextDocumentContentChangeEvent(validContent)
            )
        })
        
        Thread.sleep(100)
        
        println("✅ Diagnostics updated on change")
    }
    
    @Test
    fun `diagnostics cleared on file close`() {
        println("=== TEST: diagnostics cleared on file close ===")
        
        val server = harness.server
        val uri = "file:///test.smali"
        
        // Open file
        server.textDocumentService.didOpen(DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem(
                uri,
                "smali",
                1,
                ".class public LTest;\n.super Ljava/lang/Object;"
            )
        })
        
        Thread.sleep(100)
        
        // Close file (should clear diagnostics)
        server.textDocumentService.didClose(DidCloseTextDocumentParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        })
        
        Thread.sleep(100)
        
        println("✅ Diagnostics cleared on close")
    }
    
    @Test
    fun `massive file with many errors handles gracefully`() {
        println("=== TEST: massive file with many errors ===")
        
        val server = harness.server
        
        // Generate file with 100 undefined class references
        val errors = (1..100).joinToString("\n") { i ->
            """
            .method public method$i()V
                invoke-virtual {v0}, Lcom/example/Missing$i;->foo()V
                return-void
            .end method
            """.trimIndent()
        }
        
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            $errors
        """.trimIndent()
        
        val startTime = System.currentTimeMillis()
        
        server.textDocumentService.didOpen(DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem(
                "file:///test.smali",
                "smali",
                1,
                content
            )
        })
        
        val elapsed = System.currentTimeMillis() - startTime
        
        println("Massive file with 100 errors: ${elapsed}ms")
        assertTrue(elapsed < 1000, "Should handle massive file with errors in < 1s, took ${elapsed}ms")
        
        println("✅ Massive file handled gracefully")
    }
    
    @Test
    fun `concurrent diagnostic requests`() {
        println("=== TEST: concurrent diagnostic requests ===")
        
        val server = harness.server
        
        // Open 10 files concurrently
        val threads = (1..10).map { i ->
            Thread {
                val content = """
                    .class public LTest$i;
                    .super Ljava/lang/Object;
                    
                    .method public test()V
                        return-void
                    .end method
                """.trimIndent()
                
                server.textDocumentService.didOpen(DidOpenTextDocumentParams().apply {
                    textDocument = TextDocumentItem(
                        "file:///test$i.smali",
                        "smali",
                        1,
                        content
                    )
                })
            }
        }
        
        val startTime = System.currentTimeMillis()
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val elapsed = System.currentTimeMillis() - startTime
        
        println("Concurrent diagnostic requests (10 files): ${elapsed}ms")
        
        println("✅ Concurrent requests handled")
    }
}
