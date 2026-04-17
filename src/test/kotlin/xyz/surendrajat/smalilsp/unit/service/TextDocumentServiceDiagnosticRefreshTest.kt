package xyz.surendrajat.smalilsp.unit.service

import org.eclipse.lsp4j.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.SmaliTextDocumentService
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.*
import xyz.surendrajat.smalilsp.shared.TestLSPClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for diagnostic refresh after indexing completes.
 * Verifies that stale "class not found" diagnostics are cleared
 * when the workspace index becomes complete.
 */
class TextDocumentServiceDiagnosticRefreshTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var service: SmaliTextDocumentService
    private lateinit var client: TestLSPClient

    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        service = SmaliTextDocumentService(
            index,
            DefinitionProvider(index),
            HoverProvider(index),
            ReferenceProvider(index),
            CallHierarchyProvider(index),
            TypeHierarchyProvider(index),
            CodeLensProvider(index),
            CompletionProvider(index),
            RenameProvider(index)
        )
        client = TestLSPClient()
        service.connect(client)
    }

    @Test
    fun `didOpen during indexing suppresses semantic diagnostics`() {
        // indexingComplete defaults to false
        service.indexingComplete = false

        val content = """
            .class public Lcom/example/Test;
            .super Lcom/example/MissingClass;
            
            .method public foo()V
                return-void
            .end method
        """.trimIndent()

        service.didOpen(DidOpenTextDocumentParams(
            TextDocumentItem("file:///Test.smali", "smali", 1, content)
        ))

        // During indexing, only syntax errors should appear - no semantic "class not found"
        val diagnostics = client.getDiagnostics("file:///Test.smali")
        val undefinedClassDiags = diagnostics.filter { it.code?.left == "undefined-class" }
        assertEquals(0, undefinedClassDiags.size,
            "Should NOT show undefined-class warnings during indexing")
    }

    @Test
    fun `didOpen after indexing includes semantic diagnostics`() {
        service.indexingComplete = true

        val content = """
            .class public Lcom/example/Test;
            .super Lcom/example/MissingClass;
            
            .method public foo()V
                return-void
            .end method
        """.trimIndent()

        service.didOpen(DidOpenTextDocumentParams(
            TextDocumentItem("file:///Test.smali", "smali", 1, content)
        ))

        val diagnostics = client.getDiagnostics("file:///Test.smali")
        val undefinedClassDiags = diagnostics.filter { it.code?.left == "undefined-class" }
        assertTrue(undefinedClassDiags.isNotEmpty(),
            "Should show undefined-class warnings after indexing completes")
    }

    @Test
    fun `refreshDiagnosticsForOpenFiles clears stale warnings`() {
        service.indexingComplete = false

        // Simulate: user opens file while indexing is in-progress
        val testUri = "file:///Test.smali"
        val testContent = """
            .class public Lcom/example/Test;
            .super Lcom/example/Dependency;
            
            .method public foo()V
                return-void
            .end method
        """.trimIndent()

        service.didOpen(DidOpenTextDocumentParams(
            TextDocumentItem(testUri, "smali", 1, testContent)
        ))

        // At this point, no semantic diagnostics (indexing not complete)
        val duringIndexing = client.getDiagnostics(testUri)
        assertEquals(0, duringIndexing.filter { it.code?.left == "undefined-class" }.size)

        // Now simulate: indexing completes and indexes the dependency class
        val depContent = """
            .class public Lcom/example/Dependency;
            .super Ljava/lang/Object;
        """.trimIndent()
        val parser = xyz.surendrajat.smalilsp.parser.SmaliParser()
        parser.parse("file:///Dependency.smali", depContent)?.let { index.indexFile(it) }

        // Mark indexing complete and refresh
        service.indexingComplete = true
        service.refreshDiagnosticsForOpenFiles()

        // After refresh, the dependency IS in the index — no warnings expected
        val afterRefresh = client.getDiagnostics(testUri)
        val undefinedAfterRefresh = afterRefresh.filter { it.code?.left == "undefined-class" }
        assertEquals(0, undefinedAfterRefresh.size,
            "After indexing + refresh, dependency class should be found — no warnings")
    }

    @Test
    fun `refreshDiagnosticsForOpenFiles shows real warnings for truly missing classes`() {
        service.indexingComplete = false

        val testUri = "file:///Test.smali"
        val testContent = """
            .class public Lcom/example/Test;
            .super Lcom/example/ReallyMissing;
            
            .method public foo()V
                return-void
            .end method
        """.trimIndent()

        service.didOpen(DidOpenTextDocumentParams(
            TextDocumentItem(testUri, "smali", 1, testContent)
        ))

        // Mark indexing complete and refresh — but ReallyMissing was never indexed
        service.indexingComplete = true
        service.refreshDiagnosticsForOpenFiles()

        val afterRefresh = client.getDiagnostics(testUri)
        val undefinedDiags = afterRefresh.filter { it.code?.left == "undefined-class" }
        assertTrue(undefinedDiags.isNotEmpty(),
            "Truly missing classes should still show warnings after indexing refresh")
        assertTrue(undefinedDiags.any { it.message.contains("ReallyMissing") })
    }

    @Test
    fun `didChange during indexing also suppresses semantic diagnostics`() {
        service.indexingComplete = false

        val uri = "file:///Test.smali"
        val initial = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
        """.trimIndent()

        service.didOpen(DidOpenTextDocumentParams(
            TextDocumentItem(uri, "smali", 1, initial)
        ))
        // Baseline: open during indexing → no undefined-class warnings
        assertEquals(0, client.getDiagnostics(uri)
            .filter { it.code?.left == "undefined-class" }.size)

        // User types a character — edit introduces a class ref to a not-yet-indexed class
        val edited = """
            .class public Lcom/example/Test;
            .super Lcom/example/NotIndexedYet;
        """.trimIndent()

        service.didChange(DidChangeTextDocumentParams().apply {
            textDocument = VersionedTextDocumentIdentifier(uri, 2)
            contentChanges = listOf(TextDocumentContentChangeEvent(edited))
        })

        // With fix: edits during indexing also suppress semantic warnings
        val afterEdit = client.getDiagnostics(uri)
        val undefinedAfterEdit = afterEdit.filter { it.code?.left == "undefined-class" }
        assertEquals(0, undefinedAfterEdit.size,
            "didChange during indexing should suppress semantic warnings like didOpen does")
    }

    @Test
    fun `didClose removes from open file tracking`() {
        service.indexingComplete = true

        val testUri = "file:///Test.smali"
        val testContent = """
            .class public Lcom/example/Test;
            .super Lcom/example/Missing;
        """.trimIndent()

        service.didOpen(DidOpenTextDocumentParams(
            TextDocumentItem(testUri, "smali", 1, testContent)
        ))

        // Close the file
        service.didClose(DidCloseTextDocumentParams(
            TextDocumentIdentifier(testUri)
        ))

        // Diagnostics should be cleared
        val afterClose = client.getDiagnostics(testUri)
        assertEquals(0, afterClose.size, "Diagnostics should be cleared on close")

        // refresh should not re-publish for closed file
        client.clearAll()
        service.refreshDiagnosticsForOpenFiles()
        val afterRefresh = client.getDiagnostics(testUri)
        assertEquals(0, afterRefresh.size, "Closed files should not get refreshed diagnostics")
    }
}
