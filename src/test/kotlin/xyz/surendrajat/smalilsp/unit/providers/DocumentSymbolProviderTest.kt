package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.SymbolKind
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.shared.TempTestWorkspace
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

import xyz.surendrajat.smalilsp.providers.DocumentSymbolProvider
import xyz.surendrajat.smalilsp.shared.TestWorkspace
/**
 * Tests for DocumentSymbolProvider.
 * 
 * Verifies document outline functionality for IDE's document outline view.
 */
class DocumentSymbolProviderTest {
    
    @Test
    fun `basic class with methods and fields`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Example.smali", """
            .class public Ltest/Example;
            .super Ljava/lang/Object;
            
            .field public name:Ljava/lang/String;
            .field private age:I
            
            .method public constructor <init>()V
                return-void
            .end method
            
            .method public getName()Ljava/lang/String;
                const/4 v0, 0x0
                return-object v0
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/Example.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        // Should have one class symbol
        assertEquals(1, symbols.size, "Should have one class symbol")
        val classSymbol = symbols[0]
        
        assertEquals(SymbolKind.Class, classSymbol.kind, "Should be a class")
        assertTrue(classSymbol.name.contains("Example"), "Class name should contain Example")
        
        // Should have 3 children: 1 constructor + 1 method + 2 fields
        assertNotNull(classSymbol.children, "Should have children")
        assertEquals(4, classSymbol.children.size, "Should have 4 children")
        
        // Check constructor
        val constructor = classSymbol.children.find { it.name == "<init>" }
        assertNotNull(constructor, "Should have constructor")
        assertEquals(SymbolKind.Constructor, constructor.kind, "Should be constructor kind")
        
        // Check method
        val method = classSymbol.children.find { it.name == "getName" }
        assertNotNull(method, "Should have getName method")
        assertEquals(SymbolKind.Method, method.kind, "Should be method kind")
        
        // Check fields
        val nameField = classSymbol.children.find { it.name == "name" }
        assertNotNull(nameField, "Should have name field")
        assertEquals(SymbolKind.Field, nameField.kind, "Should be field kind")
        
        val ageField = classSymbol.children.find { it.name == "age" }
        assertNotNull(ageField, "Should have age field")
        assertEquals(SymbolKind.Field, ageField.kind, "Should be field kind")
        
        workspace.cleanup()
    }
    
    @Test
    fun `empty class with no members`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Empty.smali", """
            .class public Ltest/Empty;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        val file = workspace.parseFile("test/Empty.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        assertEquals(1, symbols.size, "Should have one class symbol")
        val classSymbol = symbols[0]
        assertEquals(SymbolKind.Class, classSymbol.kind, "Should be a class")
        
        // No children (or empty list)
        val childCount = classSymbol.children?.size ?: 0
        assertEquals(0, childCount, "Should have no children")
        
        workspace.cleanup()
    }
    
    @Test
    fun `class with many methods shows all`() {
        val workspace = TempTestWorkspace.create()
        
        val methods = (1..15).map { i ->
            """
            .method public method$i()V
                return-void
            .end method
            """.trimIndent()
        }.joinToString("\n\n")
        
        workspace.addFile("test/ManyMethods.smali", """
            .class public Ltest/ManyMethods;
            .super Ljava/lang/Object;
            
            $methods
        """.trimIndent())
        
        val file = workspace.parseFile("test/ManyMethods.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        assertNotNull(classSymbol.children, "Should have children")
        assertEquals(15, classSymbol.children.size, "Should have 15 methods")
        
        // Verify all methods present
        for (i in 1..15) {
            val method = classSymbol.children.find { it.name == "method$i" }
            assertNotNull(method, "Should have method$i")
        }
        
        workspace.cleanup()
    }
    
    @Test
    fun `abstract class shows abstract in detail`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Abstract.smali", """
            .class public abstract Ltest/Abstract;
            .super Ljava/lang/Object;
            
            .method public abstract doWork()V
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/Abstract.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        assertNotNull(classSymbol.detail, "Should have detail")
        assertTrue(classSymbol.detail.contains("abstract"), "Detail should mention abstract")
        
        workspace.cleanup()
    }
    
    @Test
    fun `class with superclass shows extends in detail`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Derived.smali", """
            .class public Ltest/Derived;
            .super Ltest/Base;
        """.trimIndent())
        
        val file = workspace.parseFile("test/Derived.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        assertNotNull(classSymbol.detail, "Should have detail")
        assertTrue(classSymbol.detail.contains("extends"), "Detail should show inheritance")
        assertTrue(classSymbol.detail.contains("Base"), "Detail should show Base class")
        
        workspace.cleanup()
    }
    
    @Test
    fun `interface shows correct modifiers`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/IClickable.smali", """
            .class public abstract interface Ltest/IClickable;
            .super Ljava/lang/Object;
            
            .method public abstract onClick()V
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/IClickable.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        assertTrue(classSymbol.name.contains("IClickable"), "Should show interface name")
        
        // Should have abstract method
        assertNotNull(classSymbol.children, "Should have children")
        val method = classSymbol.children.find { it.name == "onClick" }
        assertNotNull(method, "Should have onClick method")
        
        workspace.cleanup()
    }
    
    @Test
    fun `field types are shown as readable names`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Fields.smali", """
            .class public Ltest/Fields;
            .super Ljava/lang/Object;
            
            .field public name:Ljava/lang/String;
            .field public count:I
            .field private data:[B
            .field protected callback:Ltest/Callback;
        """.trimIndent())
        
        val file = workspace.parseFile("test/Fields.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        assertNotNull(classSymbol.children, "Should have children")
        assertEquals(4, classSymbol.children.size, "Should have 4 fields")
        
        // Check that field details show types
        classSymbol.children.forEach { fieldSymbol ->
            assertNotNull(fieldSymbol.detail, "Field ${fieldSymbol.name} should have detail")
        }
        
        // Find name field and check type is readable
        val nameField = classSymbol.children.find { it.name == "name" }
        assertNotNull(nameField, "Should have name field")
        assertTrue(
            nameField.detail.contains("String") || nameField.detail.contains("java.lang.String"),
            "Field type should be readable, got: ${nameField.detail}"
        )
        
        workspace.cleanup()
    }
    
    @Test
    fun `method descriptors shown in detail`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Methods.smali", """
            .class public Ltest/Methods;
            .super Ljava/lang/Object;
            
            .method public foo()V
                return-void
            .end method
            
            .method public bar(I)Ljava/lang/String;
                const/4 v0, 0x0
                return-object v0
            .end method
            
            .method public baz(Ljava/lang/String;I)Z
                const/4 v0, 0x0
                return v0
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/Methods.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        assertNotNull(classSymbol.children, "Should have children")
        assertEquals(3, classSymbol.children.size, "Should have 3 methods")
        
        // Each method should have descriptor as detail
        classSymbol.children.forEach { methodSymbol ->
            assertNotNull(methodSymbol.detail, "Method ${methodSymbol.name} should have descriptor")
            assertTrue(
                methodSymbol.detail.contains("(") && methodSymbol.detail.contains(")"),
                "Method detail should be descriptor format"
            )
        }
        
        workspace.cleanup()
    }
    
    @Test
    fun `static initializer shown as constructor`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Static.smali", """
            .class public Ltest/Static;
            .super Ljava/lang/Object;
            
            .method public constructor <init>()V
                return-void
            .end method
            
            .method static constructor <clinit>()V
                return-void
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/Static.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        assertNotNull(classSymbol.children, "Should have children")
        
        // Both <init> and <clinit> should be Constructor kind
        val init = classSymbol.children.find { it.name == "<init>" }
        assertNotNull(init, "Should have <init>")
        assertEquals(SymbolKind.Constructor, init.kind, "<init> should be Constructor kind")
        
        val clinit = classSymbol.children.find { it.name == "<clinit>" }
        assertNotNull(clinit, "Should have <clinit>")
        assertEquals(SymbolKind.Constructor, clinit.kind, "<clinit> should be Constructor kind")
        
        workspace.cleanup()
    }
    
    @Test
    fun `ranges allow navigation to definitions`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Ranges.smali", """
            .class public Ltest/Ranges;
            .super Ljava/lang/Object;
            
            .field public value:I
            
            .method public getValue()I
                const/4 v0, 0x0
                return v0
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/Ranges.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        
        // Class range should be valid
        assertNotNull(classSymbol.range, "Class should have range")
        assertTrue(classSymbol.range.start.line >= 0, "Class range should start at line 0+")
        
        // Children should have valid ranges
        assertNotNull(classSymbol.children, "Should have children")
        classSymbol.children.forEach { child ->
            assertNotNull(child.range, "${child.name} should have range")
            assertNotNull(child.selectionRange, "${child.name} should have selection range")
            assertTrue(
                child.range.start.line >= 0,
                "${child.name} range should be valid"
            )
        }
        
        workspace.cleanup()
    }
}
