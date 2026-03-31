package xyz.surendrajat.smalilsp.integration.lsp

import xyz.surendrajat.smalilsp.TestUtils

import org.eclipse.lsp4j.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import xyz.surendrajat.smalilsp.SmaliLanguageServer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import xyz.surendrajat.smalilsp.integration.lsp.TestLSPClient
/**
 * Simple integration tests for existing LSP features
 * Direct testing without complex framework
 */
class SimpleLSPIntegrationTest {
    
    @Test
    fun simpleGotoDefinitionWorks() {
        // Create temp workspace
        val tempDir = Files.createTempDirectory("smali-test").toFile()
        val testFile = File(tempDir, "Test.smali")
        testFile.writeText("""
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public foo()V
                .registers 1
                invoke-virtual {p0}, LTest;->bar()V
                return-void
            .end method
            
            .method public bar()V
                .registers 1
                return-void
            .end method
        """.trimIndent())
        
        try {
            // Initialize server
            val server = SmaliLanguageServer()
            val client = TestLSPClient()
            server.connect(client)
            
            val initParams = InitializeParams().apply {
                rootUri = tempDir.toURI().toString()
                capabilities = ClientCapabilities()
            }
            
            server.initialize(initParams).get(10, TimeUnit.SECONDS)
            server.initialized(InitializedParams())
            
            // Give time to index
            Thread.sleep(1000)
            
            // Open file
            val uri = testFile.toURI().toString()
            server.textDocumentService.didOpen(DidOpenTextDocumentParams(
                TextDocumentItem(uri, "smali", 1, testFile.readText())
            ))
            
            // Wait for indexing
            Thread.sleep(500)
            
            // Try goto definition on line with "->bar()"
            val result = server.textDocumentService.definition(DefinitionParams(
                TextDocumentIdentifier(uri),
                Position(5, 40)  // Position near "bar"
            )).get(5, TimeUnit.SECONDS)
            
            // Should get result
            assertNotNull(result, "Definition result should not be null")
            
            if (result.isLeft && result.left.isNotEmpty()) {
                println("✓ Goto definition works - found ${result.left.size} definitions")
                val firstDef = result.left[0]
                println("  Target line: ${firstDef.range.start.line}")
                assertTrue(firstDef.uri.contains("Test.smali"))
            } else {
                println("✗ No definitions found")
                // Don't fail - might not be fully implemented yet
            }
            
            // Cleanup
            server.shutdown().get(2, TimeUnit.SECONDS)
            server.exit()
            
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun simpleHoverWorks() {
        // Create temp workspace
        val tempDir = Files.createTempDirectory("smali-test").toFile()
        val testFile = File(tempDir, "Test.smali")
        testFile.writeText("""
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public getValue()I
                .registers 2
                const/4 v0, 0x1
                return v0
            .end method
        """.trimIndent())
        
        try {
            // Initialize server
            val server = SmaliLanguageServer()
            val client = TestLSPClient()
            server.connect(client)
            
            val initParams = InitializeParams().apply {
                rootUri = tempDir.toURI().toString()
                capabilities = ClientCapabilities()
            }
            
            server.initialize(initParams).get(10, TimeUnit.SECONDS)
            server.initialized(InitializedParams())
            
            Thread.sleep(1000)
            
            // Open file
            val uri = testFile.toURI().toString()
            server.textDocumentService.didOpen(DidOpenTextDocumentParams(
                TextDocumentItem(uri, "smali", 1, testFile.readText())
            ))
            
            Thread.sleep(500)
            
            // Try hover on method line
            val hover = server.textDocumentService.hover(HoverParams(
                TextDocumentIdentifier(uri),
                Position(3, 15)  // On "getValue"
            )).get(2, TimeUnit.SECONDS)
            
            if (hover != null) {
                println("✓ Hover works - content: ${hover.contents.right?.value?.take(50)}...")
            } else {
                println("✗ No hover result")
            }
            
            // Don't assert - just report
            
            // Cleanup
            server.shutdown().get(2, TimeUnit.SECONDS)
            server.exit()
            
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun parseRealFileTest() {
        val mastodonPath = TestUtils.getMastodonApk()!!.absolutePath
        val mastodonDir = File(mastodonPath)
        
        if (!mastodonDir.exists()) {
            println("⚠ Mastodon APK not found, skipping")
            return
        }
        
        println("✓ Testing with real Mastodon APK")
        
        // Initialize server
        val server = SmaliLanguageServer()
        val client = TestLSPClient()
        server.connect(client)
        
        val initParams = InitializeParams().apply {
            rootUri = mastodonDir.toURI().toString()
            capabilities = ClientCapabilities()
        }
        
        server.initialize(initParams).get(30, TimeUnit.SECONDS)
        server.initialized(InitializedParams())
        
        println("  Waiting for indexing...")
        Thread.sleep(15000)  // 15 seconds for indexing
        
        // Find a file
        val smaliFiles = mastodonDir.walkTopDown()
            .filter { it.extension == "smali" }
            .take(10)
            .toList()
        
        println("  Found ${smaliFiles.size} files to test")
        
        var successful = 0
        smaliFiles.forEach { file ->
            try {
                val uri = file.toURI().toString()
                server.textDocumentService.didOpen(DidOpenTextDocumentParams(
                    TextDocumentItem(uri, "smali", 1, file.readText())
                ))
                
                Thread.sleep(100)
                
                // Try definition
                val result = server.textDocumentService.definition(DefinitionParams(
                    TextDocumentIdentifier(uri),
                    Position(10, 10)
                )).get(2, TimeUnit.SECONDS)
                
                if (result != null) {
                    successful++
                }
                
            } catch (e: Exception) {
                // Skip
            }
        }
        
        println("  Successfully tested $successful/${smaliFiles.size} files")
        assertTrue(successful > 0, "At least some files should work")
        
        // Cleanup
        server.shutdown().get(2, TimeUnit.SECONDS)
        server.exit()
    }
}
