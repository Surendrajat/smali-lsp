package xyz.surendrajat.smalilsp.integration.lsp

import xyz.surendrajat.smalilsp.shared.TestUtils

import org.eclipse.lsp4j.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import xyz.surendrajat.smalilsp.shared.E2ETestHarness
import xyz.surendrajat.smalilsp.shared.TestWorkspace
/**
 * E2E tests for Diagnostics feature.
 * Tests diagnostic computation on real-world Mastodon APK.
 */
class DiagnosticE2ETest {
    
    private lateinit var harness: E2ETestHarness
    private lateinit var workspace: TestWorkspace
    
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
        val uri = "file:///test.smali"
        
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
                uri,
                "smali",
                1,
                validContent
            )
        })

        val diagnostics = harness.waitForDiagnostics(uri)
        assertEquals(emptyList(), diagnostics, "Valid file should publish an empty diagnostics list")

        println("✅ Diagnostics published without crash")
    }
    
    @Test
    fun `diagnostics detect undefined class references`() {
        println("=== TEST: diagnostics detect undefined class references ===")
        
        val server = harness.server
        val uri = "file:///test.smali"
        
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
                uri,
                "smali",
                1,
                content
            )
        })

        val diagnostics = harness.waitForDiagnostics(uri)
        assertEquals(2, diagnostics.size, "Should publish one superclass warning and one undefined invoke target warning")
        assertTrue(diagnostics.any { it.message.contains("NonExistentSuperClass") }, "Should report the missing superclass")
        assertTrue(diagnostics.any { it.message.contains("UndefinedClass") }, "Should report the missing invoke target class")

        println("✅ Handled undefined class references")
    }
    
    @Test
    fun `diagnostics on Mastodon files`() {
        val mastodon = TestUtils.requireMastodonApk()
        
        println("=== TEST: diagnostics on Mastodon files ===")
        
        // Re-initialize with Mastodon workspace
        harness.cleanup()
        val tempDir = kotlin.io.path.createTempDirectory("smali-mastodon").toFile()
        workspace = TestWorkspace(tempDir)
        harness = E2ETestHarness(workspace)
        harness.initialize(
            workspaceFolders = listOf(
                WorkspaceFolder(mastodon.toURI().toString(), "mastodon")
            )
        )
        
        val server = harness.server
        
        // Sample 10 random files
        val smaliFiles = mastodon.walkTopDown()
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

            harness.waitForDiagnostics(uri, 5_000)

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

        val initialDiagnostics = harness.waitForDiagnostics(uri)
        assertTrue(initialDiagnostics.isNotEmpty(), "Invalid file should publish diagnostics before the edit")
        
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

        harness.client.waitForDiagnosticsCleared(uri)
        assertEquals(emptyList(), harness.getDiagnostics(uri), "Diagnostics should be cleared after fixing the file")
        
        println("✅ Diagnostics updated on change")
    }
    
    @Test
    fun `diagnostics cleared on file close`() {
        println("=== TEST: diagnostics cleared on file close ===")
        
        val server = harness.server
        val uri = "file:///test.smali"
        
        val invalidContent = """
            .class public LTest;
            .super Lcom/example/Missing;
        """.trimIndent()

        // Open file with diagnostics
        server.textDocumentService.didOpen(DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem(
                uri,
                "smali",
                1,
                invalidContent
            )
        })

        val diagnostics = harness.waitForDiagnostics(uri)
        assertTrue(diagnostics.isNotEmpty(), "File should have diagnostics before close")
        
        // Close file (should clear diagnostics)
        server.textDocumentService.didClose(DidCloseTextDocumentParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        })

        harness.client.waitForDiagnosticsCleared(uri)
        assertEquals(emptyList(), harness.getDiagnostics(uri), "Closing the file should clear diagnostics")
        
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
        val diagnostics = harness.waitForDiagnostics("file:///test.smali")
        val undefinedClassDiagnostics = diagnostics.count { it.message.contains("not found in workspace or SDK") }
        
        println("Massive file with 100 errors: ${elapsed}ms")
        assertTrue(elapsed < 1000, "Should handle massive file with errors in < 1s, took ${elapsed}ms")
        assertEquals(100, undefinedClassDiagnostics, "Should publish one undefined-class diagnostic per broken invoke")
        
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

        val diagnosticsByFile = (1..10).associateWith { i ->
            harness.waitForDiagnostics("file:///test$i.smali", 2_000)
        }
        
        println("Concurrent diagnostic requests (10 files): ${elapsed}ms")
        assertTrue(diagnosticsByFile.values.all { it.isEmpty() }, "All concurrent valid files should publish empty diagnostics")
        
        println("✅ Concurrent requests handled")
    }
}
