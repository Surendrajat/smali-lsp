package xyz.surendrajat.smalilsp.unit.data

import xyz.surendrajat.smalilsp.data.DalvikOpcodeDatabase
import kotlin.test.*

class DalvikOpcodeDatabaseTest {

    @Test
    fun `lookup known opcodes`() {
        val nop = DalvikOpcodeDatabase.lookup("nop")
        assertNotNull(nop)
        assertEquals("00", nop.opcode)
        assertEquals("nop", nop.name)

        val invokeVirtual = DalvikOpcodeDatabase.lookup("invoke-virtual")
        assertNotNull(invokeVirtual)
        assertEquals("6E", invokeVirtual.opcode)

        val constString = DalvikOpcodeDatabase.lookup("const-string")
        assertNotNull(constString)
        assertEquals("1A", constString.opcode)
    }

    @Test
    fun `lookup is case-insensitive`() {
        val upper = DalvikOpcodeDatabase.lookup("NOP")
        val lower = DalvikOpcodeDatabase.lookup("nop")
        val mixed = DalvikOpcodeDatabase.lookup("Nop")
        assertNotNull(upper)
        assertNotNull(lower)
        assertNotNull(mixed)
        assertEquals(upper, lower)
        assertEquals(lower, mixed)
    }

    @Test
    fun `lookup unknown opcode returns null`() {
        assertNull(DalvikOpcodeDatabase.lookup("not-a-real-opcode"))
        assertNull(DalvikOpcodeDatabase.lookup(""))
    }

    @Test
    fun `allInstructionNames excludes unused`() {
        val names = DalvikOpcodeDatabase.allInstructionNames()
        assertTrue(names.isNotEmpty())
        assertTrue(names.none { it.startsWith("unused_") })
        assertTrue(names.contains("nop"))
        assertTrue(names.contains("invoke-virtual"))
        assertTrue(names.contains("const-method-type"))
    }

    @Test
    fun `all major opcode families are present`() {
        // move variants
        assertNotNull(DalvikOpcodeDatabase.lookup("move"))
        assertNotNull(DalvikOpcodeDatabase.lookup("move/from16"))
        assertNotNull(DalvikOpcodeDatabase.lookup("move-wide"))
        assertNotNull(DalvikOpcodeDatabase.lookup("move-object"))
        assertNotNull(DalvikOpcodeDatabase.lookup("move-result"))
        assertNotNull(DalvikOpcodeDatabase.lookup("move-result-object"))
        assertNotNull(DalvikOpcodeDatabase.lookup("move-exception"))

        // return variants
        assertNotNull(DalvikOpcodeDatabase.lookup("return-void"))
        assertNotNull(DalvikOpcodeDatabase.lookup("return"))
        assertNotNull(DalvikOpcodeDatabase.lookup("return-wide"))
        assertNotNull(DalvikOpcodeDatabase.lookup("return-object"))

        // const variants
        assertNotNull(DalvikOpcodeDatabase.lookup("const/4"))
        assertNotNull(DalvikOpcodeDatabase.lookup("const/16"))
        assertNotNull(DalvikOpcodeDatabase.lookup("const"))
        assertNotNull(DalvikOpcodeDatabase.lookup("const-wide"))
        assertNotNull(DalvikOpcodeDatabase.lookup("const-string"))
        assertNotNull(DalvikOpcodeDatabase.lookup("const-class"))

        // field access
        assertNotNull(DalvikOpcodeDatabase.lookup("iget"))
        assertNotNull(DalvikOpcodeDatabase.lookup("iput-object"))
        assertNotNull(DalvikOpcodeDatabase.lookup("sget"))
        assertNotNull(DalvikOpcodeDatabase.lookup("sput-short"))

        // invoke variants
        assertNotNull(DalvikOpcodeDatabase.lookup("invoke-virtual"))
        assertNotNull(DalvikOpcodeDatabase.lookup("invoke-super"))
        assertNotNull(DalvikOpcodeDatabase.lookup("invoke-direct"))
        assertNotNull(DalvikOpcodeDatabase.lookup("invoke-static"))
        assertNotNull(DalvikOpcodeDatabase.lookup("invoke-interface"))
        assertNotNull(DalvikOpcodeDatabase.lookup("invoke-virtual/range"))

        // control flow
        assertNotNull(DalvikOpcodeDatabase.lookup("goto"))
        assertNotNull(DalvikOpcodeDatabase.lookup("if-eq"))
        assertNotNull(DalvikOpcodeDatabase.lookup("if-eqz"))
        assertNotNull(DalvikOpcodeDatabase.lookup("packed-switch"))

        // binary ops
        assertNotNull(DalvikOpcodeDatabase.lookup("add-int"))
        assertNotNull(DalvikOpcodeDatabase.lookup("sub-long"))
        assertNotNull(DalvikOpcodeDatabase.lookup("mul-float"))

        // 2addr variants
        assertNotNull(DalvikOpcodeDatabase.lookup("add-int/2addr"))
        assertNotNull(DalvikOpcodeDatabase.lookup("rem-double/2addr"))

        // lit variants
        assertNotNull(DalvikOpcodeDatabase.lookup("add-int/lit16"))
        assertNotNull(DalvikOpcodeDatabase.lookup("rsub-int"))
        assertNotNull(DalvikOpcodeDatabase.lookup("add-int/lit8"))
        assertNotNull(DalvikOpcodeDatabase.lookup("ushr-int/lit8"))

        // conversions
        assertNotNull(DalvikOpcodeDatabase.lookup("int-to-long"))
        assertNotNull(DalvikOpcodeDatabase.lookup("double-to-float"))

        // newer opcodes
        assertNotNull(DalvikOpcodeDatabase.lookup("invoke-polymorphic"))
        assertNotNull(DalvikOpcodeDatabase.lookup("invoke-custom"))
        assertNotNull(DalvikOpcodeDatabase.lookup("const-method-handle"))
        assertNotNull(DalvikOpcodeDatabase.lookup("const-method-type"))
    }

    @Test
    fun `toMarkdown includes key fields`() {
        val info = DalvikOpcodeDatabase.lookup("invoke-virtual")!!
        val md = info.toMarkdown()
        assertTrue(md.contains("INVOKE-VIRTUAL"))
        assertTrue(md.contains("0x6E"))
        assertTrue(md.contains("35c"))
        assertTrue(md.contains("Syntax"))
        assertTrue(md.contains("meth@BBBB"))
    }

    @Test
    fun `toCompletionDoc is concise`() {
        val info = DalvikOpcodeDatabase.lookup("nop")!!
        val doc = info.toCompletionDoc()
        assertTrue(doc.contains("nop"))
        assertTrue(doc.contains("Syntax"))
        // Completion doc should be shorter than full markdown
        assertTrue(doc.length < info.toMarkdown().length)
    }

    @Test
    fun `array operations are complete`() {
        val arrayOps = listOf(
            "aget", "aget-wide", "aget-object", "aget-boolean",
            "aget-byte", "aget-char", "aget-short",
            "aput", "aput-wide", "aput-object", "aput-boolean",
            "aput-byte", "aput-char", "aput-short"
        )
        for (op in arrayOps) {
            assertNotNull(DalvikOpcodeDatabase.lookup(op), "Missing array op: $op")
        }
    }

    @Test
    fun `comparison operations are complete`() {
        val cmpOps = listOf("cmpl-float", "cmpg-float", "cmpl-double", "cmpg-double", "cmp-long")
        for (op in cmpOps) {
            assertNotNull(DalvikOpcodeDatabase.lookup(op), "Missing cmp op: $op")
        }
    }

    @Test
    fun `unary operations are complete`() {
        val unaryOps = listOf(
            "neg-int", "not-int", "neg-long", "not-long", "neg-float", "neg-double",
            "int-to-long", "int-to-float", "int-to-double",
            "long-to-int", "long-to-float", "long-to-double",
            "float-to-int", "float-to-long", "float-to-double",
            "double-to-int", "double-to-long", "double-to-float",
            "int-to-byte", "int-to-char", "int-to-short"
        )
        for (op in unaryOps) {
            assertNotNull(DalvikOpcodeDatabase.lookup(op), "Missing unary op: $op")
        }
    }
}
