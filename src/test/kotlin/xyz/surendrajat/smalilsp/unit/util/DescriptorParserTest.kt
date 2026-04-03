package xyz.surendrajat.smalilsp.unit.util

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.util.DescriptorParser
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the consolidated DescriptorParser utility.
 */
class DescriptorParserTest {

    @Test
    fun `parse empty string returns empty list`() {
        assertEquals(emptyList(), DescriptorParser.parseTypeSequence(""))
    }

    @Test
    fun `parse single primitive type`() {
        val types = DescriptorParser.parseTypeSequence("I")
        assertEquals(1, types.size)
        assertEquals("I", types[0].type)
        assertTrue(types[0].isPrimitive)
    }

    @Test
    fun `parse multiple primitive types`() {
        val types = DescriptorParser.parseTypeSequence("IJZ")
        assertEquals(3, types.size)
        assertEquals("I", types[0].type)
        assertEquals("J", types[1].type)
        assertEquals("Z", types[2].type)
        assertTrue(types.all { it.isPrimitive })
    }

    @Test
    fun `parse object type`() {
        val types = DescriptorParser.parseTypeSequence("Ljava/lang/String;")
        assertEquals(1, types.size)
        assertEquals("Ljava/lang/String;", types[0].type)
        assertTrue(types[0].isClassType)
        assertEquals("Ljava/lang/String;", types[0].className)
    }

    @Test
    fun `parse mixed primitive and object types`() {
        val types = DescriptorParser.parseTypeSequence("ILjava/lang/String;Z")
        assertEquals(3, types.size)
        assertEquals("I", types[0].type)
        assertEquals("Ljava/lang/String;", types[1].type)
        assertEquals("Z", types[2].type)
    }

    @Test
    fun `parse primitive array`() {
        val types = DescriptorParser.parseTypeSequence("[I")
        assertEquals(1, types.size)
        assertEquals("[I", types[0].type)
        assertTrue(types[0].isArray)
        assertTrue(types[0].isPrimitive)
    }

    @Test
    fun `parse multi-dimensional primitive array`() {
        val types = DescriptorParser.parseTypeSequence("[[J")
        assertEquals(1, types.size)
        assertEquals("[[J", types[0].type)
        assertTrue(types[0].isArray)
    }

    @Test
    fun `parse object array`() {
        val types = DescriptorParser.parseTypeSequence("[Ljava/lang/String;")
        assertEquals(1, types.size)
        assertEquals("[Ljava/lang/String;", types[0].type)
        assertTrue(types[0].isArray)
        assertTrue(types[0].isClassType)
        assertEquals("Ljava/lang/String;", types[0].className)
    }

    @Test
    fun `parse multi-dimensional object array`() {
        val types = DescriptorParser.parseTypeSequence("[[Ljava/lang/Object;")
        assertEquals(1, types.size)
        assertEquals("[[Ljava/lang/Object;", types[0].type)
        assertEquals("Ljava/lang/Object;", types[0].className)
        assertTrue(types[0].isArray)
    }

    @Test
    fun `parse full method descriptor with parens`() {
        val types = DescriptorParser.parseTypeSequence("(ILjava/lang/String;)V")
        assertEquals(3, types.size)
        assertEquals("I", types[0].type)
        assertEquals("Ljava/lang/String;", types[1].type)
        assertEquals("V", types[2].type)
    }

    @Test
    fun `parse complex descriptor`() {
        val types = DescriptorParser.parseTypeSequence("(ILjava/lang/String;[I[[Ljava/lang/Object;Z)Ljava/lang/Integer;")
        assertEquals(6, types.size)
        assertEquals("I", types[0].type)
        assertEquals("Ljava/lang/String;", types[1].type)
        assertEquals("[I", types[2].type)
        assertEquals("[[Ljava/lang/Object;", types[3].type)
        assertEquals("Z", types[4].type)
        assertEquals("Ljava/lang/Integer;", types[5].type)
    }

    // --- extractClassNames ---

    @Test
    fun `extractClassNames from method descriptor`() {
        val classes = DescriptorParser.extractClassNames("(ILjava/lang/String;[Ljava/lang/Integer;)V")
        assertEquals(setOf("Ljava/lang/String;", "Ljava/lang/Integer;"), classes)
    }

    @Test
    fun `extractClassNames ignores primitives`() {
        val classes = DescriptorParser.extractClassNames("(IJZ)V")
        assertTrue(classes.isEmpty())
    }

    @Test
    fun `extractClassNames from array of objects`() {
        val classes = DescriptorParser.extractClassNames("[[Ljava/lang/Object;")
        assertEquals(setOf("Ljava/lang/Object;"), classes)
    }

    // --- typeAtOffset ---

    @Test
    fun `typeAtOffset finds primitive`() {
        val span = DescriptorParser.typeAtOffset("IJZ", 1)
        assertNotNull(span)
        assertEquals("J", span.type)
    }

    @Test
    fun `typeAtOffset finds object type at start`() {
        val span = DescriptorParser.typeAtOffset("Ljava/lang/String;", 0)
        assertNotNull(span)
        assertEquals("Ljava/lang/String;", span.type)
    }

    @Test
    fun `typeAtOffset finds object type at middle`() {
        val span = DescriptorParser.typeAtOffset("Ljava/lang/String;", 10)
        assertNotNull(span)
        assertEquals("Ljava/lang/String;", span.type)
    }

    @Test
    fun `typeAtOffset returns null for parens`() {
        val span = DescriptorParser.typeAtOffset("(I)V", 0) // on '('
        assertNull(span)
    }

    @Test
    fun `typeAtOffset after parens`() {
        val span = DescriptorParser.typeAtOffset("(I)V", 1) // on 'I'
        assertNotNull(span)
        assertEquals("I", span.type)
    }

    @Test
    fun `typeAtOffset on array bracket`() {
        val span = DescriptorParser.typeAtOffset("[I", 0) // on '['
        assertNotNull(span)
        assertEquals("[I", span.type)
        assertTrue(span.isArray)
    }

    // --- Edge cases ---

    @Test
    fun `truncated object type is skipped`() {
        val types = DescriptorParser.parseTypeSequence("ILcom/foo")
        assertEquals(1, types.size)
        assertEquals("I", types[0].type)
    }

    @Test
    fun `truncated array at end is handled`() {
        val types = DescriptorParser.parseTypeSequence("I[")
        assertEquals(1, types.size)
        assertEquals("I", types[0].type)
    }

    @Test
    fun `offset tracking is correct`() {
        val types = DescriptorParser.parseTypeSequence("ILjava/lang/String;Z")
        assertEquals(0, types[0].start)
        assertEquals(1, types[0].end)
        assertEquals(1, types[1].start)
        assertEquals(19, types[1].end)
        assertEquals(19, types[2].start)
        assertEquals(20, types[2].end)
    }
}
