package xyz.surendrajat.smalilsp.unit.resolver

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

import xyz.surendrajat.smalilsp.resolver.TypeResolver
/**
 * Test TypeResolver with various Smali type descriptors.
 */
class TypeResolverTest {
    
    @Test
    fun `extract class name from simple object type`() {
        assertEquals("Ljava/lang/String;", TypeResolver.extractClassName("Ljava/lang/String;"))
        assertEquals("Ljava/lang/Integer;", TypeResolver.extractClassName("Ljava/lang/Integer;"))
        assertEquals("Lcom/example/MyClass;", TypeResolver.extractClassName("Lcom/example/MyClass;"))
    }
    
    @Test
    fun `extract class name from array type`() {
        assertEquals("Ljava/lang/String;", TypeResolver.extractClassName("[Ljava/lang/String;"))
        assertEquals("Ljava/lang/Integer;", TypeResolver.extractClassName("[[Ljava/lang/Integer;"))
        assertEquals("Lcom/example/MyClass;", TypeResolver.extractClassName("[[[Lcom/example/MyClass;"))
    }
    
    @Test
    fun `extract class name returns null for primitives`() {
        assertNull(TypeResolver.extractClassName("I"))
        assertNull(TypeResolver.extractClassName("J"))
        assertNull(TypeResolver.extractClassName("Z"))
        assertNull(TypeResolver.extractClassName("V"))
    }
    
    @Test
    fun `extract class name returns null for primitive arrays`() {
        assertNull(TypeResolver.extractClassName("[I"))
        assertNull(TypeResolver.extractClassName("[[J"))
        assertNull(TypeResolver.extractClassName("[[[Z"))
    }
    
    @Test
    fun `extract class names from method descriptor with primitives only`() {
        val classes = TypeResolver.extractClassNames("(IJ)V")
        assertTrue(classes.isEmpty(), "Primitive-only descriptor should have no classes")
    }
    
    @Test
    fun `extract class names from method descriptor with one object`() {
        val classes = TypeResolver.extractClassNames("(Ljava/lang/String;)V")
        assertEquals(1, classes.size)
        assertTrue(classes.contains("Ljava/lang/String;"))
    }
    
    @Test
    fun `extract class names from method descriptor with mixed types`() {
        val classes = TypeResolver.extractClassNames("(ILjava/lang/String;J)Ljava/lang/Integer;")
        assertEquals(2, classes.size)
        assertTrue(classes.contains("Ljava/lang/String;"))
        assertTrue(classes.contains("Ljava/lang/Integer;"))
    }
    
    @Test
    fun `extract class names from method descriptor with arrays`() {
        val classes = TypeResolver.extractClassNames("([Ljava/lang/String;[[I)[[Ljava/lang/Integer;")
        assertEquals(2, classes.size)
        assertTrue(classes.contains("Ljava/lang/String;"))
        assertTrue(classes.contains("Ljava/lang/Integer;"))
    }
    
    @Test
    fun `extract class names from complex method descriptor`() {
        // Real example: (Landroid/content/Context;Landroid/util/AttributeSet;)V
        val classes = TypeResolver.extractClassNames("(Landroid/content/Context;Landroid/util/AttributeSet;)V")
        assertEquals(2, classes.size)
        assertTrue(classes.contains("Landroid/content/Context;"))
        assertTrue(classes.contains("Landroid/util/AttributeSet;"))
    }
    
    @Test
    fun `extract class names from method with no parameters`() {
        val classes = TypeResolver.extractClassNames("()Ljava/lang/String;")
        assertEquals(1, classes.size)
        assertTrue(classes.contains("Ljava/lang/String;"))
    }
    
    @Test
    fun `extract class names from void returning method`() {
        val classes = TypeResolver.extractClassNames("(Ljava/lang/String;I)V")
        assertEquals(1, classes.size)
        assertTrue(classes.contains("Ljava/lang/String;"))
    }
    
    @Test
    fun `isPrimitive detects primitives correctly`() {
        assertTrue(TypeResolver.isPrimitive("I"))
        assertTrue(TypeResolver.isPrimitive("J"))
        assertTrue(TypeResolver.isPrimitive("Z"))
        assertTrue(TypeResolver.isPrimitive("B"))
        assertTrue(TypeResolver.isPrimitive("C"))
        assertTrue(TypeResolver.isPrimitive("S"))
        assertTrue(TypeResolver.isPrimitive("F"))
        assertTrue(TypeResolver.isPrimitive("D"))
        assertTrue(TypeResolver.isPrimitive("V"))
    }
    
    @Test
    fun `isPrimitive detects primitive arrays`() {
        assertTrue(TypeResolver.isPrimitive("[I"))
        assertTrue(TypeResolver.isPrimitive("[[J"))
        assertTrue(TypeResolver.isPrimitive("[[[Z"))
    }
    
    @Test
    fun `isPrimitive returns false for objects`() {
        assertFalse(TypeResolver.isPrimitive("Ljava/lang/String;"))
        assertFalse(TypeResolver.isPrimitive("[Ljava/lang/String;"))
        assertFalse(TypeResolver.isPrimitive("Lcom/example/MyClass;"))
    }
    
    @Test
    fun `toReadableName converts primitives`() {
        assertEquals("int", TypeResolver.toReadableName("I"))
        assertEquals("long", TypeResolver.toReadableName("J"))
        assertEquals("boolean", TypeResolver.toReadableName("Z"))
        assertEquals("byte", TypeResolver.toReadableName("B"))
        assertEquals("char", TypeResolver.toReadableName("C"))
        assertEquals("short", TypeResolver.toReadableName("S"))
        assertEquals("float", TypeResolver.toReadableName("F"))
        assertEquals("double", TypeResolver.toReadableName("D"))
        assertEquals("void", TypeResolver.toReadableName("V"))
    }
    
    @Test
    fun `toReadableName converts objects`() {
        assertEquals("java.lang.String", TypeResolver.toReadableName("Ljava/lang/String;"))
        assertEquals("java.lang.Integer", TypeResolver.toReadableName("Ljava/lang/Integer;"))
        assertEquals("com.example.MyClass", TypeResolver.toReadableName("Lcom/example/MyClass;"))
        assertEquals("android.content.Context", TypeResolver.toReadableName("Landroid/content/Context;"))
    }
    
    @Test
    fun `toReadableName converts arrays`() {
        assertEquals("int[]", TypeResolver.toReadableName("[I"))
        assertEquals("int[][]", TypeResolver.toReadableName("[[I"))
        assertEquals("java.lang.String[]", TypeResolver.toReadableName("[Ljava/lang/String;"))
        assertEquals("java.lang.String[][]", TypeResolver.toReadableName("[[Ljava/lang/String;"))
        assertEquals("android.content.Context[][][]", TypeResolver.toReadableName("[[[Landroid/content/Context;"))
    }
    
    @Test
    fun `toReadableName handles empty or malformed descriptors`() {
        assertEquals("unknown", TypeResolver.toReadableName(""))
        assertEquals("unknown", TypeResolver.toReadableName("X"))
    }
}
