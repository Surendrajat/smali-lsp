package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.integration.lsp.TempTestWorkspace
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import xyz.surendrajat.smalilsp.providers.ReferenceProvider

class ReferenceProviderTest {

    @Test
    fun `find references to class in same file`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Example.smali", """
            .class public Ltest/Example;
            .super Ljava/lang/Object;

            .field public self:Ltest/Example;

            .method public getSelf()Ltest/Example;
                const/4 v0, 0x0
                return-object v0
            .end method
        """.trimIndent())

        val index = WorkspaceIndex()
        val file = workspace.parseFile("test/Example.smali")
        index.indexFile(file)

        val provider = ReferenceProvider(index)

        // Find references from .class line
        val references = provider.findReferences(
            uri = workspace.getUri("test/Example.smali"),
            position = Position(0, 20), // On "Example" in .class
            includeDeclaration = true
        )

        // Should find declaration + field type + return type
        assertTrue(references.size >= 2, "Should find declaration plus at least one usage, got ${references.size}")

        // Should include the class definition line
        val declRef = references.find { it.range.start.line == 0 }
        assertTrue(declRef != null, "Should include declaration at line 0")

        workspace.cleanup()
    }

    @Test
    fun `find references from superclass line`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("base/Base.smali", """
            .class public Lbase/Base;
            .super Ljava/lang/Object;
        """.trimIndent())

        workspace.addFile("derived/Derived.smali", """
            .class public Lderived/Derived;
            .super Lbase/Base;
        """.trimIndent())

        val index = WorkspaceIndex()
        index.indexFile(workspace.parseFile("base/Base.smali"))
        index.indexFile(workspace.parseFile("derived/Derived.smali"))

        val provider = ReferenceProvider(index)

        // Find references to Base from Derived's .super directive
        val references = provider.findReferences(
            uri = workspace.getUri("derived/Derived.smali"),
            position = Position(1, 15), // On "Base" in .super
            includeDeclaration = true
        )

        // Should find Base's declaration + Derived's .super reference
        assertTrue(references.size >= 2, "Should find Base declaration and .super reference, got ${references.size}")

        workspace.cleanup()
    }

    @Test
    fun `find references to interface`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("iface/IClickable.smali", """
            .class public abstract interface Liface/IClickable;
            .super Ljava/lang/Object;
        """.trimIndent())

        workspace.addFile("impl/Button.smali", """
            .class public Limpl/Button;
            .super Ljava/lang/Object;
            .implements Liface/IClickable;
        """.trimIndent())

        val index = WorkspaceIndex()
        index.indexFile(workspace.parseFile("iface/IClickable.smali"))
        index.indexFile(workspace.parseFile("impl/Button.smali"))

        val provider = ReferenceProvider(index)

        // Find references to interface
        val references = provider.findReferences(
            uri = workspace.getUri("iface/IClickable.smali"),
            position = Position(0, 30), // On interface name
            includeDeclaration = true
        )

        // Should find declaration + .implements reference
        assertTrue(references.size >= 2, "Should find declaration and .implements reference, got ${references.size}")

        workspace.cleanup()
    }

    @Test
    fun `references without declaration excluded when requested`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Base.smali", """
            .class public Ltest/Base;
            .super Ljava/lang/Object;
        """.trimIndent())

        workspace.addFile("test/User.smali", """
            .class public Ltest/User;
            .super Ltest/Base;
        """.trimIndent())

        val index = WorkspaceIndex()
        index.indexFile(workspace.parseFile("test/Base.smali"))
        index.indexFile(workspace.parseFile("test/User.smali"))

        val provider = ReferenceProvider(index)

        // Find references WITHOUT declaration
        val references = provider.findReferences(
            uri = workspace.getUri("test/Base.smali"),
            position = Position(0, 15),
            includeDeclaration = false
        )

        // Should NOT include the declaration itself (line 0 of Base.smali)
        var hasBaseDeclaration = false
        for (ref in references) {
            if (ref.uri == workspace.getUri("test/Base.smali") && ref.range.start.line == 0) {
                hasBaseDeclaration = true
            }
        }
        assertTrue(!hasBaseDeclaration, "Should not include declaration when includeDeclaration=false")

        workspace.cleanup()
    }

    @Test
    fun `references on field declaration`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Fields.smali", """
            .class public Ltest/Fields;
            .super Ljava/lang/Object;

            .field public myField:I

            .method public getField()I
                .registers 2
                iget v0, p0, Ltest/Fields;->myField:I
                return v0
            .end method
        """.trimIndent())

        val index = WorkspaceIndex()
        index.indexFile(workspace.parseFile("test/Fields.smali"))

        val provider = ReferenceProvider(index)

        // Find references from field line
        val references = provider.findReferences(
            uri = workspace.getUri("test/Fields.smali"),
            position = Position(3, 15) // On field line
        )

        // Should find at least the field declaration
        assertTrue(references.size >= 1, "Should find at least the field declaration, got ${references.size}")
        assertEquals(workspace.getUri("test/Fields.smali"), references[0].uri)

        workspace.cleanup()
    }

    @Test
    fun `references on method declaration`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Methods.smali", """
            .class public Ltest/Methods;
            .super Ljava/lang/Object;

            .method public foo()V
                return-void
            .end method

            .method public bar()V
                .registers 1
                invoke-virtual {p0}, Ltest/Methods;->foo()V
                return-void
            .end method
        """.trimIndent())

        val index = WorkspaceIndex()
        index.indexFile(workspace.parseFile("test/Methods.smali"))

        val provider = ReferenceProvider(index)

        // Find references from method line
        val references = provider.findReferences(
            uri = workspace.getUri("test/Methods.smali"),
            position = Position(3, 15) // On method line
        )

        // Should find the declaration + the invoke call
        assertTrue(references.size >= 1, "Should find at least the method declaration, got ${references.size}")
        assertEquals(workspace.getUri("test/Methods.smali"), references[0].uri)

        workspace.cleanup()
    }

    @Test
    fun `references on invalid line returns empty`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Short.smali", """
            .class public Ltest/Short;
            .super Ljava/lang/Object;
        """.trimIndent())

        val index = WorkspaceIndex()
        index.indexFile(workspace.parseFile("test/Short.smali"))

        val provider = ReferenceProvider(index)

        // Request references on line beyond file length
        val references = provider.findReferences(
            uri = workspace.getUri("test/Short.smali"),
            position = Position(100, 0)
        )

        assertEquals(0, references.size, "Should return empty for invalid position")

        workspace.cleanup()
    }

    @Test
    fun `references to SDK class returns minimal results`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Activity.smali", """
            .class public Ltest/Activity;
            .super Landroid/app/Activity;
        """.trimIndent())

        val index = WorkspaceIndex()
        index.indexFile(workspace.parseFile("test/Activity.smali"))

        val provider = ReferenceProvider(index)

        // Find references to SDK class (Activity) from .super line
        val references = provider.findReferences(
            uri = workspace.getUri("test/Activity.smali"),
            position = Position(1, 20) // On "Activity" in .super
        )

        // SDK classes aren't in the index, so this should not crash
        // and should return either the .super directive or empty
        assertTrue(references.size <= 5, "SDK class refs should be bounded, got ${references.size}")

        workspace.cleanup()
    }

    @Test
    fun `cross-file method references found`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("util/Helper.smali", """
            .class public Lutil/Helper;
            .super Ljava/lang/Object;

            .method public static doWork()V
                return-void
            .end method
        """.trimIndent())

        workspace.addFile("app/Main.smali", """
            .class public Lapp/Main;
            .super Ljava/lang/Object;

            .method public run()V
                .registers 1
                invoke-static {}, Lutil/Helper;->doWork()V
                return-void
            .end method
        """.trimIndent())

        val index = WorkspaceIndex()
        index.indexFile(workspace.parseFile("util/Helper.smali"))
        index.indexFile(workspace.parseFile("app/Main.smali"))

        val provider = ReferenceProvider(index)

        // Find references to doWork from its declaration
        val references = provider.findReferences(
            uri = workspace.getUri("util/Helper.smali"),
            position = Position(3, 30), // On "doWork" in .method
            includeDeclaration = true
        )

        // Should find declaration in Helper + call site in Main
        assertTrue(references.size >= 2, "Should find declaration + call site, got ${references.size}")

        // Verify both files are represented
        val helperUri = workspace.getUri("util/Helper.smali")
        val mainUri = workspace.getUri("app/Main.smali")
        var hasHelper = false
        var hasMain = false
        for (ref in references) {
            if (ref.uri == helperUri) hasHelper = true
            if (ref.uri == mainUri) hasMain = true
        }
        assertTrue(hasHelper, "Should include Helper.smali")
        assertTrue(hasMain, "Should include Main.smali")

        workspace.cleanup()
    }

    @Test
    fun `references with empty index returns empty`() {
        val workspace = TempTestWorkspace.create()

        val index = WorkspaceIndex()
        val provider = ReferenceProvider(index)

        val references = provider.findReferences(
            uri = "file:///test.smali",
            position = Position(0, 0)
        )

        assertEquals(0, references.size, "Should return empty for non-indexed file")

        workspace.cleanup()
    }
}
