package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

import xyz.surendrajat.smalilsp.providers.HoverProvider

/**
 * Tests for hover issues from user-testing.md.
 *
 * Issues covered:
 * - Hover #7: SDK classes should show "SDK Class" label
 * - Hover #8: Primitive types should show proper info
 * - Hover #9: Array types [[ hover
 * - Hover #10: Position-based hover on .method/.field for params/return types
 */
class RemainingHoverIssuesTest {

    private val parser = SmaliParser()
    private val index = WorkspaceIndex()
    private val hoverProvider = HoverProvider(index)
    private val tempFiles = mutableListOf<java.io.File>()

    private fun parseContent(content: String): String {
        val tempFile = kotlin.io.path.createTempFile("test", ".smali").toFile()
        tempFile.writeText(content)
        tempFiles.add(tempFile)

        val uri = tempFile.toURI().toString()
        val file = parser.parse(uri, content)
        assertNotNull(file)
        index.indexFile(file)

        return uri
    }

    @org.junit.jupiter.api.AfterEach
    fun cleanup() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    @Test
    fun `hover on SDK class reference shows SDK label`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;

            .method public test()V
                new-instance v0, Ljava/lang/String;
                return-void
            .end method
        """.trimIndent()

        val uri = parseContent(content)

        // Hover on "Ljava/lang/String;" in new-instance instruction
        val hover = hoverProvider.provideHover(uri, Position(4, 30))
        assertNotNull(hover, "Hover on SDK class reference must not be null")

        val hoverText = hover.contents.right.value
        assertTrue(hoverText.contains("SDK"), "SDK class hover must contain 'SDK' label, got: $hoverText")
    }

    @Test
    fun `hover on primitive type in method signature`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;

            .method public test(I)J
                const-wide v0, 0x0
                return-wide v0
            .end method
        """.trimIndent()

        val uri = parseContent(content)

        // Hover on "I" (int parameter) in method signature
        // Line: ".method public test(I)J"
        //        0         1         2
        //        0123456789012345678901234
        // 'I' is at column 20
        val hoverOnInt = hoverProvider.provideHover(uri, Position(3, 20))
        assertNotNull(hoverOnInt, "Hover on primitive type 'I' in method signature must not be null")

        val hoverText = hoverOnInt.contents.right.value
        assertTrue(
            hoverText.contains("int") || hoverText.contains("Integer") || hoverText.contains("Primitive"),
            "Primitive hover must describe the type, got: $hoverText"
        )
    }

    @Test
    fun `hover on 2D array type in instruction`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;

            .field public myArray:[Ljava/lang/String;

            .method public test()V
                filled-new-array {v0, v1}, [[Lt5/d;
                return-void
            .end method
        """.trimIndent()

        val uri = parseContent(content)

        // Create the referenced class so hover can resolve it
        val referencedContent = """
            .class public Lt5/d;
            .super Ljava/lang/Object;
        """.trimIndent()
        val refFile = parser.parse("file:///t5_d.smali", referencedContent)
        assertNotNull(refFile)
        index.indexFile(refFile!!)

        // Hover on "[[Lt5/d;" in filled-new-array instruction
        val hover = hoverProvider.provideHover(uri, Position(6, 35))
        assertNotNull(hover, "Hover on 2D array type must not be null")

        val hoverText = hover.contents.right.value
        assertTrue(
            hoverText.contains("t5") || hoverText.contains("d") || hoverText.contains("array"),
            "2D array hover must describe the array element type, got: $hoverText"
        )
    }

    @Test
    fun `hover on parameter type in method declaration`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;

            .method public test(Ljava/lang/String;I)V
                return-void
            .end method
        """.trimIndent()

        val uri = parseContent(content)

        // Hover on "Ljava/lang/String;" in method signature
        // Line: ".method public test(Ljava/lang/String;I)V"
        val hoverOnParam = hoverProvider.provideHover(uri, Position(3, 25))
        assertNotNull(hoverOnParam, "Hover on parameter type in method declaration must not be null")

        val hoverText = hoverOnParam.contents.right.value
        assertTrue(
            hoverText.contains("String") || hoverText.contains("SDK"),
            "Parameter type hover must describe String, got: $hoverText"
        )
    }

    @Test
    fun `hover on return type in method declaration`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;

            .method public test()Ljava/lang/String;
                const/4 v0, 0x0
                return-object v0
            .end method
        """.trimIndent()

        val uri = parseContent(content)

        // Hover on "Ljava/lang/String;" in return type
        // Line: ".method public test()Ljava/lang/String;"
        val hoverOnReturn = hoverProvider.provideHover(uri, Position(3, 26))
        assertNotNull(hoverOnReturn, "Hover on return type in method declaration must not be null")

        val hoverText = hoverOnReturn.contents.right.value
        assertTrue(
            hoverText.contains("String") || hoverText.contains("SDK"),
            "Return type hover must describe String, got: $hoverText"
        )
    }

    @Test
    fun `hover on field type in field declaration`() {
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;

            .field public myField:Ljava/lang/String;
        """.trimIndent()

        val uri = parseContent(content)

        // Hover on "Ljava/lang/String;" in field type
        // Line: ".field public myField:Ljava/lang/String;"
        val hoverOnType = hoverProvider.provideHover(uri, Position(3, 27))
        assertNotNull(hoverOnType, "Hover on field type in field declaration must not be null")

        val hoverText = hoverOnType.contents.right.value
        assertTrue(
            hoverText.contains("String") || hoverText.contains("SDK"),
            "Field type hover must describe String, got: $hoverText"
        )
    }
}
