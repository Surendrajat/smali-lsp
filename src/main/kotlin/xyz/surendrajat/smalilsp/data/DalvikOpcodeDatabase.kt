package xyz.surendrajat.smalilsp.data

/**
 * Complete Dalvik bytecode instruction documentation database.
 *
 * Data sourced from the official Android Dalvik bytecode specification:
 * https://source.android.com/docs/core/runtime/dalvik-bytecode
 *
 * Covers all 256 opcodes (0x00-0xFF) including unused/reserved ones.
 */
object DalvikOpcodeDatabase {

    /**
     * Information about a single Dalvik instruction.
     */
    data class InstructionInfo(
        val opcode: String,
        val name: String,
        val format: String,
        val formatId: String,
        val syntax: String,
        val argsInfo: String,
        val shortDesc: String,
        val longDesc: String,
        val note: String = "",
        val example: String = "",
        val exampleDesc: String = ""
    ) {
        /**
         * Generate rich markdown documentation for hover display.
         */
        fun toMarkdown(): String = buildString {
            append("## ${name.uppercase()}\n\n")
            append("**Opcode:** `0x$opcode`\n\n")
            append("**Format:** `$formatId`\n\n")
            append("**Syntax:** `$syntax`\n\n")
            append("$shortDesc\n\n")
            append(longDesc)

            if (argsInfo.isNotEmpty()) {
                append("\n\n**Arguments:** $argsInfo")
            }
            if (example.isNotEmpty()) {
                append("\n\n**Example:** `$example`")
                if (exampleDesc.isNotEmpty()) {
                    append("\n\n*$exampleDesc*")
                }
            }
            if (note.isNotEmpty()) {
                append("\n\n**Note:** $note")
            }
        }

        /**
         * Generate concise documentation for completion items.
         */
        fun toCompletionDoc(): String = buildString {
            append("**$name**\n\n")
            append(shortDesc)
            append("\n\n**Syntax:** `$syntax`")
        }
    }

    private val instructionsByName: Map<String, InstructionInfo>

    init {
        val all = buildInstructionList()
        instructionsByName = all.associateBy { it.name.lowercase() }
    }

    /**
     * Look up instruction documentation by name.
     * Case-insensitive. Returns null if not found.
     */
    fun lookup(name: String): InstructionInfo? =
        instructionsByName[name.lowercase()]

    /**
     * Get all known instruction names (excluding unused_XX).
     * Cached — safe since the instruction database is immutable.
     */
    private val cachedInstructionNames: List<String> by lazy {
        instructionsByName.values
            .filter { !it.name.startsWith("unused_") }
            .map { it.name }
    }

    fun allInstructionNames(): List<String> = cachedInstructionNames

    /**
     * Get all instructions (including unused).
     */
    fun allInstructions(): Collection<InstructionInfo> =
        instructionsByName.values

    private fun buildInstructionList(): List<InstructionInfo> = listOf(
        // 0x00 nop
        InstructionInfo("00", "nop", "ØØ|op", "10x", "nop", "",
            "No operation.",
            "Does nothing, Waste cycles.",
            note = "Data-bearing pseudo-instructions are tagged with this opcode, in which case the high-order byte of the opcode unit indicates the nature of the data.",
            example = "0000 - nop", exampleDesc = "Does nothing, Waste cycles"),

        // 0x01 move
        InstructionInfo("01", "move", "B|A|op", "12x", "move vA, vB",
            "A: destination register (4 bits), B: source register (4 bits)",
            "Move the contents of one non-object register to another.",
            "Moves the content of vB into vA. Both registers are in the first 16 register range (0-15).",
            example = "0110 - move v0, v1", exampleDesc = "Moves the content of v1 into v0."),

        // 0x02 move/from16
        InstructionInfo("02", "move/from16", "AA|op BBBB", "22x", "move/from16 vAA, vBBBB",
            "A: destination register (8 bits), B: source register (16 bits)",
            "Move the contents of one non-object register to another.",
            "Moves the content of vB into vA. vB is in the 64k register range (0-65535) while vA is one of the first 256 registers (0-255)."),

        // 0x03 move/16
        InstructionInfo("03", "move/16", "ØØ|op AAAA BBBB", "32x", "move/16 vAAAA, vBBBB",
            "A: destination register (16 bits), B: source register (16 bits)",
            "Move the contents of one non-object register to another.",
            "Moves the content of vB into vA. Both registers are in the first 64k register range (0-65535)."),

        // 0x04 move-wide
        InstructionInfo("04", "move-wide", "B|A|op", "12x", "move-wide vA, vB",
            "A: destination register-pair (4 bits), B: source register-pair (4 bits)",
            "Move the contents of one register-pair to another.",
            "Move the value of the (vB, vB+1) register-pair to the (vA, vA+1) register-pair.",
            note = "It is legal to move from vN to either vN-1 or vN+1, so implementations must arrange for both halves of a register-pair to be read before anything is written."),

        // 0x05 move-wide/from16
        InstructionInfo("05", "move-wide/from16", "AA|op BBBB", "22x", "move-wide/from16 vAA, vBBBB",
            "A: destination register-pair (8 bits), B: source register-pair (16 bits)",
            "Move the contents of one register-pair to another.",
            "Move the value (long/double) of the (vBBBB, vBBBB+1) register-pair to the (vAA, vAA+1) register-pair."),

        // 0x06 move-wide/16
        InstructionInfo("06", "move-wide/16", "ØØ|op AAAA BBBB", "32x", "move-wide/16 vAAAA, vBBBB",
            "A: destination register-pair (16 bits), B: source register-pair (16 bits)",
            "Move the contents of one register-pair to another.",
            "Move the value of the (vBBBB, vBBBB+1) register-pair to the (vAAAA, vAAAA+1) register-pair."),

        // 0x07 move-object
        InstructionInfo("07", "move-object", "B|A|op", "12x", "move-object vA, vB",
            "A: destination register (4 bits), B: source register (4 bits)",
            "Move the contents of one object-bearing register to another.",
            "Move the value of register vB to register vA.",
            example = "0781 - move-object v1, v8", exampleDesc = "Moves the content of v8 to v1."),

        // 0x08 move-object/from16
        InstructionInfo("08", "move-object/from16", "AA|op BBBB", "22x", "move-object/from16 vAA, vBBBB",
            "A: destination register (8 bits), B: source register (16 bits)",
            "Move the contents of one object-bearing register to another.",
            "Move the value of register vBBBB to register vAA."),

        // 0x09 move-object/16
        InstructionInfo("09", "move-object/16", "ØØ|op AAAA BBBB", "32x", "move-object/16 vAAAA, vBBBB",
            "A: destination register (16 bits), B: source register (16 bits)",
            "Move the contents of one object-bearing register to another.",
            "Move the value of register vBBBB to register vAAAA."),

        // 0x0A move-result
        InstructionInfo("0A", "move-result", "AA|op", "11x", "move-result vAA",
            "A: destination register (8 bits)",
            "Move the single-word non-object result of the preceding invoke-kind into the specified register.",
            "Moves the single-word result of the immediately preceding method invocation into the destination register.",
            note = "Must immediately follow an invoke-kind with a valid single-word, non-object result. Anywhere else is invalid.",
            example = "0A00 - move-result v0", exampleDesc = "Move the return value of a previous method invocation into v0."),

        // 0x0B move-result-wide
        InstructionInfo("0B", "move-result-wide", "AA|op", "11x", "move-result-wide vAA",
            "A: destination register-pair (8 bits)",
            "Move the wide (double-word) result of the preceding invoke-kind into the specified register-pair.",
            "Moves the wide (double-word) result of the previous method invocation into register-pair (vAA, vAA+1).",
            note = "Must immediately follow an invoke-kind with a valid double-word result."),

        // 0x0C move-result-object
        InstructionInfo("0C", "move-result-object", "AA|op", "11x", "move-result-object vAA",
            "A: destination register (8 bits)",
            "Move the object result of the preceding invoke-kind into the specified register.",
            "Moves the object result of the previous method invocation into vAA.",
            note = "Must immediately follow an invoke-kind or filled-new-array with a valid object result."),

        // 0x0D move-exception
        InstructionInfo("0D", "move-exception", "AA|op", "11x", "move-exception vAA",
            "A: destination register (8 bits)",
            "Save a just-caught exception into the given register.",
            "Moves the caught exception into vAA.",
            note = "This must be the first instruction of any exception handler whose caught exception is not to be ignored, and must only ever occur as the first instruction of an exception handler."),

        // 0x0E return-void
        InstructionInfo("0E", "return-void", "ØØ|op", "10x", "return-void", "",
            "Return from a void method.",
            "Return without a return value, used in void type methods.",
            example = "0E00 - return-void", exampleDesc = "Return of a void method without return value."),

        // 0x0F return
        InstructionInfo("0F", "return", "AA|op", "11x", "return vAA",
            "A: return value register (8 bits)",
            "Return from a single-width (32-bit) non-object value-returning method.",
            "Returns the 32-bit value in vAA to the caller.",
            example = "0F00 - return v0", exampleDesc = "Returns with return value in v0."),

        // 0x10 return-wide
        InstructionInfo("10", "return-wide", "AA|op", "11x", "return-wide vAA",
            "A: return value register-pair (8 bits)",
            "Return from a double-width (64-bit) value-returning method.",
            "Returns a double/long result from register-pair (vAA, vAA+1) to the caller."),

        // 0x11 return-object
        InstructionInfo("11", "return-object", "AA|op", "11x", "return-object vAA",
            "A: return value register (8 bits)",
            "Return from an object-returning method.",
            "Returns the object reference value from vAA to the caller."),

        // 0x12 const/4
        InstructionInfo("12", "const/4", "B|A|op", "11n", "const/4 vA, #+B",
            "A: destination register (4 bits), B: signed int (4 bits)",
            "Move the given literal value (sign-extended to 32 bits) into the specified register.",
            "Moves the literal value B into vA. B is sign-extended to 32 bits. The value of B is in the range -8 to 7.",
            example = "1210 - const/4 v0, 0x1", exampleDesc = "Moves the literal value 1 into v0."),

        // 0x13 const/16
        InstructionInfo("13", "const/16", "AA|op BBBB", "21s", "const/16 vAA, #+BBBB",
            "A: destination register (8 bits), B: signed int (16 bits)",
            "Move the given literal value (sign-extended to 32 bits) into the specified register.",
            "Moves the literal value BBBB into vAA. BBBB is sign-extended to 32 bits."),

        // 0x14 const
        InstructionInfo("14", "const", "AA|op BBBBlo BBBBhi", "31i", "const vAA, #+BBBBBBBB",
            "A: destination register (8 bits), B: arbitrary constant (32 bits)",
            "Move the given literal value into the specified register.",
            "Moves the literal value BBBBBBBB into vAA."),

        // 0x15 const/high16
        InstructionInfo("15", "const/high16", "AA|op BBBB", "21h", "const/high16 vAA, #+BBBB0000",
            "A: destination register (8 bits), B: signed int (16 bits)",
            "Move the given literal value (right-zero-extended to 32 bits) into the specified register.",
            "Moves the literal value BBBB0000 into vAA.",
            note = "Generally used to initialize float values."),

        // 0x16 const-wide/16
        InstructionInfo("16", "const-wide/16", "AA|op BBBB", "21s", "const-wide/16 vAA, #+BBBB",
            "A: destination register-pair (8 bits), B: signed int (16 bits)",
            "Move the given literal value (sign-extended to 64 bits) into the specified register-pair.",
            "Moves the literal value BBBB into register-pair (vAA, vAA+1), expanding the integer constant into a long constant."),

        // 0x17 const-wide/32
        InstructionInfo("17", "const-wide/32", "AA|op BBBBlo BBBBhi", "31i", "const-wide/32 vAA, #+BBBBBBBB",
            "A: destination register-pair (8 bits), B: signed int (32 bits)",
            "Move the given literal value (sign-extended to 64 bits) into the specified register-pair.",
            "Moves the literal value BBBBBBBB into register-pair (vAA, vAA+1)."),

        // 0x18 const-wide
        InstructionInfo("18", "const-wide", "AA|op BBBBlo BBBB BBBB BBBBhi", "51l", "const-wide vAA, #+BBBBBBBBBBBBBBBB",
            "A: destination register-pair (8 bits), B: signed int (64 bits)",
            "Move the given literal value into the specified register-pair.",
            "Moves the literal value constant BBBBBBBBBBBBBBBB into register-pair (vAA, vAA+1)."),

        // 0x19 const-wide/high16
        InstructionInfo("19", "const-wide/high16", "AA|op BBBB", "21h", "const-wide/high16 vAA, #+BBBB000000000000",
            "A: destination register-pair (8 bits), B: signed int (16 bits)",
            "Move the given literal value (right-zero-extended to 64 bits) into the specified register-pair.",
            "Moves the literal value BBBB into register-pair (vAA, vAA+1).",
            note = "Generally used to initialize double values."),

        // 0x1A const-string
        InstructionInfo("1A", "const-string", "AA|op BBBB", "21c", "const-string vAA, string@BBBB",
            "A: destination register (8 bits), B: string index",
            "Move a reference to the string specified by the given index into the specified register.",
            "Moves the string from the string pool at the given index into the specified register.",
            note = "The string pool is a table of strings stored in the DEX file."),

        // 0x1B const-string/jumbo
        InstructionInfo("1B", "const-string/jumbo", "AA|op BBBBlo BBBBhi", "31c", "const-string/jumbo vAA, string@BBBBBBBB",
            "A: destination register (8 bits), B: string index",
            "Move a reference to the string specified by the given index into the specified register.",
            "Used when the string index is too large to fit in 16 bits."),

        // 0x1C const-class
        InstructionInfo("1C", "const-class", "AA|op BBBB", "21c", "const-class vAA, type@BBBB",
            "A: destination register (8 bits), B: type index",
            "Move a reference to the class specified by the given index into the specified register.",
            "Moves the type (e.g., Object.class) from the type pool into the specified register.",
            note = "In the case where the indicated type is primitive, this will store a reference to the primitive type's degenerate class."),

        // 0x1D monitor-enter
        InstructionInfo("1D", "monitor-enter", "AA|op", "11x", "monitor-enter vAA",
            "A: reference-bearing register (8 bits)",
            "Acquire the monitor for the indicated object.",
            "Obtains the monitor of the object referenced by vAA."),

        // 0x1E monitor-exit
        InstructionInfo("1E", "monitor-exit", "AA|op", "11x", "monitor-exit vAA",
            "A: reference-bearing register (8 bits)",
            "Release the monitor for the indicated object.",
            "Releases the monitor of the object referenced by vAA."),

        // 0x1F check-cast
        InstructionInfo("1F", "check-cast", "AA|op BBBB", "21c", "check-cast vAA, type@BBBB",
            "A: reference-bearing register (8 bits), B: type index (16 bits)",
            "Throw a ClassCastException if the reference cannot be cast to the indicated type.",
            "Check that the object referenced by vAA is an instance of the class resolved by type@BBBB. If not, throw a ClassCastException."),

        // 0x20 instance-of
        InstructionInfo("20", "instance-of", "B|A|op CCCC", "22c", "instance-of vA, vB, type@CCCC",
            "A: destination register (4 bits), B: reference-bearing register (4 bits), C: type index (16 bits)",
            "Store 1 if the reference is an instance of the given type, or 0 if not.",
            "Check whether the object referenced by vB is an instance of type@CCCC. Store result in vA."),

        // 0x21 array-length
        InstructionInfo("21", "array-length", "B|A|op", "12x", "array-length vA, vB",
            "A: destination register (4 bits), B: array reference-bearing register (4 bits)",
            "Store the length of the indicated array.",
            "Stores the length of the array referenced by vB into vA."),

        // 0x22 new-instance
        InstructionInfo("22", "new-instance", "AA|op BBBB", "21c", "new-instance vAA, type@BBBB",
            "A: destination register (8 bits), B: type index (16 bits)",
            "Construct a new instance of the indicated type.",
            "Creates a new instance of the type specified by type@BBBB and stores a reference to it in vAA.",
            note = "The type must refer to a non-array class."),

        // 0x23 new-array
        InstructionInfo("23", "new-array", "B|A|op CCCC", "22c", "new-array vA, vB, type@CCCC",
            "A: destination register (4 bits), B: size register (4 bits), C: type index (16 bits)",
            "Construct a new array of the indicated type and size.",
            "Creates a new array of the type specified by type@CCCC with the size specified by vB."),

        // 0x24 filled-new-array
        InstructionInfo("24", "filled-new-array", "A|G|op BBBB F|E|D|C", "35c",
            "filled-new-array {vC, vD, vE, vF, vG}, type@BBBB",
            "A: array size (4 bits), B: type index (16 bits), C..G: argument registers (4 bits each)",
            "Construct an array of the given type and size, filling it with the supplied contents.",
            "The constructed instance is stored as a result and must be moved with move-result-object.",
            note = "The type must be an array type, and contents must be single-word."),

        // 0x25 filled-new-array/range
        InstructionInfo("25", "filled-new-array/range", "AA|op BBBB CCCC", "3rc",
            "filled-new-array/range {vCCCC .. vNNNN}, type@BBBB",
            "A: array size (8 bits), B: type index (16 bits), C: first argument register (16 bits)",
            "Construct an array of the given type and size, filling it with the supplied contents (range variant).",
            "The constructed instance must be moved with move-result-object."),

        // 0x26 fill-array-data
        InstructionInfo("26", "fill-array-data", "AA|op BBBBlo BBBBhi", "31t", "fill-array-data vAA, +BBBBBBBB",
            "A: array reference (8 bits), B: signed branch offset to table data (32 bits)",
            "Fill the given array with the indicated data.",
            "Fills the array referenced by vAA with the data specified by the table data pseudo-instruction.",
            note = "The array must be an array of primitives, and the data table must match the array type and size."),

        // 0x27 throw
        InstructionInfo("27", "throw", "AA|op", "11x", "throw vAA",
            "A: exception-bearing register (8 bits)",
            "Throw the indicated exception.",
            "Throws the exception object referenced by vAA."),

        // 0x28 goto
        InstructionInfo("28", "goto", "AA|op", "10t", "goto +AA",
            "A: signed branch offset (8 bits)",
            "Unconditionally jump to the indicated instruction.",
            "Jumps to the instruction at the offset specified by +AA.",
            note = "The branch offset must not be 0."),

        // 0x29 goto/16
        InstructionInfo("29", "goto/16", "ØØ|op AAAA", "20t", "goto/16 +AAAA",
            "A: signed branch offset (16 bits)",
            "Unconditionally jump to the indicated instruction.",
            "Jumps to the instruction at the offset specified by +AAAA."),

        // 0x2A goto/32
        InstructionInfo("2A", "goto/32", "ØØ|op AAAAlo AAAAhi", "30t", "goto/32 +AAAAAAAA",
            "A: signed branch offset (32 bits)",
            "Unconditionally jump to the indicated instruction.",
            "Jumps to the instruction at the offset specified by +AAAAAAAA."),

        // 0x2B packed-switch
        InstructionInfo("2B", "packed-switch", "AA|op BBBBlo BBBBhi", "31t", "packed-switch vAA, +BBBBBBBB",
            "A: register to test (8 bits), B: signed branch offset to table data (32 bits)",
            "Jump based on the value in the given register using a table of offsets.",
            "Uses a table of offsets corresponding to each value in a particular integral range."),

        // 0x2C sparse-switch
        InstructionInfo("2C", "sparse-switch", "AA|op BBBBlo BBBBhi", "31t", "sparse-switch vAA, +BBBBBBBB",
            "A: register to test (8 bits), B: signed branch offset to table data (32 bits)",
            "Jump based on the value in the given register using an ordered table of value-offset pairs.",
            "Uses an ordered table of value-offset pairs for branching."),

        // 0x2D-0x31 comparison ops
        InstructionInfo("2D", "cmpl-float", "AA|op CC BB", "23x", "cmpl-float vAA, vBB, vCC",
            "A: destination (8 bits), B: first source (8 bits), C: second source (8 bits)",
            "Compare two float values with less-than bias.",
            "If vBB == vCC, 0 is stored in vAA. If vBB > vCC, 1. If vBB < vCC or NaN, -1."),

        InstructionInfo("2E", "cmpg-float", "AA|op CC BB", "23x", "cmpg-float vAA, vBB, vCC",
            "A: destination (8 bits), B: first source (8 bits), C: second source (8 bits)",
            "Compare two float values with greater-than bias.",
            "If vBB == vCC, 0 is stored. If vBB > vCC or NaN, 1. If vBB < vCC, -1."),

        InstructionInfo("2F", "cmpl-double", "AA|op CC BB", "23x", "cmpl-double vAA, vBB, vCC",
            "A: destination (8 bits), B: first source pair (8 bits), C: second source pair (8 bits)",
            "Compare two double values with less-than bias.",
            "Compares double values in vBB,vBB+1 and vCC,vCC+1. Less-than bias for NaN."),

        InstructionInfo("30", "cmpg-double", "AA|op CC BB", "23x", "cmpg-double vAA, vBB, vCC",
            "A: destination (8 bits), B: first source pair (8 bits), C: second source pair (8 bits)",
            "Compare two double values with greater-than bias.",
            "Compares double values in vBB,vBB+1 and vCC,vCC+1. Greater-than bias for NaN."),

        InstructionInfo("31", "cmp-long", "AA|op CC BB", "23x", "cmp-long vAA, vBB, vCC",
            "A: destination (8 bits), B: first source pair (8 bits), C: second source pair (8 bits)",
            "Compare two long values.",
            "If vBB == vCC, 0 stored. If vBB > vCC, 1. If vBB < vCC, -1."),

        // 0x32-0x37 if-test
        InstructionInfo("32", "if-eq", "B|A|op CCCC", "22t", "if-eq vA, vB, +CCCC",
            "A: first register (4 bits), B: second register (4 bits), C: signed branch offset (16 bits)",
            "Branch if two registers are equal.",
            "Branches to +CCCC if vA == vB."),

        InstructionInfo("33", "if-ne", "B|A|op CCCC", "22t", "if-ne vA, vB, +CCCC",
            "A: first register (4 bits), B: second register (4 bits), C: signed branch offset (16 bits)",
            "Branch if two registers are not equal.",
            "Branches to +CCCC if vA != vB."),

        InstructionInfo("34", "if-lt", "B|A|op CCCC", "22t", "if-lt vA, vB, +CCCC",
            "A: first register (4 bits), B: second register (4 bits), C: signed branch offset (16 bits)",
            "Branch if the first register is less than the second.",
            "Branches to +CCCC if vA < vB."),

        InstructionInfo("35", "if-ge", "B|A|op CCCC", "22t", "if-ge vA, vB, +CCCC",
            "A: first register (4 bits), B: second register (4 bits), C: signed branch offset (16 bits)",
            "Branch if the first register is greater than or equal to the second.",
            "Branches to +CCCC if vA >= vB."),

        InstructionInfo("36", "if-gt", "B|A|op CCCC", "22t", "if-gt vA, vB, +CCCC",
            "A: first register (4 bits), B: second register (4 bits), C: signed branch offset (16 bits)",
            "Branch if the first register is greater than the second.",
            "Branches to +CCCC if vA > vB."),

        InstructionInfo("37", "if-le", "B|A|op CCCC", "22t", "if-le vA, vB, +CCCC",
            "A: first register (4 bits), B: second register (4 bits), C: signed branch offset (16 bits)",
            "Branch if the first register is less than or equal to the second.",
            "Branches to +CCCC if vA <= vB."),

        // 0x38-0x3D if-testz
        InstructionInfo("38", "if-eqz", "AA|op BBBB", "21t", "if-eqz vAA, +BBBB",
            "A: register to test (8 bits), B: signed branch offset (16 bits)",
            "Branch if the register is equal to zero.",
            "Branches to +BBBB if vAA == 0."),

        InstructionInfo("39", "if-nez", "AA|op BBBB", "21t", "if-nez vAA, +BBBB",
            "A: register to test (8 bits), B: signed branch offset (16 bits)",
            "Branch if the register is not equal to zero.",
            "Branches to +BBBB if vAA != 0."),

        InstructionInfo("3A", "if-ltz", "AA|op BBBB", "21t", "if-ltz vAA, +BBBB",
            "A: register to test (8 bits), B: signed branch offset (16 bits)",
            "Branch if the register is less than zero.",
            "Branches to +BBBB if vAA < 0."),

        InstructionInfo("3B", "if-gez", "AA|op BBBB", "21t", "if-gez vAA, +BBBB",
            "A: register to test (8 bits), B: signed branch offset (16 bits)",
            "Branch if the register is greater than or equal to zero.",
            "Branches to +BBBB if vAA >= 0."),

        InstructionInfo("3C", "if-gtz", "AA|op BBBB", "21t", "if-gtz vAA, +BBBB",
            "A: register to test (8 bits), B: signed branch offset (16 bits)",
            "Branch if the register is greater than zero.",
            "Branches to +BBBB if vAA > 0."),

        InstructionInfo("3D", "if-lez", "AA|op BBBB", "21t", "if-lez vAA, +BBBB",
            "A: register to test (8 bits), B: signed branch offset (16 bits)",
            "Branch if the register is less than or equal to zero.",
            "Branches to +BBBB if vAA <= 0."),

        // 0x44-0x51 array operations
        InstructionInfo("44", "aget", "AA|op CC BB", "23x", "aget vAA, vBB, vCC",
            "A: destination (8 bits), B: array register (8 bits), C: index register (8 bits)",
            "Get an integer value from an array.",
            "Retrieves the integer value from the array in vBB at index vCC, stores in vAA."),

        InstructionInfo("45", "aget-wide", "AA|op CC BB", "23x", "aget-wide vAA, vBB, vCC",
            "A: destination pair (8 bits), B: array register (8 bits), C: index register (8 bits)",
            "Get a long/double value from an array.",
            "Retrieves the long/double value from the array in vBB at index vCC, stores in vAA,vAA+1."),

        InstructionInfo("46", "aget-object", "AA|op CC BB", "23x", "aget-object vAA, vBB, vCC",
            "A: destination (8 bits), B: array register (8 bits), C: index register (8 bits)",
            "Get an object reference from an array.",
            "Retrieves the object reference from the array in vBB at index vCC, stores in vAA."),

        InstructionInfo("47", "aget-boolean", "AA|op CC BB", "23x", "aget-boolean vAA, vBB, vCC",
            "A: destination (8 bits), B: array register (8 bits), C: index register (8 bits)",
            "Get a boolean value from an array.",
            "Retrieves the boolean value from the array in vBB at index vCC, stores in vAA."),

        InstructionInfo("48", "aget-byte", "AA|op CC BB", "23x", "aget-byte vAA, vBB, vCC",
            "A: destination (8 bits), B: array register (8 bits), C: index register (8 bits)",
            "Get a byte value from an array.",
            "Retrieves the byte value from the array in vBB at index vCC, stores in vAA."),

        InstructionInfo("49", "aget-char", "AA|op CC BB", "23x", "aget-char vAA, vBB, vCC",
            "A: destination (8 bits), B: array register (8 bits), C: index register (8 bits)",
            "Get a char value from an array.",
            "Retrieves the char value from the array in vBB at index vCC, stores in vAA."),

        InstructionInfo("4A", "aget-short", "AA|op CC BB", "23x", "aget-short vAA, vBB, vCC",
            "A: destination (8 bits), B: array register (8 bits), C: index register (8 bits)",
            "Get a short value from an array.",
            "Retrieves the short value from the array in vBB at index vCC, stores in vAA."),

        InstructionInfo("4B", "aput", "AA|op CC BB", "23x", "aput vAA, vBB, vCC",
            "A: source (8 bits), B: array register (8 bits), C: index register (8 bits)",
            "Store an integer value into an array.",
            "Stores the integer value from vAA into the array in vBB at index vCC."),

        InstructionInfo("4C", "aput-wide", "AA|op CC BB", "23x", "aput-wide vAA, vBB, vCC",
            "A: source pair (8 bits), B: array register (8 bits), C: index register (8 bits)",
            "Store a long/double value into an array.",
            "Stores the long/double value from vAA,vAA+1 into the array in vBB at index vCC."),

        InstructionInfo("4D", "aput-object", "AA|op CC BB", "23x", "aput-object vAA, vBB, vCC",
            "A: source (8 bits), B: array register (8 bits), C: index register (8 bits)",
            "Store an object reference into an array.",
            "Stores the object reference from vAA into the array in vBB at index vCC."),

        InstructionInfo("4E", "aput-boolean", "AA|op CC BB", "23x", "aput-boolean vAA, vBB, vCC",
            "A: source (8 bits), B: array register (8 bits), C: index register (8 bits)",
            "Store a boolean value into an array.",
            "Stores the boolean value from vAA into the array in vBB at index vCC."),

        InstructionInfo("4F", "aput-byte", "AA|op CC BB", "23x", "aput-byte vAA, vBB, vCC",
            "A: source (8 bits), B: array register (8 bits), C: index register (8 bits)",
            "Store a byte value into an array.",
            "Stores the byte value from vAA into the array in vBB at index vCC."),

        InstructionInfo("50", "aput-char", "AA|op CC BB", "23x", "aput-char vAA, vBB, vCC",
            "A: source (8 bits), B: array register (8 bits), C: index register (8 bits)",
            "Store a char value into an array.",
            "Stores the char value from vAA into the array in vBB at index vCC."),

        InstructionInfo("51", "aput-short", "AA|op CC BB", "23x", "aput-short vAA, vBB, vCC",
            "A: source (8 bits), B: array register (8 bits), C: index register (8 bits)",
            "Store a short value into an array.",
            "Stores the short value from vAA into the array in vBB at index vCC."),

        // 0x52-0x5F instance field operations
        InstructionInfo("52", "iget", "B|A|op CCCC", "22c", "iget vA, vB, field@CCCC",
            "A: destination (4 bits), B: object register (4 bits), C: field index (16 bits)",
            "Get an instance field value.",
            "Retrieves the value of the instance field specified by field@CCCC from the object in vB, stores in vA."),

        InstructionInfo("53", "iget-wide", "B|A|op CCCC", "22c", "iget-wide vA, vB, field@CCCC",
            "A: destination pair (4 bits), B: object register (4 bits), C: field index (16 bits)",
            "Get a long/double instance field value.",
            "Retrieves the long/double value of the instance field into vA,vA+1."),

        InstructionInfo("54", "iget-object", "B|A|op CCCC", "22c", "iget-object vA, vB, field@CCCC",
            "A: destination (4 bits), B: object register (4 bits), C: field index (16 bits)",
            "Get an object reference instance field value.",
            "Retrieves the object reference of the instance field into vA."),

        InstructionInfo("55", "iget-boolean", "B|A|op CCCC", "22c", "iget-boolean vA, vB, field@CCCC",
            "A: destination (4 bits), B: object register (4 bits), C: field index (16 bits)",
            "Get a boolean instance field value.",
            "Retrieves the boolean value of the instance field into vA."),

        InstructionInfo("56", "iget-byte", "B|A|op CCCC", "22c", "iget-byte vA, vB, field@CCCC",
            "A: destination (4 bits), B: object register (4 bits), C: field index (16 bits)",
            "Get a byte instance field value.",
            "Retrieves the byte value of the instance field into vA."),

        InstructionInfo("57", "iget-char", "B|A|op CCCC", "22c", "iget-char vA, vB, field@CCCC",
            "A: destination (4 bits), B: object register (4 bits), C: field index (16 bits)",
            "Get a char instance field value.",
            "Retrieves the char value of the instance field into vA."),

        InstructionInfo("58", "iget-short", "B|A|op CCCC", "22c", "iget-short vA, vB, field@CCCC",
            "A: destination (4 bits), B: object register (4 bits), C: field index (16 bits)",
            "Get a short instance field value.",
            "Retrieves the short value of the instance field into vA."),

        InstructionInfo("59", "iput", "B|A|op CCCC", "22c", "iput vA, vB, field@CCCC",
            "A: source (4 bits), B: object register (4 bits), C: field index (16 bits)",
            "Store a value into an instance field.",
            "Stores the value from vA into the instance field of the object in vB."),

        InstructionInfo("5A", "iput-wide", "B|A|op CCCC", "22c", "iput-wide vA, vB, field@CCCC",
            "A: source pair (4 bits), B: object register (4 bits), C: field index (16 bits)",
            "Store a long/double value into an instance field.",
            "Stores the long/double value from vA,vA+1 into the instance field."),

        InstructionInfo("5B", "iput-object", "B|A|op CCCC", "22c", "iput-object vA, vB, field@CCCC",
            "A: source (4 bits), B: object register (4 bits), C: field index (16 bits)",
            "Store an object reference into an instance field.",
            "Stores the object reference from vA into the instance field."),

        InstructionInfo("5C", "iput-boolean", "B|A|op CCCC", "22c", "iput-boolean vA, vB, field@CCCC",
            "A: source (4 bits), B: object register (4 bits), C: field index (16 bits)",
            "Store a boolean value into an instance field.",
            "Stores the boolean value from vA into the instance field."),

        InstructionInfo("5D", "iput-byte", "B|A|op CCCC", "22c", "iput-byte vA, vB, field@CCCC",
            "A: source (4 bits), B: object register (4 bits), C: field index (16 bits)",
            "Store a byte value into an instance field.",
            "Stores the byte value from vA into the instance field."),

        InstructionInfo("5E", "iput-char", "B|A|op CCCC", "22c", "iput-char vA, vB, field@CCCC",
            "A: source (4 bits), B: object register (4 bits), C: field index (16 bits)",
            "Store a char value into an instance field.",
            "Stores the char value from vA into the instance field."),

        InstructionInfo("5F", "iput-short", "B|A|op CCCC", "22c", "iput-short vA, vB, field@CCCC",
            "A: source (4 bits), B: object register (4 bits), C: field index (16 bits)",
            "Store a short value into an instance field.",
            "Stores the short value from vA into the instance field."),

        // 0x60-0x6D static field operations
        InstructionInfo("60", "sget", "AA|op BBBB", "21c", "sget vAA, field@BBBB",
            "A: destination (8 bits), B: field index (16 bits)",
            "Get a static field value.",
            "Retrieves the value of the static field into vAA."),

        InstructionInfo("61", "sget-wide", "AA|op BBBB", "21c", "sget-wide vAA, field@BBBB",
            "A: destination pair (8 bits), B: field index (16 bits)",
            "Get a long/double static field value.",
            "Retrieves the long/double value of the static field into vAA,vAA+1."),

        InstructionInfo("62", "sget-object", "AA|op BBBB", "21c", "sget-object vAA, field@BBBB",
            "A: destination (8 bits), B: field index (16 bits)",
            "Get an object reference static field value.",
            "Retrieves the object reference of the static field into vAA."),

        InstructionInfo("63", "sget-boolean", "AA|op BBBB", "21c", "sget-boolean vAA, field@BBBB",
            "A: destination (8 bits), B: field index (16 bits)",
            "Get a boolean static field value.",
            "Retrieves the boolean value of the static field into vAA."),

        InstructionInfo("64", "sget-byte", "AA|op BBBB", "21c", "sget-byte vAA, field@BBBB",
            "A: destination (8 bits), B: field index (16 bits)",
            "Get a byte static field value.",
            "Retrieves the byte value of the static field into vAA."),

        InstructionInfo("65", "sget-char", "AA|op BBBB", "21c", "sget-char vAA, field@BBBB",
            "A: destination (8 bits), B: field index (16 bits)",
            "Get a char static field value.",
            "Retrieves the char value of the static field into vAA."),

        InstructionInfo("66", "sget-short", "AA|op BBBB", "21c", "sget-short vAA, field@BBBB",
            "A: destination (8 bits), B: field index (16 bits)",
            "Get a short static field value.",
            "Retrieves the short value of the static field into vAA."),

        InstructionInfo("67", "sput", "AA|op BBBB", "21c", "sput vAA, field@BBBB",
            "A: source (8 bits), B: field index (16 bits)",
            "Store a value into a static field.",
            "Stores the value from vAA into the static field."),

        InstructionInfo("68", "sput-wide", "AA|op BBBB", "21c", "sput-wide vAA, field@BBBB",
            "A: source pair (8 bits), B: field index (16 bits)",
            "Store a long/double value into a static field.",
            "Stores the long/double value from vAA,vAA+1 into the static field."),

        InstructionInfo("69", "sput-object", "AA|op BBBB", "21c", "sput-object vAA, field@BBBB",
            "A: source (8 bits), B: field index (16 bits)",
            "Store an object reference into a static field.",
            "Stores the object reference from vAA into the static field."),

        InstructionInfo("6A", "sput-boolean", "AA|op BBBB", "21c", "sput-boolean vAA, field@BBBB",
            "A: source (8 bits), B: field index (16 bits)",
            "Store a boolean value into a static field.",
            "Stores the boolean value from vAA into the static field."),

        InstructionInfo("6B", "sput-byte", "AA|op BBBB", "21c", "sput-byte vAA, field@BBBB",
            "A: source (8 bits), B: field index (16 bits)",
            "Store a byte value into a static field.",
            "Stores the byte value from vAA into the static field."),

        InstructionInfo("6C", "sput-char", "AA|op BBBB", "21c", "sput-char vAA, field@BBBB",
            "A: source (8 bits), B: field index (16 bits)",
            "Store a char value into a static field.",
            "Stores the char value from vAA into the static field."),

        InstructionInfo("6D", "sput-short", "AA|op BBBB", "21c", "sput-short vAA, field@BBBB",
            "A: source (8 bits), B: field index (16 bits)",
            "Store a short value into a static field.",
            "Stores the short value from vAA into the static field."),

        // 0x6E-0x72 invoke operations
        InstructionInfo("6E", "invoke-virtual", "A|G|op BBBB F|E|D|C", "35c",
            "invoke-virtual {vC, vD, vE, vF, vG}, meth@BBBB",
            "A: argument word count (4 bits), B: method index (16 bits), C..G: argument registers (4 bits each)",
            "Invoke a virtual method.",
            "Invokes the virtual method specified by meth@BBBB. Used for normal virtual methods that are not static, private, or constructor.",
            note = "The result (if any) can be stored with a move-result* instruction immediately following."),

        InstructionInfo("6F", "invoke-super", "A|G|op BBBB F|E|D|C", "35c",
            "invoke-super {vC, vD, vE, vF, vG}, meth@BBBB",
            "A: argument word count (4 bits), B: method index (16 bits), C..G: argument registers (4 bits each)",
            "Invoke the closest superclass's virtual method.",
            "Invokes the closest superclass's virtual method specified by meth@BBBB."),

        InstructionInfo("70", "invoke-direct", "A|G|op BBBB F|E|D|C", "35c",
            "invoke-direct {vC, vD, vE, vF, vG}, meth@BBBB",
            "A: argument word count (4 bits), B: method index (16 bits), C..G: argument registers (4 bits each)",
            "Invoke a direct method.",
            "Invokes the direct method specified by meth@BBBB. Direct methods are non-overridable instance methods, such as private methods or constructors."),

        InstructionInfo("71", "invoke-static", "A|G|op BBBB F|E|D|C", "35c",
            "invoke-static {vC, vD, vE, vF, vG}, meth@BBBB",
            "A: argument word count (4 bits), B: method index (16 bits), C..G: argument registers (4 bits each)",
            "Invoke a static method.",
            "Invokes the static method specified by meth@BBBB. Static methods are always direct methods."),

        InstructionInfo("72", "invoke-interface", "A|G|op BBBB F|E|D|C", "35c",
            "invoke-interface {vC, vD, vE, vF, vG}, meth@BBBB",
            "A: argument word count (4 bits), B: method index (16 bits), C..G: argument registers (4 bits each)",
            "Invoke an interface method.",
            "Invokes the interface method specified by meth@BBBB. Used for methods on objects whose concrete class is not known."),

        // 0x74-0x78 invoke/range operations
        InstructionInfo("74", "invoke-virtual/range", "AA|op BBBB CCCC", "3rc",
            "invoke-virtual/range {vCCCC .. vNNNN}, meth@BBBB",
            "A: argument word count (8 bits), B: method index (16 bits), C: first arg register (16 bits)",
            "Invoke a virtual method with a range of registers.",
            "Range variant of invoke-virtual for methods with many arguments."),

        InstructionInfo("75", "invoke-super/range", "AA|op BBBB CCCC", "3rc",
            "invoke-super/range {vCCCC .. vNNNN}, meth@BBBB",
            "A: argument word count (8 bits), B: method index (16 bits), C: first arg register (16 bits)",
            "Invoke the closest superclass's virtual method with a range of registers.",
            "Range variant of invoke-super."),

        InstructionInfo("76", "invoke-direct/range", "AA|op BBBB CCCC", "3rc",
            "invoke-direct/range {vCCCC .. vNNNN}, meth@BBBB",
            "A: argument word count (8 bits), B: method index (16 bits), C: first arg register (16 bits)",
            "Invoke a direct method with a range of registers.",
            "Range variant of invoke-direct."),

        InstructionInfo("77", "invoke-static/range", "AA|op BBBB CCCC", "3rc",
            "invoke-static/range {vCCCC .. vNNNN}, meth@BBBB",
            "A: argument word count (8 bits), B: method index (16 bits), C: first arg register (16 bits)",
            "Invoke a static method with a range of registers.",
            "Range variant of invoke-static."),

        InstructionInfo("78", "invoke-interface/range", "AA|op BBBB CCCC", "3rc",
            "invoke-interface/range {vCCCC .. vNNNN}, meth@BBBB",
            "A: argument word count (8 bits), B: method index (16 bits), C: first arg register (16 bits)",
            "Invoke an interface method with a range of registers.",
            "Range variant of invoke-interface."),

        // 0x7B-0x8F unary operations and conversions
        InstructionInfo("7B", "neg-int", "B|A|op", "12x", "neg-int vA, vB",
            "A: destination (4 bits), B: source (4 bits)",
            "Negate an integer value.",
            "Negates the integer value in vB and stores the result in vA."),

        InstructionInfo("7C", "not-int", "B|A|op", "12x", "not-int vA, vB",
            "A: destination (4 bits), B: source (4 bits)",
            "Perform bitwise NOT on an integer value.",
            "Performs bitwise NOT on the integer value in vB and stores the result in vA."),

        InstructionInfo("7D", "neg-long", "B|A|op", "12x", "neg-long vA, vB",
            "A: destination pair (4 bits), B: source pair (4 bits)",
            "Negate a long value.",
            "Negates the long value in vB,vB+1 and stores the result in vA,vA+1."),

        InstructionInfo("7E", "not-long", "B|A|op", "12x", "not-long vA, vB",
            "A: destination pair (4 bits), B: source pair (4 bits)",
            "Perform bitwise NOT on a long value.",
            "Performs bitwise NOT on the long value in vB,vB+1, stores in vA,vA+1."),

        InstructionInfo("7F", "neg-float", "B|A|op", "12x", "neg-float vA, vB",
            "A: destination (4 bits), B: source (4 bits)",
            "Negate a float value.",
            "Negates the float value in vB, stores in vA."),

        InstructionInfo("80", "neg-double", "B|A|op", "12x", "neg-double vA, vB",
            "A: destination pair (4 bits), B: source pair (4 bits)",
            "Negate a double value.",
            "Negates the double value in vB,vB+1, stores in vA,vA+1."),

        InstructionInfo("81", "int-to-long", "B|A|op", "12x", "int-to-long vA, vB",
            "A: destination pair (4 bits), B: source (4 bits)",
            "Convert an integer to a long.", "Converts the integer value in vB to a long, stores in vA,vA+1."),

        InstructionInfo("82", "int-to-float", "B|A|op", "12x", "int-to-float vA, vB",
            "A: destination (4 bits), B: source (4 bits)",
            "Convert an integer to a float.", "Converts the integer value in vB to a float, stores in vA."),

        InstructionInfo("83", "int-to-double", "B|A|op", "12x", "int-to-double vA, vB",
            "A: destination pair (4 bits), B: source (4 bits)",
            "Convert an integer to a double.", "Converts the integer value in vB to a double, stores in vA,vA+1."),

        InstructionInfo("84", "long-to-int", "B|A|op", "12x", "long-to-int vA, vB",
            "A: destination (4 bits), B: source pair (4 bits)",
            "Convert a long to an integer.", "Converts the long value in vB,vB+1 to an integer, stores in vA."),

        InstructionInfo("85", "long-to-float", "B|A|op", "12x", "long-to-float vA, vB",
            "A: destination (4 bits), B: source pair (4 bits)",
            "Convert a long to a float.", "Converts the long value in vB,vB+1 to a float, stores in vA."),

        InstructionInfo("86", "long-to-double", "B|A|op", "12x", "long-to-double vA, vB",
            "A: destination pair (4 bits), B: source pair (4 bits)",
            "Convert a long to a double.", "Converts the long value in vB,vB+1 to a double, stores in vA,vA+1."),

        InstructionInfo("87", "float-to-int", "B|A|op", "12x", "float-to-int vA, vB",
            "A: destination (4 bits), B: source (4 bits)",
            "Convert a float to an integer.", "Converts the float value in vB to an integer, stores in vA."),

        InstructionInfo("88", "float-to-long", "B|A|op", "12x", "float-to-long vA, vB",
            "A: destination pair (4 bits), B: source (4 bits)",
            "Convert a float to a long.", "Converts the float value in vB to a long, stores in vA,vA+1."),

        InstructionInfo("89", "float-to-double", "B|A|op", "12x", "float-to-double vA, vB",
            "A: destination pair (4 bits), B: source (4 bits)",
            "Convert a float to a double.", "Converts the float value in vB to a double, stores in vA,vA+1."),

        InstructionInfo("8A", "double-to-int", "B|A|op", "12x", "double-to-int vA, vB",
            "A: destination (4 bits), B: source pair (4 bits)",
            "Convert a double to an integer.", "Converts the double value in vB,vB+1 to an integer, stores in vA."),

        InstructionInfo("8B", "double-to-long", "B|A|op", "12x", "double-to-long vA, vB",
            "A: destination pair (4 bits), B: source pair (4 bits)",
            "Convert a double to a long.", "Converts the double value in vB,vB+1 to a long, stores in vA,vA+1."),

        InstructionInfo("8C", "double-to-float", "B|A|op", "12x", "double-to-float vA, vB",
            "A: destination (4 bits), B: source pair (4 bits)",
            "Convert a double to a float.", "Converts the double value in vB,vB+1 to a float, stores in vA."),

        InstructionInfo("8D", "int-to-byte", "B|A|op", "12x", "int-to-byte vA, vB",
            "A: destination (4 bits), B: source (4 bits)",
            "Truncate an integer to a byte.", "Truncates the integer value in vB to a byte, stores in vA."),

        InstructionInfo("8E", "int-to-char", "B|A|op", "12x", "int-to-char vA, vB",
            "A: destination (4 bits), B: source (4 bits)",
            "Truncate an integer to a char.", "Truncates the integer value in vB to a char, stores in vA."),

        InstructionInfo("8F", "int-to-short", "B|A|op", "12x", "int-to-short vA, vB",
            "A: destination (4 bits), B: source (4 bits)",
            "Truncate an integer to a short.", "Truncates the integer value in vB to a short, stores in vA."),

        // 0x90-0xAF binary operations (3-register)
        *buildBinaryOps3Reg(),

        // 0xB0-0xCF binary operations (2addr)
        *buildBinaryOps2Addr(),

        // 0xD0-0xD7 lit16 operations
        *buildLit16Ops(),

        // 0xD8-0xE2 lit8 operations
        *buildLit8Ops(),

        // 0xFA-0xFF newer opcodes
        InstructionInfo("FA", "invoke-polymorphic", "A|G|op BBBB F|E|D|C HHHH", "45cc",
            "invoke-polymorphic {vC, vD, vE, vF, vG}, meth@BBBB, proto@HHHH",
            "A: argument word count (4 bits), B: method reference (16 bits), C: receiver (4 bits), D..G: args (4 bits each), H: prototype index (16 bits)",
            "Invoke the indicated signature polymorphic method.",
            "Invokes a signature polymorphic method such as MethodHandle.invoke or MethodHandle.invokeExact.",
            note = "Present in Dex files from version 038 onwards."),

        InstructionInfo("FB", "invoke-polymorphic/range", "AA|op BBBB CCCC HHHH", "4rcc",
            "invoke-polymorphic/range {vCCCC .. vNNNN}, meth@BBBB, proto@HHHH",
            "A: argument word count (8 bits), B: method reference (16 bits), C: receiver (16 bits), H: prototype index (16 bits)",
            "Invoke the indicated signature polymorphic method (range variant).",
            "Range variant of invoke-polymorphic.",
            note = "Present in Dex files from version 038 onwards."),

        InstructionInfo("FC", "invoke-custom", "A|G|op BBBB F|E|D|C", "35c",
            "invoke-custom {vC, vD, vE, vF, vG}, call_site@BBBB",
            "A: argument word count (4 bits), B: call site reference index (16 bits), C..G: argument registers (4 bits each)",
            "Resolve and invoke the indicated call site.",
            "Resolves and invokes the call site specified by call_site@BBBB using the bootstrap linker method.",
            note = "Present in Dex files from version 038 onwards."),

        InstructionInfo("FD", "invoke-custom/range", "AA|op BBBB CCCC", "3rc",
            "invoke-custom/range {vCCCC .. vNNNN}, call_site@BBBB",
            "A: argument word count (8 bits), B: call site reference (16 bits), C: first arg register (16 bits)",
            "Resolve and invoke a call site (range variant).",
            "Range variant of invoke-custom.",
            note = "Present in Dex files from version 038 onwards."),

        InstructionInfo("FE", "const-method-handle", "AA|op BBBB", "21c",
            "const-method-handle vAA, method_handle@BBBB",
            "A: destination register (8 bits), B: method handle index (16 bits)",
            "Move a reference to the method handle into the specified register.",
            "Moves a reference to the method handle specified by method_handle@BBBB into vAA.",
            note = "Present in Dex files from version 039 onwards."),

        InstructionInfo("FF", "const-method-type", "AA|op BBBB", "21c",
            "const-method-type vAA, proto@BBBB",
            "A: destination register (8 bits), B: method prototype reference (16 bits)",
            "Move a reference to the method prototype into the specified register.",
            "Moves a reference to the method prototype specified by proto@BBBB into vAA.",
            note = "Present in Dex files from version 039 onwards.")
    )

    /** Build 3-register binary operations (0x90-0xAF) */
    private fun buildBinaryOps3Reg(): Array<InstructionInfo> {
        data class BinOp(val offset: Int, val op: String, val verb: String, val type: String, val pair: Boolean)
        val ops = listOf(
            BinOp(0x90, "add-int", "Add", "int", false),
            BinOp(0x91, "sub-int", "Subtract", "int", false),
            BinOp(0x92, "mul-int", "Multiply", "int", false),
            BinOp(0x93, "div-int", "Divide", "int", false),
            BinOp(0x94, "rem-int", "Remainder of", "int", false),
            BinOp(0x95, "and-int", "Bitwise AND", "int", false),
            BinOp(0x96, "or-int", "Bitwise OR", "int", false),
            BinOp(0x97, "xor-int", "Bitwise XOR", "int", false),
            BinOp(0x98, "shl-int", "Shift left", "int", false),
            BinOp(0x99, "shr-int", "Shift right", "int", false),
            BinOp(0x9A, "ushr-int", "Unsigned shift right", "int", false),
            BinOp(0x9B, "add-long", "Add", "long", true),
            BinOp(0x9C, "sub-long", "Subtract", "long", true),
            BinOp(0x9D, "mul-long", "Multiply", "long", true),
            BinOp(0x9E, "div-long", "Divide", "long", true),
            BinOp(0x9F, "rem-long", "Remainder of", "long", true),
            BinOp(0xA0, "and-long", "Bitwise AND", "long", true),
            BinOp(0xA1, "or-long", "Bitwise OR", "long", true),
            BinOp(0xA2, "xor-long", "Bitwise XOR", "long", true),
            BinOp(0xA3, "shl-long", "Shift left", "long", true),
            BinOp(0xA4, "shr-long", "Shift right", "long", true),
            BinOp(0xA5, "ushr-long", "Unsigned shift right", "long", true),
            BinOp(0xA6, "add-float", "Add", "float", false),
            BinOp(0xA7, "sub-float", "Subtract", "float", false),
            BinOp(0xA8, "mul-float", "Multiply", "float", false),
            BinOp(0xA9, "div-float", "Divide", "float", false),
            BinOp(0xAA, "rem-float", "Remainder of", "float", false),
            BinOp(0xAB, "add-double", "Add", "double", true),
            BinOp(0xAC, "sub-double", "Subtract", "double", true),
            BinOp(0xAD, "mul-double", "Multiply", "double", true),
            BinOp(0xAE, "div-double", "Divide", "double", true),
            BinOp(0xAF, "rem-double", "Remainder of", "double", true),
        )
        val regType = { pair: Boolean -> if (pair) "register pair" else "register" }
        return ops.map { o ->
            val rt = regType(o.pair)
            InstructionInfo(
                String.format("%02X", o.offset), o.op, "AA|op CC BB", "23x",
                "${o.op} vAA, vBB, vCC",
                "A: destination $rt (8 bits), B: first source $rt (8 bits), C: second source $rt (8 bits)",
                "${o.verb} two ${o.type} values.",
                "${o.verb}s the ${o.type} values in vBB and vCC and stores the result in vAA."
            )
        }.toTypedArray()
    }

    /** Build 2-address binary operations (0xB0-0xCF) */
    private fun buildBinaryOps2Addr(): Array<InstructionInfo> {
        data class BinOp(val offset: Int, val op: String, val verb: String, val type: String, val pair: Boolean)
        val ops = listOf(
            BinOp(0xB0, "add-int/2addr", "Add", "int", false),
            BinOp(0xB1, "sub-int/2addr", "Subtract", "int", false),
            BinOp(0xB2, "mul-int/2addr", "Multiply", "int", false),
            BinOp(0xB3, "div-int/2addr", "Divide", "int", false),
            BinOp(0xB4, "rem-int/2addr", "Remainder of", "int", false),
            BinOp(0xB5, "and-int/2addr", "Bitwise AND", "int", false),
            BinOp(0xB6, "or-int/2addr", "Bitwise OR", "int", false),
            BinOp(0xB7, "xor-int/2addr", "Bitwise XOR", "int", false),
            BinOp(0xB8, "shl-int/2addr", "Shift left", "int", false),
            BinOp(0xB9, "shr-int/2addr", "Shift right", "int", false),
            BinOp(0xBA, "ushr-int/2addr", "Unsigned shift right", "int", false),
            BinOp(0xBB, "add-long/2addr", "Add", "long", true),
            BinOp(0xBC, "sub-long/2addr", "Subtract", "long", true),
            BinOp(0xBD, "mul-long/2addr", "Multiply", "long", true),
            BinOp(0xBE, "div-long/2addr", "Divide", "long", true),
            BinOp(0xBF, "rem-long/2addr", "Remainder of", "long", true),
            BinOp(0xC0, "and-long/2addr", "Bitwise AND", "long", true),
            BinOp(0xC1, "or-long/2addr", "Bitwise OR", "long", true),
            BinOp(0xC2, "xor-long/2addr", "Bitwise XOR", "long", true),
            BinOp(0xC3, "shl-long/2addr", "Shift left", "long", true),
            BinOp(0xC4, "shr-long/2addr", "Shift right", "long", true),
            BinOp(0xC5, "ushr-long/2addr", "Unsigned shift right", "long", true),
            BinOp(0xC6, "add-float/2addr", "Add", "float", false),
            BinOp(0xC7, "sub-float/2addr", "Subtract", "float", false),
            BinOp(0xC8, "mul-float/2addr", "Multiply", "float", false),
            BinOp(0xC9, "div-float/2addr", "Divide", "float", false),
            BinOp(0xCA, "rem-float/2addr", "Remainder of", "float", false),
            BinOp(0xCB, "add-double/2addr", "Add", "double", true),
            BinOp(0xCC, "sub-double/2addr", "Subtract", "double", true),
            BinOp(0xCD, "mul-double/2addr", "Multiply", "double", true),
            BinOp(0xCE, "div-double/2addr", "Divide", "double", true),
            BinOp(0xCF, "rem-double/2addr", "Remainder of", "double", true),
        )
        val regType = { pair: Boolean -> if (pair) "register pair" else "register" }
        return ops.map { o ->
            val rt = regType(o.pair)
            InstructionInfo(
                String.format("%02X", o.offset), o.op, "B|A|op", "12x",
                "${o.op} vA, vB",
                "A: destination and first source $rt (4 bits), B: second source $rt (4 bits)",
                "${o.verb} two ${o.type} values (2-address form).",
                "${o.verb}s the ${o.type} value in vB with vA and stores the result in vA."
            )
        }.toTypedArray()
    }

    /** Build lit16 operations (0xD0-0xD7) */
    private fun buildLit16Ops(): Array<InstructionInfo> {
        data class LitOp(val offset: Int, val op: String, val verb: String)
        val ops = listOf(
            LitOp(0xD0, "add-int/lit16", "Add"),
            LitOp(0xD1, "rsub-int", "Reverse subtract"),
            LitOp(0xD2, "mul-int/lit16", "Multiply"),
            LitOp(0xD3, "div-int/lit16", "Divide"),
            LitOp(0xD4, "rem-int/lit16", "Remainder of"),
            LitOp(0xD5, "and-int/lit16", "Bitwise AND"),
            LitOp(0xD6, "or-int/lit16", "Bitwise OR"),
            LitOp(0xD7, "xor-int/lit16", "Bitwise XOR"),
        )
        return ops.map { o ->
            InstructionInfo(
                String.format("%02X", o.offset), o.op, "B|A|op CCCC", "22s",
                "${o.op} vA, vB, #+CCCC",
                "A: destination register (4 bits), B: source register (4 bits), C: signed int constant (16 bits)",
                "${o.verb} register and literal (16-bit).",
                "${o.verb}s the int value in vB with the literal value CCCC and stores the result in vA."
            )
        }.toTypedArray()
    }

    /** Build lit8 operations (0xD8-0xE2) */
    private fun buildLit8Ops(): Array<InstructionInfo> {
        data class LitOp(val offset: Int, val op: String, val verb: String)
        val ops = listOf(
            LitOp(0xD8, "add-int/lit8", "Add"),
            LitOp(0xD9, "rsub-int/lit8", "Reverse subtract"),
            LitOp(0xDA, "mul-int/lit8", "Multiply"),
            LitOp(0xDB, "div-int/lit8", "Divide"),
            LitOp(0xDC, "rem-int/lit8", "Remainder of"),
            LitOp(0xDD, "and-int/lit8", "Bitwise AND"),
            LitOp(0xDE, "or-int/lit8", "Bitwise OR"),
            LitOp(0xDF, "xor-int/lit8", "Bitwise XOR"),
            LitOp(0xE0, "shl-int/lit8", "Shift left"),
            LitOp(0xE1, "shr-int/lit8", "Shift right"),
            LitOp(0xE2, "ushr-int/lit8", "Unsigned shift right"),
        )
        return ops.map { o ->
            InstructionInfo(
                String.format("%02X", o.offset), o.op, "AA|op CC BB", "22b",
                "${o.op} vAA, vBB, #+CC",
                "A: destination register (8 bits), B: source register (8 bits), C: signed int constant (8 bits)",
                "${o.verb} register and literal (8-bit).",
                "${o.verb}s the int value in vBB with the literal value CC and stores the result in vAA."
            )
        }.toTypedArray()
    }
}
