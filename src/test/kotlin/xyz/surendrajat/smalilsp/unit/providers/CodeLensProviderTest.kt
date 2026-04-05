package xyz.surendrajat.smalilsp.unit.providers

import com.google.gson.JsonObject
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.CodeLensProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodeLensProviderTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var parser: SmaliParser
    private lateinit var provider: CodeLensProvider

    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        parser = SmaliParser()
        provider = CodeLensProvider(index)
    }

    private fun indexContent(uri: String, content: String) {
        val file = parser.parse(uri, content)
        assertNotNull(file, "Failed to parse $uri")
        index.indexFile(file)
    }

    @Test
    fun `provides code lens for methods`() {
        val content = """
            .class public Lcom/example/Foo;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public doWork()V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Foo.smali", content)

        val lenses = provider.provideCodeLenses("file:///test/Foo.smali")
        // Should have lenses for <init> and doWork
        assertTrue(lenses.size >= 2, "Expected at least 2 lenses (methods), got ${lenses.size}")
        val methodLenses = lenses.filter {
            (it.data as? JsonObject)?.get("memberType")?.asString == "method"
        }
        assertTrue(methodLenses.size >= 2)
    }

    @Test
    fun `provides code lens for fields`() {
        val content = """
            .class public Lcom/example/Foo;
            .super Ljava/lang/Object;
            .field private name:Ljava/lang/String;
            .field public count:I
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Foo.smali", content)

        val lenses = provider.provideCodeLenses("file:///test/Foo.smali")
        val fieldLenses = lenses.filter {
            (it.data as? JsonObject)?.get("memberType")?.asString == "field"
        }
        assertEquals(2, fieldLenses.size, "Expected 2 field lenses")
    }

    @Test
    fun `unresolved lenses have no command`() {
        val content = """
            .class public Lcom/example/Foo;
            .super Ljava/lang/Object;
            .method public doWork()V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Foo.smali", content)

        val lenses = provider.provideCodeLenses("file:///test/Foo.smali")
        assertTrue(lenses.isNotEmpty())
        for (lens in lenses) {
            assertEquals(null, lens.command, "Unresolved lens should have null command")
        }
    }

    @Test
    fun `resolve lens shows reference count for method`() {
        val targetContent = """
            .class public Lcom/example/Target;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public doWork()V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        val callerContent = """
            .class public Lcom/example/Caller;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public go()V
                .registers 2
                new-instance v0, Lcom/example/Target;
                invoke-direct {v0}, Lcom/example/Target;-><init>()V
                invoke-virtual {v0}, Lcom/example/Target;->doWork()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Target.smali", targetContent)
        indexContent("file:///test/Caller.smali", callerContent)

        val lenses = provider.provideCodeLenses("file:///test/Target.smali")
        val doWorkLens = lenses.find {
            (it.data as? JsonObject)?.get("memberName")?.asString == "doWork"
        }
        assertNotNull(doWorkLens, "Should have lens for doWork")

        val resolved = provider.resolveCodeLens(doWorkLens)
        assertNotNull(resolved.command)
        assertTrue(resolved.command.title.contains("1 reference"), "Expected '1 reference', got '${resolved.command.title}'")
    }

    @Test
    fun `resolve lens shows 0 references for unreferenced method`() {
        val content = """
            .class public Lcom/example/Lonely;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public unused()V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Lonely.smali", content)

        val lenses = provider.provideCodeLenses("file:///test/Lonely.smali")
        val unusedLens = lenses.find {
            (it.data as? JsonObject)?.get("memberName")?.asString == "unused"
        }
        assertNotNull(unusedLens)

        val resolved = provider.resolveCodeLens(unusedLens)
        assertNotNull(resolved.command)
        assertTrue(resolved.command.title.contains("0 references"))
    }

    @Test
    fun `resolve lens counts field references`() {
        val classContent = """
            .class public Lcom/example/Data;
            .super Ljava/lang/Object;
            .field public value:I
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        val readerContent = """
            .class public Lcom/example/Reader;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public read()V
                .registers 3
                new-instance v0, Lcom/example/Data;
                invoke-direct {v0}, Lcom/example/Data;-><init>()V
                iget v1, v0, Lcom/example/Data;->value:I
                iget v2, v0, Lcom/example/Data;->value:I
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Data.smali", classContent)
        indexContent("file:///test/Reader.smali", readerContent)

        val lenses = provider.provideCodeLenses("file:///test/Data.smali")
        val valueLens = lenses.find {
            (it.data as? JsonObject)?.get("memberName")?.asString == "value"
        }
        assertNotNull(valueLens)

        val resolved = provider.resolveCodeLens(valueLens)
        assertNotNull(resolved.command)
        assertTrue(resolved.command.title.contains("2 references"), "Expected '2 references', got '${resolved.command.title}'")
    }

    @Test
    fun `returns empty for unknown URI`() {
        val lenses = provider.provideCodeLenses("file:///nonexistent.smali")
        assertTrue(lenses.isEmpty())
    }

    @Test
    fun `resolve lens with null data returns fallback`() {
        val lens = CodeLens(Range(Position(0, 0), Position(0, 0)), null, null)
        val resolved = provider.resolveCodeLens(lens)
        assertNotNull(resolved.command)
        assertEquals("? references", resolved.command.title)
    }

    @Test
    fun `lens data contains correct metadata`() {
        val content = """
            .class public Lcom/example/Foo;
            .super Ljava/lang/Object;
            .method public calculate(II)I
                .registers 4
                add-int v0, p1, p2
                return v0
            .end method
        """.trimIndent()
        indexContent("file:///test/Foo.smali", content)

        val lenses = provider.provideCodeLenses("file:///test/Foo.smali")
        val calcLens = lenses.find {
            (it.data as? JsonObject)?.get("memberName")?.asString == "calculate"
        }
        assertNotNull(calcLens)

        val data = calcLens.data as JsonObject
        assertEquals("Lcom/example/Foo;", data.get("className").asString)
        assertEquals("calculate", data.get("memberName").asString)
        assertEquals("method", data.get("memberType").asString)
        assertEquals("file:///test/Foo.smali", data.get("uri").asString)
    }
}
