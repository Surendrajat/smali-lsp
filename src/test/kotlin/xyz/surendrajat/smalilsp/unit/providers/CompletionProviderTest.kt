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
        assertTrue(result.items.any {
            it.textEdit?.left?.newText == "Lcom/example/MyActivity;"
        })
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

    // --- textEdit replacement range ---

    @Test
    fun `opcode completion replaces typed prefix via textEdit`() {
        // User typed "invoke" at column 4 (after spaces)
        val line = "    invoke"
        val result = provider.provideCompletions("file:///test.smali", Position(5, 10), line)
        assertTrue(result.items.isNotEmpty())

        val item = result.items.first { it.label == "invoke-virtual" }
        val textEdit = item.textEdit?.left
        assertNotNull(textEdit, "CompletionItem must have a textEdit")
        // The textEdit should replace from column 4 (start of "invoke") to column 10 (cursor)
        assertEquals(4, textEdit.range.start.character)
        assertEquals(10, textEdit.range.end.character)
        assertEquals("invoke-virtual", textEdit.newText)
    }

    @Test
    fun `member completion replaces typed prefix via textEdit`() {
        val content = """
            .class public Lcom/example/Service;
            .super Ljava/lang/Object;
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

        val line = "    invoke-virtual {v0}, Lcom/example/Service;->st"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty())

        val item = result.items.first { it.label.contains("start") }
        val textEdit = item.textEdit?.left
        assertNotNull(textEdit, "Member CompletionItem must have a textEdit")
        // Should replace "st" (2 chars before cursor)
        assertEquals(line.length - 2, textEdit.range.start.character)
        assertEquals(line.length, textEdit.range.end.character)
    }

    // --- Specific opcode completion tests ---

    @Test
    fun `invoke-static appears in completions for invoke-st prefix`() {
        val line = "    invoke-st"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty(), "Should have completions for 'invoke-st'")
        assertTrue(result.items.any { it.label == "invoke-static" }, "Should include invoke-static")
        assertTrue(result.items.any { it.label == "invoke-static/range" }, "Should include invoke-static/range")
    }

    @Test
    fun `return prefix completes return-void and variants`() {
        val line = "    return"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty(), "Should have completions for 'return'")
        val labels = result.items.map { it.label }
        assertTrue(labels.contains("return-void"), "Should include return-void, got: $labels")
        assertTrue(labels.contains("return"), "Should include return, got: $labels")
        assertTrue(labels.contains("return-wide"), "Should include return-wide, got: $labels")
        assertTrue(labels.contains("return-object"), "Should include return-object, got: $labels")
    }

    @Test
    fun `const prefix completes const-4 and variants`() {
        val line = "    const"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty(), "Should have completions for 'const'")
        val labels = result.items.map { it.label }
        assertTrue(labels.contains("const/4"), "Should include const/4, got: $labels")
        assertTrue(labels.contains("const/16"), "Should include const/16, got: $labels")
        assertTrue(labels.contains("const-string"), "Should include const-string, got: $labels")
    }

    @Test
    fun `aput prefix completes aput-object and variants`() {
        val line = "    aput"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty(), "Should have completions for 'aput'")
        val labels = result.items.map { it.label }
        assertTrue(labels.contains("aput"), "Should include aput, got: $labels")
        assertTrue(labels.contains("aput-object"), "Should include aput-object, got: $labels")
        assertTrue(labels.contains("aput-wide"), "Should include aput-wide, got: $labels")
    }

    @Test
    fun `move prefix completes move variants`() {
        val line = "    move"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty(), "Should have completions for 'move'")
        val labels = result.items.map { it.label }
        assertTrue(labels.contains("move"), "Should include move, got: $labels")
        assertTrue(labels.contains("move-object"), "Should include move-object, got: $labels")
        assertTrue(labels.contains("move-result"), "Should include move-result, got: $labels")
    }

    @Test
    fun `add-int prefix completes add-int variants`() {
        val line = "    add-int"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty(), "Should have completions for 'add-int'")
        val labels = result.items.map { it.label }
        assertTrue(labels.contains("add-int"), "Should include add-int, got: $labels")
        assertTrue(labels.contains("add-int/2addr"), "Should include add-int/2addr, got: $labels")
        assertTrue(labels.contains("add-int/lit8"), "Should include add-int/lit8, got: $labels")
        assertTrue(labels.contains("add-int/lit16"), "Should include add-int/lit16, got: $labels")
    }

    @Test
    fun `single char n completes nop and neg variants`() {
        val line = "    n"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty(), "Should have completions for 'n'")
        val labels = result.items.map { it.label }
        assertTrue(labels.contains("nop"), "Should include nop, got: $labels")
        assertTrue(labels.any { it.startsWith("neg-") }, "Should include neg variants, got: $labels")
        assertTrue(labels.any { it.startsWith("new-") }, "Should include new variants, got: $labels")
    }
}
