package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.RenameProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenameProviderTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var provider: RenameProvider

    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        provider = RenameProvider(index)
    }

    // --- Helpers ---

    private fun indexFileWithContent(uri: String, file: SmaliFile, content: String) {
        index.indexFile(file)
        index.setDocumentContent(uri, content)
    }

    // --- prepareRename tests ---

    @Nested
    @DisplayName("prepareRename")
    inner class PrepareRenameTests {

        @Test
        fun `returns null for class definition`() {
            val uri = "file:///Test.smali"
            val content = ".class public Lcom/example/Test;\n.super Ljava/lang/Object;"
            val file = SmaliFile(
                uri = uri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Test;",
                    range = range(0, 0, 1, 27),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = emptyList(),
                fields = emptyList()
            )
            indexFileWithContent(uri, file, content)

            // Cursor on class name — should not be renameable
            val result = provider.prepareRename(uri, Position(0, 20))
            assertNull(result, "Class rename should not be supported")
        }

        @Test
        fun `returns null for init methods`() {
            val uri = "file:///Test.smali"
            val content = ".class public Lcom/example/Test;\n.super Ljava/lang/Object;\n\n.method public constructor <init>()V\n    return-void\n.end method"
            val file = SmaliFile(
                uri = uri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Test;",
                    range = range(0, 0, 5, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "<init>",
                        descriptor = "()V",
                        range = range(3, 0, 5, 11),
                        modifiers = setOf("public", "constructor"),
                        parameters = emptyList(),
                        returnType = "V"
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(uri, file, content)

            val result = provider.prepareRename(uri, Position(3, 30))
            assertNull(result, "<init> methods should not be renameable")
        }

        @Test
        fun `returns name range for method definition`() {
            val uri = "file:///Test.smali"
            val content = ".class public Lcom/example/Test;\n.super Ljava/lang/Object;\n\n.method public calculate(I)V\n    return-void\n.end method"
            val file = SmaliFile(
                uri = uri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Test;",
                    range = range(0, 0, 5, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "calculate",
                        descriptor = "(I)V",
                        range = range(3, 0, 5, 11),
                        modifiers = setOf("public"),
                        parameters = listOf(Parameter("I", null)),
                        returnType = "V"
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(uri, file, content)

            // Cursor on "calculate" in ".method public calculate(I)V"
            val result = provider.prepareRename(uri, Position(3, 18))
            assertNotNull(result, "Method should be renameable")
            assertEquals("calculate", result.right.placeholder)
        }

        @Test
        fun `returns name range for field definition`() {
            val uri = "file:///Test.smali"
            val content = ".class public Lcom/example/Test;\n.super Ljava/lang/Object;\n\n.field private count:I"
            val file = SmaliFile(
                uri = uri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Test;",
                    range = range(0, 0, 3, 22),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = emptyList(),
                fields = listOf(
                    FieldDefinition(
                        name = "count",
                        type = "I",
                        range = range(3, 0, 3, 22),
                        modifiers = setOf("private")
                    )
                )
            )
            indexFileWithContent(uri, file, content)

            val result = provider.prepareRename(uri, Position(3, 17))
            assertNotNull(result, "Field should be renameable")
            assertEquals("count", result.right.placeholder)
        }

        @Test
        fun `returns name range for label definition`() {
            val uri = "file:///Test.smali"
            val content = ".class public Lcom/example/Test;\n.super Ljava/lang/Object;\n\n.method public test()V\n    :start\n    return-void\n.end method"
            val labelDef = LabelDefinition("start", range(4, 4, 4, 10))
            val file = SmaliFile(
                uri = uri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Test;",
                    range = range(0, 0, 6, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "test",
                        descriptor = "()V",
                        range = range(3, 0, 6, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V",
                        labels = mapOf("start" to labelDef)
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(uri, file, content)

            val result = provider.prepareRename(uri, Position(4, 6))
            assertNotNull(result, "Label should be renameable")
            assertEquals("start", result.right.placeholder)
        }
    }

    // --- Method rename tests ---

    @Nested
    @DisplayName("Method rename")
    inner class MethodRenameTests {

        @Test
        fun `renames method definition and call site`() {
            val callerUri = "file:///Caller.smali"
            val targetUri = "file:///Target.smali"

            // Target class with method
            val targetContent = ".class public Lcom/example/Target;\n.super Ljava/lang/Object;\n\n.method public doWork()V\n    return-void\n.end method"
            val targetFile = SmaliFile(
                uri = targetUri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Target;",
                    range = range(0, 0, 5, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "doWork",
                        descriptor = "()V",
                        range = range(3, 0, 5, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V"
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(targetUri, targetFile, targetContent)

            // Caller class calling the method
            val callerContent = ".class public Lcom/example/Caller;\n.super Ljava/lang/Object;\n\n.method public test()V\n    .registers 2\n    new-instance v0, Lcom/example/Target;\n    invoke-virtual {v0}, Lcom/example/Target;->doWork()V\n    return-void\n.end method"
            val callerFile = SmaliFile(
                uri = callerUri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Caller;",
                    range = range(0, 0, 8, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "test",
                        descriptor = "()V",
                        range = range(3, 0, 8, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V",
                        instructions = listOf(
                            InvokeInstruction(
                                opcode = "invoke-virtual",
                                className = "Lcom/example/Target;",
                                methodName = "doWork",
                                descriptor = "()V",
                                range = range(6, 4, 6, 55)
                            )
                        )
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(callerUri, callerFile, callerContent)

            // Rename at definition
            val edit = provider.rename(targetUri, Position(3, 18), "execute")
            assertNotNull(edit, "Should produce workspace edit")

            val targetEdits = edit.changes[targetUri]
            assertNotNull(targetEdits, "Should have edits in target file")
            assertTrue(targetEdits.any { it.newText == "execute" }, "Should rename declaration")

            val callerEdits = edit.changes[callerUri]
            assertNotNull(callerEdits, "Should have edits in caller file")
            assertTrue(callerEdits.any { it.newText == "execute" }, "Should rename call site")
        }

        @Test
        fun `rename from call site works`() {
            val targetUri = "file:///Target.smali"
            val callerUri = "file:///Caller.smali"

            val targetContent = ".class public Lcom/example/Target;\n.super Ljava/lang/Object;\n\n.method public doWork()V\n    return-void\n.end method"
            val targetFile = SmaliFile(
                uri = targetUri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Target;",
                    range = range(0, 0, 5, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "doWork",
                        descriptor = "()V",
                        range = range(3, 0, 5, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V"
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(targetUri, targetFile, targetContent)

            val callerContent = ".class public Lcom/example/Caller;\n.super Ljava/lang/Object;\n\n.method public test()V\n    .registers 2\n    new-instance v0, Lcom/example/Target;\n    invoke-virtual {v0}, Lcom/example/Target;->doWork()V\n    return-void\n.end method"
            val invokeInstr = InvokeInstruction(
                opcode = "invoke-virtual",
                className = "Lcom/example/Target;",
                methodName = "doWork",
                descriptor = "()V",
                range = range(6, 4, 6, 55)
            )
            val callerFile = SmaliFile(
                uri = callerUri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Caller;",
                    range = range(0, 0, 8, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "test",
                        descriptor = "()V",
                        range = range(3, 0, 8, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V",
                        instructions = listOf(invokeInstr)
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(callerUri, callerFile, callerContent)

            // Rename from the call site: position on "doWork" in the invoke line
            // "    invoke-virtual {v0}, Lcom/example/Target;->doWork()V"
            //                                                  ^--- position 47
            val edit = provider.rename(callerUri, Position(6, 47), "execute")
            assertNotNull(edit, "Should produce workspace edit from call site")

            val targetEdits = edit.changes[targetUri]
            assertNotNull(targetEdits, "Should rename declaration too")

            val callerEdits = edit.changes[callerUri]
            assertNotNull(callerEdits, "Should rename call site")
        }

        @Test
        fun `polymorphic rename cascades to subclass methods`() {
            val baseUri = "file:///Base.smali"
            val childUri = "file:///Child.smali"
            val callerUri = "file:///Caller.smali"

            // Base class
            val baseContent = ".class public Lcom/example/Base;\n.super Ljava/lang/Object;\n\n.method public run()V\n    return-void\n.end method"
            val baseFile = SmaliFile(
                uri = baseUri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Base;",
                    range = range(0, 0, 5, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "run",
                        descriptor = "()V",
                        range = range(3, 0, 5, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V"
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(baseUri, baseFile, baseContent)

            // Child override
            val childContent = ".class public Lcom/example/Child;\n.super Lcom/example/Base;\n\n.method public run()V\n    return-void\n.end method"
            val childFile = SmaliFile(
                uri = childUri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Child;",
                    range = range(0, 0, 5, 11),
                    modifiers = setOf("public"),
                    superClass = "Lcom/example/Base;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "run",
                        descriptor = "()V",
                        range = range(3, 0, 5, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V"
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(childUri, childFile, childContent)

            // Caller calling Base.run
            val callerContent = ".class public Lcom/example/Caller;\n.super Ljava/lang/Object;\n\n.method public test()V\n    .registers 2\n    invoke-virtual {v0}, Lcom/example/Base;->run()V\n    return-void\n.end method"
            val callerFile = SmaliFile(
                uri = callerUri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Caller;",
                    range = range(0, 0, 7, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "test",
                        descriptor = "()V",
                        range = range(3, 0, 7, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V",
                        instructions = listOf(
                            InvokeInstruction(
                                opcode = "invoke-virtual",
                                className = "Lcom/example/Base;",
                                methodName = "run",
                                descriptor = "()V",
                                range = range(5, 4, 5, 50)
                            )
                        )
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(callerUri, callerFile, callerContent)

            // Rename Base.run -> execute
            val edit = provider.rename(baseUri, Position(3, 17), "execute")
            assertNotNull(edit)

            // Base declaration should be renamed
            assertTrue(edit.changes.containsKey(baseUri), "Should edit base file")
            // Child override should be renamed
            assertTrue(edit.changes.containsKey(childUri), "Should rename child override too")
            // Call site should be renamed
            assertTrue(edit.changes.containsKey(callerUri), "Should rename call site")
        }
    }

    // --- Field rename tests ---

    @Nested
    @DisplayName("Field rename")
    inner class FieldRenameTests {

        @Test
        fun `renames field declaration and access sites`() {
            val targetUri = "file:///Target.smali"
            val callerUri = "file:///Caller.smali"

            val targetContent = ".class public Lcom/example/Target;\n.super Ljava/lang/Object;\n\n.field public name:Ljava/lang/String;"
            val targetFile = SmaliFile(
                uri = targetUri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Target;",
                    range = range(0, 0, 3, 37),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = emptyList(),
                fields = listOf(
                    FieldDefinition(
                        name = "name",
                        type = "Ljava/lang/String;",
                        range = range(3, 0, 3, 37),
                        modifiers = setOf("public")
                    )
                )
            )
            indexFileWithContent(targetUri, targetFile, targetContent)

            val callerContent = ".class public Lcom/example/Caller;\n.super Ljava/lang/Object;\n\n.method public test()V\n    .registers 2\n    iget-object v0, p0, Lcom/example/Target;->name:Ljava/lang/String;\n    return-void\n.end method"
            val callerFile = SmaliFile(
                uri = callerUri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Caller;",
                    range = range(0, 0, 7, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "test",
                        descriptor = "()V",
                        range = range(3, 0, 7, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V",
                        instructions = listOf(
                            FieldAccessInstruction(
                                opcode = "iget-object",
                                className = "Lcom/example/Target;",
                                fieldName = "name",
                                fieldType = "Ljava/lang/String;",
                                range = range(5, 4, 5, 66)
                            )
                        )
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(callerUri, callerFile, callerContent)

            val edit = provider.rename(targetUri, Position(3, 17), "displayName")
            assertNotNull(edit)

            val targetEdits = edit.changes[targetUri]
            assertNotNull(targetEdits, "Should edit declaration")
            assertTrue(targetEdits.any { it.newText == "displayName" })

            val callerEdits = edit.changes[callerUri]
            assertNotNull(callerEdits, "Should edit access site")
            assertTrue(callerEdits.any { it.newText == "displayName" })
        }

        @Test
        fun `rename field from access site`() {
            val targetUri = "file:///Target.smali"
            val callerUri = "file:///Caller.smali"

            val targetContent = ".class public Lcom/example/Target;\n.super Ljava/lang/Object;\n\n.field public count:I"
            val targetFile = SmaliFile(
                uri = targetUri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Target;",
                    range = range(0, 0, 3, 21),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = emptyList(),
                fields = listOf(
                    FieldDefinition(
                        name = "count",
                        type = "I",
                        range = range(3, 0, 3, 21),
                        modifiers = setOf("public")
                    )
                )
            )
            indexFileWithContent(targetUri, targetFile, targetContent)

            val callerContent = ".class public Lcom/example/Caller;\n.super Ljava/lang/Object;\n\n.method public test()V\n    .registers 2\n    iget v0, p0, Lcom/example/Target;->count:I\n    return-void\n.end method"
            val fieldAccess = FieldAccessInstruction(
                opcode = "iget",
                className = "Lcom/example/Target;",
                fieldName = "count",
                fieldType = "I",
                range = range(5, 4, 5, 44)
            )
            val callerFile = SmaliFile(
                uri = callerUri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Caller;",
                    range = range(0, 0, 7, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "test",
                        descriptor = "()V",
                        range = range(3, 0, 7, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V",
                        instructions = listOf(fieldAccess)
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(callerUri, callerFile, callerContent)

            // Cursor on "count" in the instruction
            // "    iget v0, p0, Lcom/example/Target;->count:I"
            //                                          ^--- find "count" after "->"
            val line = "    iget v0, p0, Lcom/example/Target;->count:I"
            val countIdx = line.indexOf("count", line.indexOf("->"))
            val edit = provider.rename(callerUri, Position(5, countIdx), "total")
            assertNotNull(edit)
            assertTrue(edit.changes.containsKey(targetUri), "Should edit declaration")
            assertTrue(edit.changes.containsKey(callerUri), "Should edit access site")
        }
    }

    // --- Label rename tests ---

    @Nested
    @DisplayName("Label rename")
    inner class LabelRenameTests {

        @Test
        fun `renames label definition and all references`() {
            val uri = "file:///Test.smali"
            val content = listOf(
                ".class public Lcom/example/Test;",       // 0
                ".super Ljava/lang/Object;",               // 1
                "",                                         // 2
                ".method public test(I)V",                 // 3
                "    .registers 2",                        // 4
                "    if-eqz p1, :skip",                    // 5
                "    invoke-virtual {p0}, Lcom/example/Test;->other()V",  // 6
                "    :skip",                               // 7
                "    return-void",                         // 8
                ".end method"                              // 9
            ).joinToString("\n")

            val skipLabel = LabelDefinition("skip", range(7, 4, 7, 9))
            val jumpInstr = JumpInstruction(
                opcode = "if-eqz",
                targetLabel = "skip",
                range = range(5, 4, 5, 22),
                labelRange = range(5, 18, 5, 22)
            )
            val file = SmaliFile(
                uri = uri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Test;",
                    range = range(0, 0, 9, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "test",
                        descriptor = "(I)V",
                        range = range(3, 0, 9, 11),
                        modifiers = setOf("public"),
                        parameters = listOf(Parameter("I", null)),
                        returnType = "V",
                        instructions = listOf(jumpInstr),
                        labels = mapOf("skip" to skipLabel)
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(uri, file, content)

            // Rename at label definition (:skip on line 7)
            val edit = provider.rename(uri, Position(7, 6), "done")
            assertNotNull(edit)

            val edits = edit.changes[uri]
            assertNotNull(edits)
            // Should have at least 2 edits: definition + jump reference
            assertTrue(edits.size >= 2, "Should rename both definition and references, got ${edits.size}")
            assertTrue(edits.all { it.newText == "done" })
        }

        @Test
        fun `renames label from jump instruction`() {
            val uri = "file:///Test.smali"
            val content = listOf(
                ".class public Lcom/example/Test;",
                ".super Ljava/lang/Object;",
                "",
                ".method public test()V",
                "    .registers 1",
                "    goto :end",
                "    :end",
                "    return-void",
                ".end method"
            ).joinToString("\n")

            val endLabel = LabelDefinition("end", range(6, 4, 6, 8))
            val gotoInstr = JumpInstruction(
                opcode = "goto",
                targetLabel = "end",
                range = range(5, 4, 5, 13),
                labelRange = range(5, 10, 5, 13)
            )
            val file = SmaliFile(
                uri = uri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Test;",
                    range = range(0, 0, 8, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "test",
                        descriptor = "()V",
                        range = range(3, 0, 8, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V",
                        instructions = listOf(gotoInstr),
                        labels = mapOf("end" to endLabel)
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(uri, file, content)

            // Rename from the goto instruction (cursor on "end" in "goto :end")
            val edit = provider.rename(uri, Position(5, 11), "finish")
            assertNotNull(edit)

            val edits = edit.changes[uri]
            assertNotNull(edits)
            assertTrue(edits.size >= 2, "Should rename definition + reference")
        }

        @Test
        fun `renames label in catch directives`() {
            val uri = "file:///Test.smali"
            val content = listOf(
                ".class public Lcom/example/Test;",       // 0
                ".super Ljava/lang/Object;",               // 1
                "",                                         // 2
                ".method public test()V",                  // 3
                "    .registers 1",                        // 4
                "    :try_start",                          // 5
                "    invoke-virtual {p0}, Lcom/example/Test;->risky()V",  // 6
                "    :try_end",                            // 7
                "    .catch Ljava/lang/Exception; {:try_start .. :try_end} :handler", // 8
                "    :handler",                            // 9
                "    return-void",                         // 10
                ".end method"                              // 11
            ).joinToString("\n")

            val tryStartLabel = LabelDefinition("try_start", range(5, 4, 5, 14))
            val tryEndLabel = LabelDefinition("try_end", range(7, 4, 7, 12))
            val handlerLabel = LabelDefinition("handler", range(9, 4, 9, 12))

            val file = SmaliFile(
                uri = uri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Test;",
                    range = range(0, 0, 11, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "test",
                        descriptor = "()V",
                        range = range(3, 0, 11, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V",
                        instructions = emptyList(),
                        labels = mapOf(
                            "try_start" to tryStartLabel,
                            "try_end" to tryEndLabel,
                            "handler" to handlerLabel
                        )
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(uri, file, content)

            // Rename "handler" label from its definition
            val edit = provider.rename(uri, Position(9, 6), "catch_0")
            assertNotNull(edit)

            val edits = edit.changes[uri]
            assertNotNull(edits)
            // Should rename: definition (:handler) + .catch reference (:handler)
            assertTrue(edits.size >= 2, "Should rename label def + catch reference, got ${edits.size}")
            assertTrue(edits.all { it.newText == "catch_0" })
        }
    }

    // --- Edge case tests ---

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCaseTests {

        @Test
        fun `rename with blank new name returns null`() {
            val uri = "file:///Test.smali"
            val content = ".class public Lcom/example/Test;\n.super Ljava/lang/Object;\n\n.method public test()V\n    return-void\n.end method"
            val file = SmaliFile(
                uri = uri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Test;",
                    range = range(0, 0, 5, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "test",
                        descriptor = "()V",
                        range = range(3, 0, 5, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V"
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(uri, file, content)

            val edit = provider.rename(uri, Position(3, 18), "  ")
            assertNull(edit, "Blank name should return null")
        }

        @Test
        fun `rename on unknown URI returns null`() {
            val edit = provider.rename("file:///nonexistent.smali", Position(0, 0), "foo")
            assertNull(edit)
        }

        @Test
        fun `prepareRename on unknown URI returns null`() {
            val result = provider.prepareRename("file:///nonexistent.smali", Position(0, 0))
            assertNull(result)
        }

        @Test
        fun `rename method with invalid characters returns null`() {
            val uri = "file:///Test.smali"
            val content = ".class public Lcom/example/Test;\n.super Ljava/lang/Object;\n\n.method public test()V\n    return-void\n.end method"
            val file = SmaliFile(
                uri = uri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Test;",
                    range = range(0, 0, 5, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "test",
                        descriptor = "()V",
                        range = range(3, 0, 5, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V"
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(uri, file, content)

            // Names with spaces, slashes, parens, etc. should be rejected
            assertNull(provider.rename(uri, Position(3, 18), "bad name"), "Spaces not allowed")
            assertNull(provider.rename(uri, Position(3, 18), "bad/name"), "Slashes not allowed")
            assertNull(provider.rename(uri, Position(3, 18), "bad(name"), "Parens not allowed")
            assertNull(provider.rename(uri, Position(3, 18), "123start"), "Cannot start with digit")
        }

        @Test
        fun `rename method with valid smali identifiers succeeds`() {
            val uri = "file:///Test.smali"
            val content = ".class public Lcom/example/Test;\n.super Ljava/lang/Object;\n\n.method public test()V\n    return-void\n.end method"
            val file = SmaliFile(
                uri = uri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Test;",
                    range = range(0, 0, 5, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "test",
                        descriptor = "()V",
                        range = range(3, 0, 5, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V"
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(uri, file, content)

            // Valid Smali names: letters, digits, underscores, dollar signs, hyphens
            assertNotNull(provider.rename(uri, Position(3, 18), "newName"))
            assertNotNull(provider.rename(uri, Position(3, 18), "_private"))
            assertNotNull(provider.rename(uri, Position(3, 18), "\$synthetic"))
            assertNotNull(provider.rename(uri, Position(3, 18), "name-with-hyphens"))
        }

        @Test
        fun `rename field with invalid characters returns null`() {
            val uri = "file:///Test.smali"
            val content = ".class public Lcom/example/Test;\n.super Ljava/lang/Object;\n\n.field public myField:I"
            val file = SmaliFile(
                uri = uri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Test;",
                    range = range(0, 0, 3, 22),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = emptyList(),
                fields = listOf(
                    FieldDefinition(
                        name = "myField",
                        type = "I",
                        range = range(3, 0, 3, 22),
                        modifiers = setOf("public")
                    )
                )
            )
            indexFileWithContent(uri, file, content)

            assertNull(provider.rename(uri, Position(3, 17), "bad name"), "Spaces not allowed in field name")
            assertNull(provider.rename(uri, Position(3, 17), "bad;name"), "Semicolons not allowed in field name")
        }

        @Test
        fun `rename label with invalid characters returns null`() {
            val uri = "file:///Test.smali"
            val content = ".class public Lcom/example/Test;\n.super Ljava/lang/Object;\n\n.method public test()V\n    :myLabel\n    goto :myLabel\n    return-void\n.end method"
            val file = SmaliFile(
                uri = uri,
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Test;",
                    range = range(0, 0, 7, 11),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = listOf(
                    MethodDefinition(
                        name = "test",
                        descriptor = "()V",
                        range = range(3, 0, 7, 11),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V",
                        labels = mapOf("myLabel" to LabelDefinition(
                            name = "myLabel",
                            range = range(4, 4, 4, 12)
                        )),
                        instructions = listOf(
                            JumpInstruction(
                                opcode = "goto",
                                targetLabel = "myLabel",
                                range = range(5, 4, 5, 18),
                                labelRange = range(5, 10, 5, 17)
                            )
                        )
                    )
                ),
                fields = emptyList()
            )
            indexFileWithContent(uri, file, content)

            assertNull(provider.rename(uri, Position(4, 6), "bad-name"), "Hyphens not allowed in label names")
            assertNull(provider.rename(uri, Position(4, 6), "bad name"), "Spaces not allowed in label names")
        }
    }

    // --- Companion validation tests ---

    @Nested
    @DisplayName("Name validation")
    inner class NameValidationTests {

        @Test
        fun `isValidSmaliIdentifier accepts valid names`() {
            assertTrue(RenameProvider.isValidSmaliIdentifier("foo"))
            assertTrue(RenameProvider.isValidSmaliIdentifier("_bar"))
            assertTrue(RenameProvider.isValidSmaliIdentifier("\$baz"))
            assertTrue(RenameProvider.isValidSmaliIdentifier("a1"))
            assertTrue(RenameProvider.isValidSmaliIdentifier("my-method"))
            assertTrue(RenameProvider.isValidSmaliIdentifier("access\$000"))
        }

        @Test
        fun `isValidSmaliIdentifier rejects invalid names`() {
            assertTrue(!RenameProvider.isValidSmaliIdentifier(""))
            assertTrue(!RenameProvider.isValidSmaliIdentifier("1start"))
            assertTrue(!RenameProvider.isValidSmaliIdentifier("has space"))
            assertTrue(!RenameProvider.isValidSmaliIdentifier("has/slash"))
            assertTrue(!RenameProvider.isValidSmaliIdentifier("has(paren"))
            assertTrue(!RenameProvider.isValidSmaliIdentifier("has;semi"))
        }

        @Test
        fun `isValidLabelName accepts valid names`() {
            assertTrue(RenameProvider.isValidLabelName("try_start_0"))
            assertTrue(RenameProvider.isValidLabelName("catch_0"))
            assertTrue(RenameProvider.isValidLabelName("_label"))
            assertTrue(RenameProvider.isValidLabelName("a123"))
        }

        @Test
        fun `isValidLabelName rejects invalid names`() {
            assertTrue(!RenameProvider.isValidLabelName(""))
            assertTrue(!RenameProvider.isValidLabelName("1start"))
            assertTrue(!RenameProvider.isValidLabelName("has-hyphen"))
            assertTrue(!RenameProvider.isValidLabelName("has space"))
            assertTrue(!RenameProvider.isValidLabelName("has\$dollar"))
        }
    }
}
