package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.TypeHierarchyProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TypeHierarchyProviderTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var parser: SmaliParser
    private lateinit var provider: TypeHierarchyProvider

    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        parser = SmaliParser()
        provider = TypeHierarchyProvider(index)
    }

    private fun indexContent(uri: String, content: String) {
        val file = parser.parse(uri, content)
        assertNotNull(file, "Failed to parse $uri")
        index.indexFile(file)
    }

    // --- prepare ---

    @Test
    fun `prepare returns class item at class definition line`() {
        val content = """
            .class public Lcom/example/Foo;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Foo.smali", content)

        val items = provider.prepare("file:///test/Foo.smali", Position(0, 10))
        assertEquals(1, items.size)
        assertEquals("Foo", items[0].name)
        assertEquals("Lcom/example/Foo;", items[0].detail)
        assertEquals(SymbolKind.Class, items[0].kind)
    }

    @Test
    fun `prepare returns interface kind for interface class`() {
        val content = """
            .class public interface abstract Lcom/example/ICallback;
            .super Ljava/lang/Object;
        """.trimIndent()
        indexContent("file:///test/ICallback.smali", content)

        val items = provider.prepare("file:///test/ICallback.smali", Position(0, 10))
        assertEquals(1, items.size)
        assertEquals(SymbolKind.Interface, items[0].kind)
    }

    @Test
    fun `prepare on super directive returns super class`() {
        val parentContent = """
            .class public Lcom/example/Parent;
            .super Ljava/lang/Object;
        """.trimIndent()
        val childContent = """
            .class public Lcom/example/Child;
            .super Lcom/example/Parent;
        """.trimIndent()
        indexContent("file:///test/Parent.smali", parentContent)
        indexContent("file:///test/Child.smali", childContent)

        // Position on .super line (line 1)
        val items = provider.prepare("file:///test/Child.smali", Position(1, 10))
        assertEquals(1, items.size)
        assertEquals("Parent", items[0].name)
    }

    @Test
    fun `prepare returns empty for unknown URI`() {
        val items = provider.prepare("file:///nonexistent.smali", Position(0, 0))
        assertTrue(items.isEmpty())
    }

    // --- supertypes ---

    @Test
    fun `supertypes returns parent class`() {
        val parentContent = """
            .class public Lcom/example/Base;
            .super Ljava/lang/Object;
        """.trimIndent()
        val childContent = """
            .class public Lcom/example/Child;
            .super Lcom/example/Base;
        """.trimIndent()
        indexContent("file:///test/Base.smali", parentContent)
        indexContent("file:///test/Child.smali", childContent)

        val items = provider.prepare("file:///test/Child.smali", Position(0, 10))
        assertEquals(1, items.size)

        val supertypes = provider.supertypes(items[0])
        assertTrue(supertypes.isNotEmpty(), "Should have at least one supertype")
        assertTrue(supertypes.any { it.name == "Base" })
    }

    @Test
    fun `supertypes returns interfaces`() {
        val ifaceContent = """
            .class public interface abstract Lcom/example/Runnable;
            .super Ljava/lang/Object;
        """.trimIndent()
        val implContent = """
            .class public Lcom/example/Task;
            .super Ljava/lang/Object;
            .implements Lcom/example/Runnable;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Runnable.smali", ifaceContent)
        indexContent("file:///test/Task.smali", implContent)

        val items = provider.prepare("file:///test/Task.smali", Position(0, 10))
        val supertypes = provider.supertypes(items[0])
        assertTrue(supertypes.any { it.name == "Runnable" }, "Should include Runnable interface")
    }

    @Test
    fun `supertypes returns both parent and interfaces`() {
        val baseContent = """
            .class public Lcom/example/Base;
            .super Ljava/lang/Object;
        """.trimIndent()
        val ifaceContent = """
            .class public interface abstract Lcom/example/Serializable;
            .super Ljava/lang/Object;
        """.trimIndent()
        val childContent = """
            .class public Lcom/example/Model;
            .super Lcom/example/Base;
            .implements Lcom/example/Serializable;
        """.trimIndent()
        indexContent("file:///test/Base.smali", baseContent)
        indexContent("file:///test/Serializable.smali", ifaceContent)
        indexContent("file:///test/Model.smali", childContent)

        val items = provider.prepare("file:///test/Model.smali", Position(0, 10))
        val supertypes = provider.supertypes(items[0])
        assertEquals(2, supertypes.size, "Should have parent + interface")
        val names = supertypes.map { it.name }.toSet()
        assertTrue("Base" in names)
        assertTrue("Serializable" in names)
    }

    @Test
    fun `supertypes excludes java lang Object`() {
        val content = """
            .class public Lcom/example/Simple;
            .super Ljava/lang/Object;
        """.trimIndent()
        indexContent("file:///test/Simple.smali", content)

        val items = provider.prepare("file:///test/Simple.smali", Position(0, 5))
        val supertypes = provider.supertypes(items[0])
        // java.lang.Object is excluded when not in workspace
        assertTrue(supertypes.none { it.name == "Object" && it.detail == "Ljava/lang/Object;" },
            "Should not include unresolved java.lang.Object")
    }

    // --- subtypes ---

    @Test
    fun `subtypes returns direct subclasses`() {
        val parentContent = """
            .class public Lcom/example/Animal;
            .super Ljava/lang/Object;
        """.trimIndent()
        val childContent1 = """
            .class public Lcom/example/Dog;
            .super Lcom/example/Animal;
        """.trimIndent()
        val childContent2 = """
            .class public Lcom/example/Cat;
            .super Lcom/example/Animal;
        """.trimIndent()
        indexContent("file:///test/Animal.smali", parentContent)
        indexContent("file:///test/Dog.smali", childContent1)
        indexContent("file:///test/Cat.smali", childContent2)

        val items = provider.prepare("file:///test/Animal.smali", Position(0, 5))
        val subtypes = provider.subtypes(items[0])
        assertEquals(2, subtypes.size)
        val names = subtypes.map { it.name }.toSet()
        assertTrue("Dog" in names)
        assertTrue("Cat" in names)
    }

    @Test
    fun `subtypes returns implementors of interface`() {
        val ifaceContent = """
            .class public interface abstract Lcom/example/Clickable;
            .super Ljava/lang/Object;
        """.trimIndent()
        val implContent = """
            .class public Lcom/example/Button;
            .super Ljava/lang/Object;
            .implements Lcom/example/Clickable;
        """.trimIndent()
        indexContent("file:///test/Clickable.smali", ifaceContent)
        indexContent("file:///test/Button.smali", implContent)

        val items = provider.prepare("file:///test/Clickable.smali", Position(0, 10))
        val subtypes = provider.subtypes(items[0])
        assertEquals(1, subtypes.size)
        assertEquals("Button", subtypes[0].name)
    }

    @Test
    fun `subtypes returns empty when no subclasses exist`() {
        val content = """
            .class public final Lcom/example/Leaf;
            .super Ljava/lang/Object;
        """.trimIndent()
        indexContent("file:///test/Leaf.smali", content)

        val items = provider.prepare("file:///test/Leaf.smali", Position(0, 5))
        val subtypes = provider.subtypes(items[0])
        assertTrue(subtypes.isEmpty())
    }

    @Test
    fun `subtypes does not return transitive subclasses`() {
        val grandparent = """
            .class public Lcom/example/A;
            .super Ljava/lang/Object;
        """.trimIndent()
        val parent = """
            .class public Lcom/example/B;
            .super Lcom/example/A;
        """.trimIndent()
        val child = """
            .class public Lcom/example/C;
            .super Lcom/example/B;
        """.trimIndent()
        indexContent("file:///test/A.smali", grandparent)
        indexContent("file:///test/B.smali", parent)
        indexContent("file:///test/C.smali", child)

        val items = provider.prepare("file:///test/A.smali", Position(0, 5))
        val subtypes = provider.subtypes(items[0])
        // Only direct subclass B, not transitive C
        assertEquals(1, subtypes.size)
        assertEquals("B", subtypes[0].name)
    }
}
