package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.CompletionProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompletionProviderTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var parser: SmaliParser
    private lateinit var provider: CompletionProvider

    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        parser = SmaliParser()
        provider = CompletionProvider(index)
    }

    private fun indexContent(uri: String, content: String) {
        val file = parser.parse(uri, content)
        assertNotNull(file, "Failed to parse $uri")
        index.indexFile(file)
    }

    // --- Opcode completion ---

    @Test
    fun `completes opcodes at line start`() {
        val result = provider.provideCompletions("file:///test.smali", Position(0, 6), "invoke")
        assertTrue(result.items.isNotEmpty(), "Should have opcode completions")
        assertTrue(result.items.any { it.label.startsWith("invoke-") })
    }

    @Test
    fun `completes partial opcodes`() {
        val result = provider.provideCompletions("file:///test.smali", Position(0, 3), "ige")
        assertTrue(result.items.isNotEmpty())
        assertTrue(result.items.any { it.label.startsWith("iget") })
    }

    @Test
    fun `no opcode completion for directives`() {
        val result = provider.provideCompletions("file:///test.smali", Position(0, 6), ".class")
        assertTrue(result.items.isEmpty(), "Directives should not trigger opcode completion")
    }

    @Test
    fun `no opcode completion for comments`() {
        val result = provider.provideCompletions("file:///test.smali", Position(0, 10), "# some text")
        assertTrue(result.items.isEmpty(), "Comments should not trigger completion")
    }

    // --- Class name completion ---

    @Test
    fun `completes class names with L-prefix`() {
        val content = """
            .class public Lcom/example/MyActivity;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/MyActivity.smali", content)

        val lineText = "    new-instance v0, Lcom/exam"
        val result = provider.provideCompletions("file:///test.smali", Position(0, lineText.length), lineText)
        assertTrue(result.items.isNotEmpty(), "Should complete partial class name")
        assertTrue(result.items.any { it.detail == "Lcom/example/MyActivity;" })
    }

    @Test
    fun `completes class names in super directive`() {
        val content = """
            .class public Lcom/example/Base;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Base.smali", content)

        val result = provider.provideCompletions("file:///test.smali", Position(0, 10), ".super Bas")
        assertTrue(result.items.isNotEmpty(), "Should complete class names in .super")
    }

    @Test
    fun `completes class names in implements directive`() {
        val content = """
            .class public interface abstract Lcom/example/MyInterface;
            .super Ljava/lang/Object;
        """.trimIndent()
        indexContent("file:///test/MyInterface.smali", content)

        val result = provider.provideCompletions("file:///test.smali", Position(0, 17), ".implements MyIn")
        assertTrue(result.items.isNotEmpty(), "Should complete class names in .implements")
    }

    // --- Member completion (after ->) ---

    @Test
    fun `completes methods after arrow`() {
        val content = """
            .class public Lcom/example/Service;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public start()V
                .registers 1
                return-void
            .end method
            .method public stop()V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Service.smali", content)

        val result = provider.provideCompletions(
            "file:///test.smali",
            Position(0, 52),
            "    invoke-virtual {v0}, Lcom/example/Service;->st"
        )
        assertTrue(result.items.isNotEmpty(), "Should complete method names")
        assertTrue(result.items.any { it.label.contains("start") })
        assertTrue(result.items.any { it.label.contains("stop") })
    }

    @Test
    fun `completes fields after arrow`() {
        val content = """
            .class public Lcom/example/Data;
            .super Ljava/lang/Object;
            .field public name:Ljava/lang/String;
            .field public count:I
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Data.smali", content)

        val result = provider.provideCompletions(
            "file:///test.smali",
            Position(0, 44),
            "    iget v0, p0, Lcom/example/Data;->n"
        )
        assertTrue(result.items.isNotEmpty(), "Should complete field names")
        assertTrue(result.items.any { it.label.contains("name") })
    }

    @Test
    fun `no completions for unknown class after arrow`() {
        val result = provider.provideCompletions(
            "file:///test.smali",
            Position(0, 52),
            "    invoke-virtual {v0}, Lcom/nonexistent/Foo;->bar"
        )
        assertTrue(result.items.isEmpty(), "Unknown class should return empty completions")
    }

    // --- Edge cases ---

    @Test
    fun `empty line returns empty completions`() {
        val result = provider.provideCompletions("file:///test.smali", Position(0, 0), "")
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `position beyond line length handled gracefully`() {
        val result = provider.provideCompletions("file:///test.smali", Position(0, 100), "short")
        // Should not throw, should return something (opcodes in this case)
        assertNotNull(result)
    }

    @Test
    fun `completion list limited to 50 class results`() {
        // Index many classes
        for (i in 1..60) {
            val content = """
                .class public Lcom/example/Gen$i;
                .super Ljava/lang/Object;
            """.trimIndent()
            indexContent("file:///test/Gen$i.smali", content)
        }

        val result = provider.provideCompletions(
            "file:///test.smali",
            Position(0, 19),
            "    new-instance v0, LGen"
        )
        assertTrue(result.items.size <= 50, "Class completions should be capped at 50")
    }
}
