package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import kotlin.test.*

import xyz.surendrajat.smalilsp.core.ClassDefinition
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.providers.HoverProvider
class HoverProviderTest {
    
    private lateinit var index: WorkspaceIndex
    private lateinit var provider: HoverProvider
    
    @BeforeTest
    fun setup() {
        index = WorkspaceIndex()
        provider = HoverProvider(index)
    }
    
    @Test
    fun `hover on class directive shows class info`() {
        val file = SmaliFile(
            uri = "file:///MyClass.smali",
            classDefinition = ClassDefinition(
                name = "Lcom/example/MyClass;",
                range = range(0, 0, 1, 0),
                modifiers = setOf("public", "final"),
                superClass = "Ljava/lang/Object;",
                interfaces = listOf("Ljava/io/Serializable;")
            ),
            methods = listOf(
                MethodDefinition(
                    name = "<init>",
                    descriptor = "()V",
                    range = range(3, 0, 5, 0),
                    modifiers = setOf("public"),
                    parameters = emptyList(),
                    returnType = "V"
                )
            ),
            fields = listOf(
                FieldDefinition(
                    name = "data",
                    type = "Ljava/lang/String;",
                    range = range(7, 0, 7, 30),
                    modifiers = setOf("private")
                )
            )
        )
        
        index.indexFile(file)
        
        // Position must be within class range (0, 0, 1, 0) to show class info
        val position = Position(0, 25) // Within class range
        val hover = provider.provideHover("file:///MyClass.smali", position)
        
        assertNotNull(hover)
        val markup = hover.contents.right as org.eclipse.lsp4j.MarkupContent
        val content = markup.value
        assertTrue(content.contains("MyClass"))
        assertTrue(content.contains("public") && content.contains("final"))
        assertTrue(content.contains("Extends"))
        assertTrue(content.contains("Serializable"))
        assertTrue(content.contains("Methods:") && content.contains(" 1"))
        assertTrue(content.contains("Fields:"))
    }
    
    @Test
    fun `hover on super shows superclass info`() {
        // Index the superclass
        index.indexFile(SmaliFile(
            uri = "sdk://android/Landroid/app/Activity;",
            classDefinition = ClassDefinition(
                name = "Landroid/app/Activity;",
                range = range(0, 0, 1, 0),
                modifiers = setOf("public"),
                superClass = "Landroid/view/ContextThemeWrapper;",
                interfaces = emptyList()
            ),
            methods = listOf(
                MethodDefinition("onCreate", "(Landroid/os/Bundle;)V", range(3, 0, 5, 0), setOf("protected"), emptyList(), "V"),
                MethodDefinition("onStart", "()V", range(6, 0, 8, 0), setOf("protected"), emptyList(), "V")
            ),
            fields = emptyList()
        ))
        
        // Index the source file (AST-based approach needs this!)
        index.indexFile(SmaliFile(
            uri = "file:///MyActivity.smali",
            classDefinition = ClassDefinition(
                name = "LMyActivity;",
                range = range(0, 0, 1, 0),
                modifiers = setOf("public"),
                superClass = "Landroid/app/Activity;",
                interfaces = emptyList()
            ),
            methods = emptyList(),
            fields = emptyList()
        ))
        
        // Position must be within class range to show class info
        val position = Position(0, 20) // Within class range (0, 0, 1, 0)
        val hover = provider.provideHover("file:///MyActivity.smali", position)
        
        assertNotNull(hover)
        val markup = hover.contents.right as org.eclipse.lsp4j.MarkupContent
        val content = markup.value
        // When hovering on MyActivity class, shows MyActivity info (not superclass)
        assertTrue(content.contains("MyActivity"))
        // Should show superclass in Extends line
        assertTrue(content.contains("Extends:") && content.contains("Activity"))
        assertTrue(content.contains("Methods:"))
    }
    
    @Test
    fun `hover on method shows signature`() {
        // Index the source file (AST-based approach needs this!)
        index.indexFile(SmaliFile(
            uri = "file:///MyClass.smali",
            classDefinition = ClassDefinition(
                name = "LMyClass;",
                range = range(0, 0, 1, 0),
                modifiers = setOf("public"),
                superClass = "Ljava/lang/Object;",
                interfaces = emptyList()
            ),
            methods = listOf(
                MethodDefinition(
                    name = "onClick",
                    descriptor = "(Landroid/view/View;)V",
                    range = range(2, 0, 5, 0),
                    modifiers = setOf("public"),
                    parameters = listOf(Parameter("Landroid/view/View;", null)),
                    returnType = "V"
                )
            ),
            fields = emptyList()
        ))
        
        val position = Position(2, 10)
        val hover = provider.provideHover("file:///MyClass.smali", position)
        
        assertNotNull(hover)
        val markup = hover.contents.right as org.eclipse.lsp4j.MarkupContent
        val content = markup.value
        assertTrue(content.contains("Method"))
        assertTrue(content.contains("onClick"))
        assertTrue(content.contains("public"))
        assertTrue(content.contains("Signature"))
        assertTrue(content.contains("View"))
    }
    
    @Test
    fun `hover on field shows type`() {
        // Index the source file (AST-based approach needs this!)
        index.indexFile(SmaliFile(
            uri = "file:///MyClass.smali",
            classDefinition = ClassDefinition(
                name = "LMyClass;",
                range = range(0, 0, 1, 0),
                modifiers = setOf("public"),
                superClass = "Ljava/lang/Object;",
                interfaces = emptyList()
            ),
            methods = emptyList(),
            fields = listOf(
                FieldDefinition(
                    name = "TAG",
                    type = "Ljava/lang/String;",
                    range = range(3, 0, 3, 50),
                    modifiers = setOf("private", "static", "final")
                )
            )
        ))
        
        val position = Position(3, 10)
        val hover = provider.provideHover("file:///MyClass.smali", position)
        
        assertNotNull(hover)
        val markup = hover.contents.right as org.eclipse.lsp4j.MarkupContent
        val content = markup.value
        assertTrue(content.contains("Field"))
        assertTrue(content.contains("TAG"))
        assertTrue(content.contains("private") && content.contains("static") && content.contains("final"))
        assertTrue(content.contains("String"))
    }
    
    @Test
    fun `hover on SDK class shows SDK label`() {
        // Index the source file (AST-based approach needs this!)
        index.indexFile(SmaliFile(
            uri = "file:///MyClass.smali",
            classDefinition = ClassDefinition(
                name = "LMyClass;",
                range = range(0, 0, 1, 0),
                modifiers = setOf("public"),
                superClass = "Ljava/lang/String;",
                interfaces = emptyList()
            ),
            methods = emptyList(),
            fields = emptyList()
        ))
        
        // Position within class range (0, 0, 1, 0)
        val position = Position(0, 10)
        val hover = provider.provideHover("file:///MyClass.smali", position)
        
        assertNotNull(hover)
        val markup = hover.contents.right as org.eclipse.lsp4j.MarkupContent
        val content = markup.value
        // When hovering on MyClass, shows MyClass info (not superclass)
        // MyClass is NOT an SDK class (it's in workspace), so no SDK label
        assertTrue(content.contains("MyClass"))
        // Should show superclass (String) in Extends line
        assertTrue(content.contains("Extends:") && content.contains("String"))
    }
    
    @Test
    fun `hover returns null for invalid position`() {
        // Index the source file (AST-based approach needs this!)
        index.indexFile(SmaliFile(
            uri = "file:///MyClass.smali",
            classDefinition = ClassDefinition(
                name = "LMyClass;",
                range = range(0, 0, 0, 20),
                modifiers = setOf("public"),
                superClass = "Ljava/lang/Object;",
                interfaces = emptyList()
            ),
            methods = emptyList(),
            fields = emptyList()
        ))
        
        val position = Position(10, 0) // Out of bounds
        val hover = provider.provideHover("file:///MyClass.smali", position)
        
        assertNull(hover)
    }
    
    @Test
    fun `hover on class reference in random line`() {
        // Index the source file (AST-based approach needs this!)
        // Expand class range to include comment line where we want to hover
        index.indexFile(SmaliFile(
            uri = "file:///MyClass.smali",
            classDefinition = ClassDefinition(
                name = "LMyClass;",
                range = range(0, 0, 5, 0), // Extended to include more lines
                modifiers = setOf("public"),
                superClass = "Ljava/lang/Object;",
                interfaces = emptyList()
            ),
            methods = emptyList(),
            fields = emptyList()
        ))
        
        // Position within extended class range (0, 0, 5, 0)
        val position = Position(3, 25) // Within class range
        val hover = provider.provideHover("file:///MyClass.smali", position)
        
        assertNotNull(hover)
        val markup = hover.contents.right as org.eclipse.lsp4j.MarkupContent
        val content = markup.value
        // When hovering within class range, shows class info
        assertTrue(content.contains("MyClass"))
    }
    
    @Test
    fun `hover shows readable class names not Smali format`() {
        // Index the source file (AST-based approach needs this!)
        index.indexFile(SmaliFile(
            uri = "file:///MyClass.smali",
            classDefinition = ClassDefinition(
                name = "LMyClass;",
                range = range(0, 0, 1, 0),
                modifiers = setOf("public"),
                superClass = "Landroid/app/Activity;",
                interfaces = emptyList()
            ),
            methods = emptyList(),
            fields = emptyList()
        ))
        
        // Position within class range (0, 0, 1, 0)
        val position = Position(0, 10)
        val hover = provider.provideHover("file:///MyClass.smali", position)
        
        assertNotNull(hover)
        val markup = hover.contents.right as org.eclipse.lsp4j.MarkupContent
        val content = markup.value
        // Should show "android.app.Activity" not "Landroid/app/Activity;"
        assertTrue(content.contains("android.app.Activity") || content.contains("Activity"))
        assertFalse(content.contains("Landroid/app/Activity;"))
    }

    // --- Opcode hover for instructions not captured in AST ---

    @Test
    fun `hover on return-void inside method body shows opcode docs`() {
        val smaliContent = """.class public Lcom/example/Test;
.super Ljava/lang/Object;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method"""
        val uri = "file:///test_hover.smali"

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
                    name = "<init>",
                    descriptor = "()V",
                    range = range(3, 0, 7, 11),
                    modifiers = setOf("public"),
                    parameters = emptyList(),
                    returnType = "V",
                    instructions = listOf(
                        InvokeInstruction("invoke-direct", "Ljava/lang/Object;", "<init>", "()V", range(5, 4, 5, 55))
                    )
                )
            ),
            fields = emptyList()
        )
        index.indexFile(file)
        index.setDocumentContent(uri, smaliContent)

        val hover = provider.provideHover(uri, Position(6, 6))

        assertNotNull(hover, "Hover should show opcode docs for return-void")
        val content = (hover.contents.right as org.eclipse.lsp4j.MarkupContent).value
        assertTrue(content.contains("return-void"), "Should contain opcode name")
        assertTrue(content.contains("0E") || content.contains("0e"), "Should contain opcode hex")
    }

    @Test
    fun `hover on const-4 inside method body shows opcode docs`() {
        val smaliContent = """.class public Lcom/example/Test;
.super Ljava/lang/Object;

.method public test()V
    .registers 2
    const/4 v0, 0x1
    return-void
.end method"""
        val uri = "file:///test_hover_const.smali"

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
                    returnType = "V"
                )
            ),
            fields = emptyList()
        )
        index.indexFile(file)
        index.setDocumentContent(uri, smaliContent)

        val hover = provider.provideHover(uri, Position(5, 6))

        assertNotNull(hover, "Hover should show opcode docs for const/4")
        val content = (hover.contents.right as org.eclipse.lsp4j.MarkupContent).value
        assertTrue(content.contains("const/4"), "Should contain opcode name")
        assertTrue(content.contains("12") || content.contains("0x12"), "Should contain opcode hex")
    }

    @Test
    fun `hover on aput-object inside method body shows opcode docs`() {
        val smaliContent = """.class public Lcom/example/Test;
.super Ljava/lang/Object;

.method public test()V
    .registers 4
    aput-object v0, v1, v2
    return-void
.end method"""
        val uri = "file:///test_hover_aput.smali"

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
                    returnType = "V"
                )
            ),
            fields = emptyList()
        )
        index.indexFile(file)
        index.setDocumentContent(uri, smaliContent)

        val hover = provider.provideHover(uri, Position(5, 6))

        assertNotNull(hover, "Hover should show opcode docs for aput-object")
        val content = (hover.contents.right as org.eclipse.lsp4j.MarkupContent).value
        assertTrue(content.contains("aput-object"), "Should contain opcode name")
        assertTrue(content.contains("4D") || content.contains("4d"), "Should contain opcode hex")
    }

    @Test
    fun `hover on move instruction inside method body shows opcode docs`() {
        val smaliContent = """.class public Lcom/example/Test;
.super Ljava/lang/Object;

.method public test()V
    .registers 2
    move v0, v1
    return-void
.end method"""
        val uri = "file:///test_hover_move.smali"

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
                    returnType = "V"
                )
            ),
            fields = emptyList()
        )
        index.indexFile(file)
        index.setDocumentContent(uri, smaliContent)

        val hover = provider.provideHover(uri, Position(5, 5))

        assertNotNull(hover, "Hover should show opcode docs for move")
        val content = (hover.contents.right as org.eclipse.lsp4j.MarkupContent).value
        assertTrue(content.contains("move"), "Should contain opcode name")
    }
}
