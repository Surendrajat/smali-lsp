package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.CallHierarchyProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CallHierarchyProviderTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var parser: SmaliParser
    private lateinit var provider: CallHierarchyProvider

    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        parser = SmaliParser()
        provider = CallHierarchyProvider(index)
    }

    private fun indexContent(uri: String, content: String) {
        val file = parser.parse(uri, content)
        assertNotNull(file, "Failed to parse $uri")
        index.indexFile(file)
    }

    @Test
    fun `prepare call hierarchy returns method at cursor`() {
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

        // Position inside doWork method
        val items = provider.prepare("file:///test/Foo.smali", Position(8, 5))
        assertEquals(1, items.size)
        assertTrue(items[0].name.contains("doWork"))
    }

    @Test
    fun `prepare call hierarchy returns empty for position outside method`() {
        val content = """
            .class public Lcom/example/Foo;
            .super Ljava/lang/Object;
            .method public doWork()V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Foo.smali", content)

        // Position on .class line
        val items = provider.prepare("file:///test/Foo.smali", Position(0, 0))
        assertTrue(items.isEmpty())
    }

    @Test
    fun `incoming calls finds callers`() {
        val callerContent = """
            .class public Lcom/example/Caller;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public callTarget()V
                .registers 2
                new-instance v0, Lcom/example/Target;
                invoke-direct {v0}, Lcom/example/Target;-><init>()V
                invoke-virtual {v0}, Lcom/example/Target;->doWork()V
                return-void
            .end method
        """.trimIndent()
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
        indexContent("file:///test/Caller.smali", callerContent)
        indexContent("file:///test/Target.smali", targetContent)

        // Prepare on Target.doWork
        val items = provider.prepare("file:///test/Target.smali", Position(8, 5))
        assertEquals(1, items.size)

        // Get incoming calls
        val incoming = provider.incomingCalls(items[0])
        assertEquals(1, incoming.size)
        assertTrue(incoming[0].from.name.contains("callTarget"))
        assertEquals("Lcom/example/Caller;", incoming[0].from.detail)
    }

    @Test
    fun `outgoing calls finds callees`() {
        val content = """
            .class public Lcom/example/Hub;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public orchestrate()V
                .registers 3
                new-instance v0, Lcom/example/Worker;
                invoke-direct {v0}, Lcom/example/Worker;-><init>()V
                invoke-virtual {v0}, Lcom/example/Worker;->process()V
                invoke-static {}, Lcom/example/Utils;->log()V
                return-void
            .end method
        """.trimIndent()
        val workerContent = """
            .class public Lcom/example/Worker;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public process()V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Hub.smali", content)
        indexContent("file:///test/Worker.smali", workerContent)

        val items = provider.prepare("file:///test/Hub.smali", Position(8, 5))
        assertEquals(1, items.size)

        val outgoing = provider.outgoingCalls(items[0])
        // Should find calls to Worker.<init>, Worker.process, Utils.log, Worker (new-instance is TypeInstruction not invoke)
        assertTrue(outgoing.isNotEmpty(), "Should find outgoing calls")

        val calleeNames = outgoing.map { it.to.name }
        assertTrue(calleeNames.any { it.contains("process") }, "Should include Worker.process call")
    }

    @Test
    fun `incoming calls with multiple callers`() {
        val target = """
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
        """.trimIndent()
        val caller1 = """
            .class public Lcom/example/Activity;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public onCreate()V
                .registers 2
                new-instance v0, Lcom/example/Service;
                invoke-virtual {v0}, Lcom/example/Service;->start()V
                return-void
            .end method
        """.trimIndent()
        val caller2 = """
            .class public Lcom/example/Fragment;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public onResume()V
                .registers 2
                new-instance v0, Lcom/example/Service;
                invoke-virtual {v0}, Lcom/example/Service;->start()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Service.smali", target)
        indexContent("file:///test/Activity.smali", caller1)
        indexContent("file:///test/Fragment.smali", caller2)

        val items = provider.prepare("file:///test/Service.smali", Position(8, 5))
        assertEquals(1, items.size)

        val incoming = provider.incomingCalls(items[0])
        assertEquals(2, incoming.size, "Should find both Activity and Fragment as callers")
    }

    @Test
    fun `no callers returns empty list`() {
        val content = """
            .class public Lcom/example/Isolated;
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
        indexContent("file:///test/Isolated.smali", content)

        val items = provider.prepare("file:///test/Isolated.smali", Position(8, 5))
        assertEquals(1, items.size)

        val incoming = provider.incomingCalls(items[0])
        assertTrue(incoming.isEmpty())
    }
}
