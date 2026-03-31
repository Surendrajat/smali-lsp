package xyz.surendrajat.smalilsp.regression

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.integration.lsp.TempTestWorkspace
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

import xyz.surendrajat.smalilsp.providers.DocumentSymbolProvider
import xyz.surendrajat.smalilsp.integration.lsp.TestWorkspace
/**
 * Edge case tests for DocumentSymbolProvider.
 * 
 * Tests unusual/malformed input that might occur in real-world files.
 */
class DocumentSymbolProviderEdgeCaseTest {
    
    @Test
    fun `file with only class definition - no members`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Empty.smali", """
            .class public Ltest/Empty;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        val file = workspace.parseFile("test/Empty.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        assertEquals(1, symbols.size)
        val classSymbol = symbols[0]
        assertTrue(classSymbol.name.contains("Empty"))
        
        val childCount = classSymbol.children?.size ?: 0
        assertEquals(0, childCount, "Empty class should have no children")
        
        workspace.cleanup()
    }
    
    @Test
    fun `inner classes shown correctly`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Outer.smali", """
            .class public Ltest/Outer;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        workspace.addFile("test/Outer${'$'}Inner.smali", """
            .class public Ltest/Outer${'$'}Inner;
            .super Ljava/lang/Object;
            
            .method public innerMethod()V
                return-void
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/Outer${'$'}Inner.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        assertEquals(1, symbols.size)
        val classSymbol = symbols[0]
        assertTrue(classSymbol.name.contains("Inner") || classSymbol.name.contains("Outer"))
        
        // Should have inner method
        assertNotNull(classSymbol.children)
        val method = classSymbol.children.find { it.name == "innerMethod" }
        assertNotNull(method, "Inner class should have its method")
        
        workspace.cleanup()
    }
    
    @Test
    fun `anonymous classes handled`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Outer${'$'}1.smali", """
            .class Ltest/Outer${'$'}1;
            .super Ljava/lang/Object;
            .source "Outer.java"
            
            .method public run()V
                return-void
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/Outer${'$'}1.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        assertEquals(1, symbols.size)
        val classSymbol = symbols[0]
        // Anonymous class name will contain $1
        assertTrue(classSymbol.name.isNotEmpty())
        
        workspace.cleanup()
    }
    
    @Test
    fun `generic types in field signatures`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Generics.smali", """
            .class public Ltest/Generics;
            .super Ljava/lang/Object;
            
            .field public list:Ljava/util/List;
            .field public map:Ljava/util/Map;
            .field private callback:Ltest/Callback;
        """.trimIndent())
        
        val file = workspace.parseFile("test/Generics.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        assertNotNull(classSymbol.children)
        assertEquals(3, classSymbol.children.size)
        
        // Each field should have readable type
        classSymbol.children.forEach { field ->
            assertNotNull(field.detail, "Field ${field.name} should have type detail")
            assertTrue(field.detail.isNotEmpty(), "Field ${field.name} type should not be empty")
        }
        
        workspace.cleanup()
    }
    
    @Test
    fun `array types shown correctly`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Arrays.smali", """
            .class public Ltest/Arrays;
            .super Ljava/lang/Object;
            
            .field public bytes:[B
            .field public ints:[I
            .field public strings:[Ljava/lang/String;
            .field public matrix:[[I
        """.trimIndent())
        
        val file = workspace.parseFile("test/Arrays.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        assertNotNull(classSymbol.children)
        assertEquals(4, classSymbol.children.size)
        
        // Array types should be readable
        val bytes = classSymbol.children.find { it.name == "bytes" }
        assertNotNull(bytes)
        assertTrue(bytes.detail.isNotEmpty())
        
        workspace.cleanup()
    }
    
    @Test
    fun `primitive types shown correctly`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Primitives.smali", """
            .class public Ltest/Primitives;
            .super Ljava/lang/Object;
            
            .field public intValue:I
            .field public longValue:J
            .field public floatValue:F
            .field public doubleValue:D
            .field public boolValue:Z
            .field public byteValue:B
            .field public charValue:C
            .field public shortValue:S
        """.trimIndent())
        
        val file = workspace.parseFile("test/Primitives.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        assertNotNull(classSymbol.children)
        assertEquals(8, classSymbol.children.size, "Should have all 8 primitive fields")
        
        // Each primitive should have type detail
        classSymbol.children.forEach { field ->
            assertNotNull(field.detail, "Field ${field.name} should have type")
        }
        
        workspace.cleanup()
    }
    
    @Test
    fun `synthetic methods included`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Synthetic.smali", """
            .class public Ltest/Synthetic;
            .super Ljava/lang/Object;
            
            .method public synthetic access${'$'}000()V
                return-void
            .end method
            
            .method public normal()V
                return-void
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/Synthetic.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        assertNotNull(classSymbol.children)
        assertEquals(2, classSymbol.children.size, "Should include synthetic methods")
        
        val synthetic = classSymbol.children.find { it.name.contains("access") }
        assertNotNull(synthetic, "Synthetic method should be present")
        
        workspace.cleanup()
    }
    
    @Test
    fun `bridge methods included`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Bridge.smali", """
            .class public Ltest/Bridge;
            .super Ljava/lang/Object;
            
            .method public bridge synthetic compare(Ljava/lang/Object;Ljava/lang/Object;)I
                return 0
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/Bridge.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        assertNotNull(classSymbol.children)
        val bridge = classSymbol.children.find { it.name == "compare" }
        assertNotNull(bridge, "Bridge method should be present")
        
        workspace.cleanup()
    }
    
    @Test
    fun `annotation classes handled`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/MyAnnotation.smali", """
            .class public interface abstract annotation Ltest/MyAnnotation;
            .super Ljava/lang/Object;
            .implements Ljava/lang/annotation/Annotation;
            
            .method public abstract value()Ljava/lang/String;
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/MyAnnotation.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        assertEquals(1, symbols.size)
        val classSymbol = symbols[0]
        assertTrue(classSymbol.name.contains("MyAnnotation"))
        
        // Should have abstract method
        assertNotNull(classSymbol.children)
        val method = classSymbol.children.find { it.name == "value" }
        assertNotNull(method)
        
        workspace.cleanup()
    }
    
    @Test
    fun `enum classes handled`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Color.smali", """
            .class public final enum Ltest/Color;
            .super Ljava/lang/Enum;
            
            .field public static final enum RED:Ltest/Color;
            .field public static final enum GREEN:Ltest/Color;
            .field public static final enum BLUE:Ltest/Color;
            
            .method public static values()[Ltest/Color;
                const/4 v0, 0x0
                return-object v0
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/Color.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        assertTrue(classSymbol.name.contains("Color"))
        
        // Should have enum constants as fields
        assertNotNull(classSymbol.children)
        assertTrue(classSymbol.children.size >= 3, "Should have at least 3 enum constants")
        
        workspace.cleanup()
    }
    
    @Test
    fun `method with many parameters`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/ManyParams.smali", """
            .class public Ltest/ManyParams;
            .super Ljava/lang/Object;
            
            .method public complex(ILjava/lang/String;Z[BLjava/util/List;Ltest/Callback;JDF)V
                return-void
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/ManyParams.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        assertNotNull(classSymbol.children)
        
        val method = classSymbol.children.find { it.name == "complex" }
        assertNotNull(method)
        assertNotNull(method.detail, "Method with many params should have descriptor")
        
        workspace.cleanup()
    }
    
    @Test
    fun `varargs methods handled`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Varargs.smali", """
            .class public Ltest/Varargs;
            .super Ljava/lang/Object;
            
            .method public varargs format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;
                const/4 v0, 0x0
                return-object v0
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/Varargs.smali")
        val provider = DocumentSymbolProvider()
        val symbols = provider.provideDocumentSymbols(file)
        
        val classSymbol = symbols[0]
        val method = classSymbol.children?.find { it.name == "format" }
        assertNotNull(method)
        
        workspace.cleanup()
    }
}
