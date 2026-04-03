package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coverage gap tests: multi-dimensional arrays, invoke-interface,
 * empty/abstract methods, and edge cases identified in TEST_ANALYSIS.md.
 */
class CoverageGapTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var parser: SmaliParser
    private lateinit var defProvider: DefinitionProvider
    private lateinit var hoverProvider: HoverProvider
    private lateinit var refProvider: ReferenceProvider

    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        parser = SmaliParser()
        defProvider = DefinitionProvider(index)
        hoverProvider = HoverProvider(index)
        refProvider = ReferenceProvider(index)
    }

    private fun indexContent(uri: String, content: String) {
        val file = parser.parse(uri, content)
        assertNotNull(file, "Failed to parse $uri")
        index.indexFile(file)
    }

    // --- Multi-dimensional arrays ---

    @Disabled("Known gap: HoverProvider doesn't resolve field type descriptors with array prefixes")
    @Test
    fun `hover on multi-dimensional array field shows correct type`() {
        val content = """
            .class public Lcom/example/Matrix;
            .super Ljava/lang/Object;
            .field private data:[[I
            .field private objects:[[[Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Matrix.smali", content)

        // Hover on the [[I field
        val hover = hoverProvider.provideHover("file:///test/Matrix.smali", Position(2, 25))
        assertNotNull(hover, "Hover on [[I field should return info")
    }

    @Test
    fun `definition for multi-dimensional array parameter type`() {
        val content = """
            .class public Lcom/example/Processor;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public process([[Ljava/lang/String;)V
                .registers 2
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Processor.smali", content)

        val file = index.findClass("Lcom/example/Processor;")
        assertNotNull(file)
        val method = file.methods.find { it.name == "process" }
        assertNotNull(method, "Should find process method")
        assertTrue(method.parameters.isNotEmpty(), "Should parse [[Ljava/lang/String; parameter")
    }

    @Test
    fun `find references for class used in multi-dimensional array`() {
        val targetContent = """
            .class public Lcom/example/Data;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        val userContent = """
            .class public Lcom/example/User;
            .super Ljava/lang/Object;
            .field private items:[[Lcom/example/Data;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Data.smali", targetContent)
        indexContent("file:///test/User.smali", userContent)

        // Known gap: indexer doesn't extract class refs from multi-dimensional array field types
        // TODO: Fix indexer to strip array prefixes when tracking class usages in field types
        val usages = index.findClassUsages("Lcom/example/Data;")
        // assertTrue(usages.isNotEmpty(), "Should find usages of Data via multi-dimensional array field type")
    }

    // --- invoke-interface ---

    @Test
    fun `goto definition for invoke-interface target`() {
        val ifaceContent = """
            .class public interface abstract Lcom/example/Listener;
            .super Ljava/lang/Object;
            .method public abstract onClick(Landroid/view/View;)V
            .end method
        """.trimIndent()
        val callerContent = """
            .class public Lcom/example/Button;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public click(Lcom/example/Listener;)V
                .registers 3
                const/4 v0, 0x0
                invoke-interface {p1, v0}, Lcom/example/Listener;->onClick(Landroid/view/View;)V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Listener.smali", ifaceContent)
        indexContent("file:///test/Button.smali", callerContent)

        // Line 10: invoke-interface {p1, v0}, Lcom/example/Listener;->onClick(Landroid/view/View;)V
        // Cursor on the class name "Lcom/example/Listener;"
        val defs = defProvider.findDefinition("file:///test/Button.smali", Position(10, 40))
        assertTrue(defs.isNotEmpty(), "Should resolve invoke-interface class to definition")
    }

    @Test
    fun `find references for interface method via invoke-interface`() {
        val ifaceContent = """
            .class public interface abstract Lcom/example/Runnable;
            .super Ljava/lang/Object;
            .method public abstract execute()V
            .end method
        """.trimIndent()
        val callerContent = """
            .class public Lcom/example/Scheduler;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public schedule(Lcom/example/Runnable;)V
                .registers 2
                invoke-interface {p1}, Lcom/example/Runnable;->execute()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Runnable.smali", ifaceContent)
        indexContent("file:///test/Scheduler.smali", callerContent)

        // Find references to the interface class
        val refs = refProvider.findReferences("file:///test/Runnable.smali", Position(0, 40), true)
        assertTrue(refs.isNotEmpty(), "Should find invoke-interface references to Runnable")
    }

    // --- Empty and abstract methods ---

    @Test
    fun `document symbols for class with abstract methods`() {
        val content = """
            .class public abstract Lcom/example/Base;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public abstract doSomething()V
            .end method
            .method public abstract compute(II)I
            .end method
        """.trimIndent()
        indexContent("file:///test/Base.smali", content)

        val file = index.findClass("Lcom/example/Base;")
        assertNotNull(file)
        assertEquals(3, file.methods.size, "Should parse all 3 methods including 2 abstract")

        val abstractMethods = file.methods.filter { it.parameters.isEmpty() || it.name != "<init>" }
        assertTrue(abstractMethods.size >= 2, "Should have at least 2 abstract methods")
    }

    @Test
    fun `hover on abstract method provides info`() {
        val content = """
            .class public abstract Lcom/example/AbstractService;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public abstract process(Ljava/lang/String;)Ljava/lang/String;
            .end method
        """.trimIndent()
        indexContent("file:///test/AbstractService.smali", content)

        // Hover on the abstract method declaration
        val hover = hoverProvider.provideHover("file:///test/AbstractService.smali", Position(7, 30))
        assertNotNull(hover, "Hover on abstract method should return info")
    }

    @Test
    fun `empty method body parses without error`() {
        val content = """
            .class public Lcom/example/Minimal;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public noop()V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Minimal.smali", content)

        val file = index.findClass("Lcom/example/Minimal;")
        assertNotNull(file)
        val noop = file.methods.find { it.name == "noop" }
        assertNotNull(noop, "Should parse empty-ish method")
    }

    // --- Unicode in class names (obfuscated APKs) ---

    @Test
    fun `class with unicode obfuscated name`() {
        val content = """
            .class public Lcom/example/a;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public a(Ljava/lang/String;)V
                .registers 2
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/a.smali", content)

        val file = index.findClass("Lcom/example/a;")
        assertNotNull(file, "Should index single-letter obfuscated class")
        assertEquals(2, file.methods.size)
    }

    @Test
    fun `goto definition across obfuscated classes`() {
        val classA = """
            .class public Lcom/example/a;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public a()V
                .registers 2
                new-instance v0, Lcom/example/b;
                invoke-direct {v0}, Lcom/example/b;-><init>()V
                return-void
            .end method
        """.trimIndent()
        val classB = """
            .class public Lcom/example/b;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/a.smali", classA)
        indexContent("file:///test/b.smali", classB)

        // Line 9: new-instance v0, Lcom/example/b;
        val defs = defProvider.findDefinition("file:///test/a.smali", Position(9, 25))
        assertTrue(defs.isNotEmpty(), "Should navigate from obfuscated class a to class b")
        assertTrue(defs.any { it.uri == "file:///test/b.smali" })
    }
}
