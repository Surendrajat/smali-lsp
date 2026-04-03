package xyz.surendrajat.smalilsp.parser

import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.parser.generated.SmaliParserBaseListener
import xyz.surendrajat.smalilsp.parser.generated.SmaliParser as GeneratedSmaliParser
import xyz.surendrajat.smalilsp.util.DescriptorParser
import xyz.surendrajat.smalilsp.util.StringPool

/**
 * Builds our clean data structures from the ANTLR parse tree.
 * 
 * Design: Keep it simple. Extract the raw text and parse the Smali-specific
 * formats ourselves. This is more reliable than trying to navigate the complex
 * ANTLR tree structure.
 */
class ASTBuilder(
    private val uri: String,
    private val tokens: CommonTokenStream
) : SmaliParserBaseListener() {
    
    // Accumulated data
    private var className: String? = null
    private var classRange: Range? = null
    private val classModifiers = mutableSetOf<String>()
    private var superClass: String? = null
    private var superClassRange: Range? = null
    private val interfaces = mutableListOf<String>()
    private val interfaceRanges = mutableMapOf<String, Range>()
    private val methods = mutableListOf<MethodDefinition>()
    private val fields = mutableListOf<FieldDefinition>()
    
    // Track current method for instruction parsing
    private var currentMethod: MethodDefinition? = null
    private val currentInstructions = mutableListOf<Instruction>()
    private val currentLabels = mutableMapOf<String, LabelDefinition>()
    
    override fun enterClassDirective(ctx: GeneratedSmaliParser.ClassDirectiveContext) {
        // Extract class name (intern for memory efficiency)
        className = ctx.className()?.text?.let { StringPool.intern(it) }
        
        // Extract modifiers (intern common ones: public, private, static, final, etc.)
        ctx.classModifier().forEach { mod ->
            classModifiers.add(StringPool.intern(mod.text))
        }
        
        // Create range for class definition
        classRange = ctx.toRange()
    }
    
    override fun enterSuperDirective(ctx: GeneratedSmaliParser.SuperDirectiveContext) {
        superClass = ctx.superName()?.text?.let { StringPool.intern(it) }
        superClassRange = ctx.toRange()  // Capture .super directive range
    }
    
    override fun enterImplementsDirective(ctx: GeneratedSmaliParser.ImplementsDirectiveContext) {
        ctx.referenceType()?.text?.let { interfaceName ->
            val internedName = StringPool.intern(interfaceName)
            interfaces.add(internedName)
            interfaceRanges[internedName] = ctx.toRange()  // Capture .implements directive range
        }
    }
    
    override fun enterFieldDirective(ctx: GeneratedSmaliParser.FieldDirectiveContext) {
        // Parse field name and type from fieldNameAndType
        val fieldNameAndType = ctx.fieldNameAndType()?.text ?: return
        
        // Format is: name:type (e.g., myField:I)
        val parts = fieldNameAndType.split(":", limit = 2)
        if (parts.size != 2) return
        
        val fieldName = StringPool.intern(parts[0])
        val fieldType = StringPool.intern(parts[1])  // Many duplicates: I, Z, V, Ljava/lang/String;
        
        // Extract modifiers (intern common ones)
        val fieldModifiers = ctx.fieldModifier().map { StringPool.intern(it.text) }.toSet()
        
        // Create field definition
        fields.add(FieldDefinition(
            name = fieldName,
            type = fieldType,
            range = ctx.toRange(),
            modifiers = fieldModifiers
        ))
    }
    
    override fun enterMethodDirective(ctx: GeneratedSmaliParser.MethodDirectiveContext) {
        val methodDecl = ctx.methodDeclaration() ?: return
        
        // Get the entire method signature text
        val methodSig = methodDecl.methodSignature()
        val sigText = methodSig?.text ?: return
        
        // Parse method name and descriptor from signature text
        // Format: methodName(params)returnType
        // e.g., <init>()V or getPath(FFFF)Landroid/graphics/Path;
        
        val openParen = sigText.indexOf('(')
        if (openParen <= 0) return
        
        val methodName = StringPool.intern(sigText.substring(0, openParen))
        val closeParen = sigText.indexOf(')', openParen)
        if (closeParen < 0) return
        
        val params = sigText.substring(openParen + 1, closeParen)
        val returnType = StringPool.intern(sigText.substring(closeParen + 1))  // Many duplicates: V, I, Z
        
        // Parse parameters - simplified: just split on type descriptors
        val parameters = parseParameters(params)
        
        // Extract modifiers (intern common ones)
        val modifiers = methodDecl.methodModifier().map { StringPool.intern(it.text) }.toSet()
        
        // Create method definition (instructions will be added in exitMethodDirective)
        currentMethod = MethodDefinition(
            name = methodName,
            descriptor = StringPool.intern("($params)$returnType"),
            range = ctx.toRange(),
            modifiers = modifiers,
            parameters = parameters,
            returnType = returnType,
            instructions = emptyList(),  // Will be populated when we exit
            labels = emptyMap()  // Will be populated when we exit
        )
        currentInstructions.clear()
        currentLabels.clear()
    }
    
    override fun exitMethodDirective(ctx: GeneratedSmaliParser.MethodDirectiveContext) {
        // Finalize the method with collected instructions and labels
        currentMethod?.let { method ->
            methods.add(method.copy(
                instructions = currentInstructions.toList(),
                labels = currentLabels.toMap()
            ))
        }
        currentMethod = null
        currentInstructions.clear()
        currentLabels.clear()
    }
    
    /**
     * Parse parameter types from descriptor string.
     * Delegates to DescriptorParser for the core parsing logic,
     * then interns strings via StringPool for memory efficiency.
     */
    private fun parseParameters(paramStr: String): List<Parameter> {
        if (paramStr.isEmpty()) return emptyList()
        return DescriptorParser.parseTypeSequence(paramStr).map { span ->
            Parameter(StringPool.intern(span.type), null)
        }
    }
    
    /**
     * Build the final SmaliFile after tree walking completes.
     */
    fun build(): SmaliFile? {
        if (className == null || classRange == null) {
            return null
        }
        
        return SmaliFile(
            uri = uri,
            classDefinition = ClassDefinition(
                name = className!!,
                range = classRange!!,
                modifiers = classModifiers.toSet(),
                superClass = superClass,
                interfaces = interfaces.toList(),
                superClassRange = superClassRange,
                interfaceRanges = interfaceRanges.toMap()
            ),
            methods = methods.toList(),
            fields = fields.toList()
        )
    }
    
    /**
     * Convert ANTLR context to our Range type.
     */
    private fun ParserRuleContext.toRange(): Range {
        val startToken = this.start
        val stopToken = this.stop ?: startToken
        
        return range(
            startToken.line - 1,  // LSP is 0-based
            startToken.charPositionInLine,
            stopToken.line - 1,
            stopToken.charPositionInLine + stopToken.text.length
        )
    }
    
    // ========== Instruction Parsing ==========
    
    /**
     * Parse invoke-virtual instruction
     */
    override fun enterInvokeVirtualInstruction(ctx: GeneratedSmaliParser.InvokeVirtualInstructionContext) {
        parseInvokeInstruction("invoke-virtual", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    override fun enterInvokeVirtualRangeInstruction(ctx: GeneratedSmaliParser.InvokeVirtualRangeInstructionContext) {
        parseInvokeInstruction("invoke-virtual/range", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    /**
     * Parse invoke-super instruction
     */
    override fun enterInvokeSuperInstruction(ctx: GeneratedSmaliParser.InvokeSuperInstructionContext) {
        parseInvokeInstruction("invoke-super", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    override fun enterInvokeSuperRangeInstruction(ctx: GeneratedSmaliParser.InvokeSuperRangeInstructionContext) {
        parseInvokeInstruction("invoke-super/range", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    /**
     * Parse invoke-direct instruction
     */
    override fun enterInvokeDirectInstruction(ctx: GeneratedSmaliParser.InvokeDirectInstructionContext) {
        parseInvokeInstruction("invoke-direct", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    override fun enterInvokeDirectRangeInstruction(ctx: GeneratedSmaliParser.InvokeDirectRangeInstructionContext) {
        parseInvokeInstruction("invoke-direct/range", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    /**
     * Parse invoke-static instruction
     */
    override fun enterInvokeStaticInstruction(ctx: GeneratedSmaliParser.InvokeStaticInstructionContext) {
        parseInvokeInstruction("invoke-static", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    override fun enterInvokeStaticRangeInstruction(ctx: GeneratedSmaliParser.InvokeStaticRangeInstructionContext) {
        parseInvokeInstruction("invoke-static/range", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    /**
     * Parse invoke-interface instruction
     */
    override fun enterInvokeInterfaceInstruction(ctx: GeneratedSmaliParser.InvokeInterfaceInstructionContext) {
        parseInvokeInstruction("invoke-interface", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    override fun enterInvokeInterfaceRangeInstruction(ctx: GeneratedSmaliParser.InvokeInterfaceRangeInstructionContext) {
        parseInvokeInstruction("invoke-interface/range", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    /**
     * Common helper to parse all invoke instructions
     */
    private fun parseInvokeInstruction(
        opcode: String,
        target: GeneratedSmaliParser.MethodInvocationTargetContext?,
        range: Range
    ) {
        if (currentMethod == null || target == null) return
        
        // Format: Lcom/example/MyClass;->myMethod(I)V
        val targetText = target.text
        val arrowIndex = targetText.indexOf("->")
        if (arrowIndex < 0) return
        
        val className = targetText.substring(0, arrowIndex)
        val methodSig = targetText.substring(arrowIndex + 2)
        
        // Parse method signature
        val openParen = methodSig.indexOf('(')
        if (openParen < 0) return
        
        val methodName = methodSig.substring(0, openParen)
        val descriptor = methodSig.substring(openParen)
        
        currentInstructions.add(InvokeInstruction(
            opcode = opcode,
            className = className,
            methodName = methodName,
            descriptor = descriptor,
            range = range
        ))
    }
    
    /**
     * Parse iget instructions
     */
    override fun enterIgetInstruction(ctx: GeneratedSmaliParser.IgetInstructionContext) {
        parseFieldAccessInstruction("iget", ctx.instanceField(), ctx.toRange())
    }
    
    override fun enterIgetWideInstruction(ctx: GeneratedSmaliParser.IgetWideInstructionContext) {
        parseFieldAccessInstruction("iget-wide", ctx.instanceField(), ctx.toRange())
    }
    
    override fun enterIgetObjectInstruction(ctx: GeneratedSmaliParser.IgetObjectInstructionContext) {
        parseFieldAccessInstruction("iget-object", ctx.instanceField(), ctx.toRange())
    }
    
    override fun enterIgetBooleanInstruction(ctx: GeneratedSmaliParser.IgetBooleanInstructionContext) {
        parseFieldAccessInstruction("iget-boolean", ctx.instanceField(), ctx.toRange())
    }
    
    override fun enterIgetByteInstruction(ctx: GeneratedSmaliParser.IgetByteInstructionContext) {
        parseFieldAccessInstruction("iget-byte", ctx.instanceField(), ctx.toRange())
    }
    
    override fun enterIgetCharInstruction(ctx: GeneratedSmaliParser.IgetCharInstructionContext) {
        parseFieldAccessInstruction("iget-char", ctx.instanceField(), ctx.toRange())
    }
    
    override fun enterIgetShortInstruction(ctx: GeneratedSmaliParser.IgetShortInstructionContext) {
        parseFieldAccessInstruction("iget-short", ctx.instanceField(), ctx.toRange())
    }
    
    /**
     * Parse iput instructions
     */
    override fun enterIputInstruction(ctx: GeneratedSmaliParser.IputInstructionContext) {
        parseFieldAccessInstruction("iput", ctx.instanceField(), ctx.toRange())
    }
    
    override fun enterIputWideInstruction(ctx: GeneratedSmaliParser.IputWideInstructionContext) {
        parseFieldAccessInstruction("iput-wide", ctx.instanceField(), ctx.toRange())
    }
    
    override fun enterIputObjectInstruction(ctx: GeneratedSmaliParser.IputObjectInstructionContext) {
        parseFieldAccessInstruction("iput-object", ctx.instanceField(), ctx.toRange())
    }
    
    override fun enterIputBooleanInstruction(ctx: GeneratedSmaliParser.IputBooleanInstructionContext) {
        parseFieldAccessInstruction("iput-boolean", ctx.instanceField(), ctx.toRange())
    }
    
    override fun enterIputByteInstruction(ctx: GeneratedSmaliParser.IputByteInstructionContext) {
        parseFieldAccessInstruction("iput-byte", ctx.instanceField(), ctx.toRange())
    }
    
    override fun enterIputCharInstruction(ctx: GeneratedSmaliParser.IputCharInstructionContext) {
        parseFieldAccessInstruction("iput-char", ctx.instanceField(), ctx.toRange())
    }
    
    override fun enterIputShortInstruction(ctx: GeneratedSmaliParser.IputShortInstructionContext) {
        parseFieldAccessInstruction("iput-short", ctx.instanceField(), ctx.toRange())
    }
    
    /**
     * Parse sget instructions
     */
    override fun enterSGetInstruction(ctx: GeneratedSmaliParser.SGetInstructionContext) {
        parseFieldAccessInstruction("sget", ctx.fieldInvocationTarget(), ctx.toRange())
    }
    
    override fun enterSGetWideInstruction(ctx: GeneratedSmaliParser.SGetWideInstructionContext) {
        parseFieldAccessInstruction("sget-wide", ctx.fieldInvocationTarget(), ctx.toRange())
    }
    
    override fun enterSGetObjectInstruction(ctx: GeneratedSmaliParser.SGetObjectInstructionContext) {
        parseFieldAccessInstruction("sget-object", ctx.fieldInvocationTarget(), ctx.toRange())
    }
    
    override fun enterSGetBooleanInstruction(ctx: GeneratedSmaliParser.SGetBooleanInstructionContext) {
        parseFieldAccessInstruction("sget-boolean", ctx.fieldInvocationTarget(), ctx.toRange())
    }
    
    override fun enterSGetByteInstruction(ctx: GeneratedSmaliParser.SGetByteInstructionContext) {
        parseFieldAccessInstruction("sget-byte", ctx.fieldInvocationTarget(), ctx.toRange())
    }
    
    override fun enterSGetCharInstruction(ctx: GeneratedSmaliParser.SGetCharInstructionContext) {
        parseFieldAccessInstruction("sget-char", ctx.fieldInvocationTarget(), ctx.toRange())
    }
    
    override fun enterSGetShortInstruction(ctx: GeneratedSmaliParser.SGetShortInstructionContext) {
        parseFieldAccessInstruction("sget-short", ctx.fieldInvocationTarget(), ctx.toRange())
    }
    
    /**
     * Parse sput instructions
     */
    override fun enterSPutInstruction(ctx: GeneratedSmaliParser.SPutInstructionContext) {
        parseFieldAccessInstruction("sput", ctx.fieldInvocationTarget(), ctx.toRange())
    }
    
    override fun enterSPutWideInstruction(ctx: GeneratedSmaliParser.SPutWideInstructionContext) {
        parseFieldAccessInstruction("sput-wide", ctx.fieldInvocationTarget(), ctx.toRange())
    }
    
    override fun enterSPutObjectInstruction(ctx: GeneratedSmaliParser.SPutObjectInstructionContext) {
        parseFieldAccessInstruction("sput-object", ctx.fieldInvocationTarget(), ctx.toRange())
    }
    
    override fun enterSPutBooleanInstruction(ctx: GeneratedSmaliParser.SPutBooleanInstructionContext) {
        parseFieldAccessInstruction("sput-boolean", ctx.fieldInvocationTarget(), ctx.toRange())
    }
    
    override fun enterSPutByteInstruction(ctx: GeneratedSmaliParser.SPutByteInstructionContext) {
        parseFieldAccessInstruction("sput-byte", ctx.fieldInvocationTarget(), ctx.toRange())
    }
    
    override fun enterSPutCharInstruction(ctx: GeneratedSmaliParser.SPutCharInstructionContext) {
        parseFieldAccessInstruction("sput-char", ctx.fieldInvocationTarget(), ctx.toRange())
    }
    
    override fun enterSPutShortInstruction(ctx: GeneratedSmaliParser.SPutShortInstructionContext) {
        parseFieldAccessInstruction("sput-short", ctx.fieldInvocationTarget(), ctx.toRange())
    }
    
    /**
     * Common helper to parse field access instructions
     */
    private fun parseFieldAccessInstruction(
        opcode: String,
        fieldCtx: ParserRuleContext?,
        range: Range
    ) {
        if (currentMethod == null || fieldCtx == null) return
        
        // Format: Lcom/example/MyClass;->myField:I
        val fieldText = fieldCtx.text
        val arrowIndex = fieldText.indexOf("->")
        if (arrowIndex < 0) return
        
        val className = fieldText.substring(0, arrowIndex)
        val fieldPart = fieldText.substring(arrowIndex + 2)
        
        // Parse field name and type
        val colonIndex = fieldPart.indexOf(':')
        if (colonIndex < 0) return
        
        val fieldName = fieldPart.substring(0, colonIndex)
        val fieldType = fieldPart.substring(colonIndex + 1)
        
        currentInstructions.add(FieldAccessInstruction(
            opcode = opcode,
            className = className,
            fieldName = fieldName,
            fieldType = fieldType,
            range = range
        ))
    }
    
    /**
     * Parse new-instance instruction
     */
    override fun enterNewInstanceInstruction(ctx: GeneratedSmaliParser.NewInstanceInstructionContext) {
        if (currentMethod == null) return
        
        val className = ctx.newInstanceType()?.text ?: return
        
        currentInstructions.add(TypeInstruction(
            opcode = "new-instance",
            className = className,
            range = ctx.toRange()
        ))
    }
    
    /**
     * Parse check-cast instruction
     */
    override fun enterCheckCastInstruction(ctx: GeneratedSmaliParser.CheckCastInstructionContext) {
        if (currentMethod == null) return
        
        val className = ctx.checkCastType()?.text ?: return
        
        currentInstructions.add(TypeInstruction(
            opcode = "check-cast",
            className = className,
            range = ctx.toRange()
        ))
    }
    
    /**
     * Parse instance-of instruction
     */
    override fun enterInstanceOfInstruction(ctx: GeneratedSmaliParser.InstanceOfInstructionContext) {
        if (currentMethod == null) return
        
        val className = ctx.checkInstanceType()?.text ?: return
        
        currentInstructions.add(TypeInstruction(
            opcode = "instance-of",
            className = className,
            range = ctx.toRange()
        ))
    }
    
    /**
     * Parse const-class instruction
     */
    override fun enterConstClass(ctx: GeneratedSmaliParser.ConstClassContext) {
        if (currentMethod == null) return
        
        val className = ctx.referenceOrArrayType()?.text ?: return
        
        currentInstructions.add(TypeInstruction(
            opcode = "const-class",
            className = className,
            range = ctx.toRange()
        ))
    }
    
    /**
     * Parse filled-new-array instruction
     * Format: filled-new-array {v0, v1, v2}, [Lcom/example/MyClass;
     */
    override fun enterFilledNewArrayInstruction(ctx: GeneratedSmaliParser.FilledNewArrayInstructionContext) {
        if (currentMethod == null) return
        
        val className = ctx.arrayElementType()?.text ?: return
        
        currentInstructions.add(TypeInstruction(
            opcode = "filled-new-array",
            className = className,
            range = ctx.toRange()
        ))
    }
    
    /**
     * Parse filled-new-array/range instruction
     * Format: filled-new-array/range {v0 .. v9}, [[Lt5/d;
     */
    override fun enterFilledNewArrayRangeInstruction(ctx: GeneratedSmaliParser.FilledNewArrayRangeInstructionContext) {
        if (currentMethod == null) return
        
        val className = ctx.arrayElementType()?.text ?: return
        
        currentInstructions.add(TypeInstruction(
            opcode = "filled-new-array/range",
            className = className,
            range = ctx.toRange()
        ))
    }
    
    /**
     * Parse new-array instruction
     * Format: new-array v1, v0, [I
     */
    override fun enterNewArrayInstruction(ctx: GeneratedSmaliParser.NewArrayInstructionContext) {
        if (currentMethod == null) return
        
        val className = ctx.arrayElementType()?.text ?: return
        
        currentInstructions.add(TypeInstruction(
            opcode = "new-array",
            className = className,
            range = ctx.toRange()
        ))
    }
    
    // ========== Label Parsing ==========

    // ========== String Constant Parsing ==========

    /**
     * Parse const-string instruction
     * Format: const-string v0, "string value"
     */
    override fun enterConstString(ctx: GeneratedSmaliParser.ConstStringContext) {
        if (currentMethod == null) return

        val register = ctx.registerIdentifier()?.text ?: return
        val rawLiteral = ctx.stringLiteral()?.text ?: return
        val value = rawLiteral.removeSurrounding("\"")

        currentInstructions.add(ConstStringInstruction(
            opcode = "const-string",
            value = value,
            register = register,
            range = ctx.toRange()
        ))
    }

    /**
     * Parse const-string/jumbo instruction
     * Format: const-string/jumbo v0, "string value"
     */
    override fun enterConstStringJumbo(ctx: GeneratedSmaliParser.ConstStringJumboContext) {
        if (currentMethod == null) return

        val register = ctx.registerIdentifier()?.text ?: return
        val rawLiteral = ctx.stringLiteral()?.text ?: return
        val value = rawLiteral.removeSurrounding("\"")

        currentInstructions.add(ConstStringInstruction(
            opcode = "const-string/jumbo",
            value = value,
            register = register,
            range = ctx.toRange()
        ))
    }

    // ========== Label Parsing ==========
    
    /**
     * Parse label definition (e.g., :cond_0)
     * lineLabel is the rule for labels in method body statements
     */
    override fun enterLineLabel(ctx: GeneratedSmaliParser.LineLabelContext) {
        if (currentMethod == null) return
        
        val labelCtx = ctx.label() ?: return
        val labelName = labelCtx.labelName()?.text ?: return
        
        currentLabels[labelName] = LabelDefinition(
            name = labelName,
            range = ctx.toRange()
        )
    }
    
    /**
     * Parse goto instruction
     */
    override fun enterGotoInstruction(ctx: GeneratedSmaliParser.GotoInstructionContext) {
        parseJumpInstruction("goto", ctx.label(), ctx.toRange())
    }
    
    override fun enterGoto16Instruction(ctx: GeneratedSmaliParser.Goto16InstructionContext) {
        parseJumpInstruction("goto/16", ctx.label(), ctx.toRange())
    }
    
    override fun enterGoto32Instruction(ctx: GeneratedSmaliParser.Goto32InstructionContext) {
        parseJumpInstruction("goto/32", ctx.label(), ctx.toRange())
    }
    
    /**
     * Parse if-*z instructions (single register tests)
     */
    override fun enterIfEqzInstruction(ctx: GeneratedSmaliParser.IfEqzInstructionContext) {
        parseJumpInstruction("if-eqz", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun enterIfNezInstruction(ctx: GeneratedSmaliParser.IfNezInstructionContext) {
        parseJumpInstruction("if-nez", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun enterIfLtzInstruction(ctx: GeneratedSmaliParser.IfLtzInstructionContext) {
        parseJumpInstruction("if-ltz", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun enterIfGezInstruction(ctx: GeneratedSmaliParser.IfGezInstructionContext) {
        parseJumpInstruction("if-gez", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun enterIfGtzInstruction(ctx: GeneratedSmaliParser.IfGtzInstructionContext) {
        parseJumpInstruction("if-gtz", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun enterIfLezInstruction(ctx: GeneratedSmaliParser.IfLezInstructionContext) {
        parseJumpInstruction("if-lez", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    /**
     * Parse if-* instructions (two register comparisons)
     */
    override fun enterIfEqInstruction(ctx: GeneratedSmaliParser.IfEqInstructionContext) {
        parseJumpInstruction("if-eq", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun enterIfNeInstruction(ctx: GeneratedSmaliParser.IfNeInstructionContext) {
        parseJumpInstruction("if-ne", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun enterIfLtInstruction(ctx: GeneratedSmaliParser.IfLtInstructionContext) {
        parseJumpInstruction("if-lt", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun enterIfGeInstruction(ctx: GeneratedSmaliParser.IfGeInstructionContext) {
        parseJumpInstruction("if-ge", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun enterIfGtInstruction(ctx: GeneratedSmaliParser.IfGtInstructionContext) {
        parseJumpInstruction("if-gt", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun enterIfLeInstruction(ctx: GeneratedSmaliParser.IfLeInstructionContext) {
        parseJumpInstruction("if-le", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    /**
     * Common helper to parse jump instructions
     */
    private fun parseJumpInstruction(
        opcode: String,
        labelCtx: GeneratedSmaliParser.LabelContext?,
        fullRange: Range
    ) {
        if (currentMethod == null || labelCtx == null) return
        
        val labelName = labelCtx.labelName()?.text ?: return
        val labelRange = labelCtx.toRange()
        
        currentInstructions.add(JumpInstruction(
            opcode = opcode,
            targetLabel = labelName,
            range = fullRange,
            labelRange = labelRange
        ))
    }
}
