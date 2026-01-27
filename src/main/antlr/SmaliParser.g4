//MIT License
//
//Copyright (c) 2018 psygate
//
//Permission is hereby granted, free of charge, to any person obtaining a copy
//of this software and associated documentation files (the "Software"), to deal
//in the Software without restriction, including without limitation the rights
//to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
//copies of the Software, and to permit persons to whom the Software is
//furnished to do so, subject to the following conditions:
//
//The above copyright notice and this permission notice shall be included in all
//copies or substantial portions of the Software.
//
//THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
//SOFTWARE.

parser grammar SmaliParser;

options {
	tokenVocab=SmaliLexer;
}

// Helpers

registerIdentifier:             IDENTIFIER;
stringLiteral:                  STRING_LITERAL;
negativeNumericLiteral:         SUB positiveNumericLiteral;

decimalNumericLiteral:          DECIMAL_LITERAL;
hexNumericLiteral:              HEX_LITERAL;
octNumericLiteral:              OCT_LITERAL;
binaryNumericLiteral:           BINARY_LITERAL;
floatNumericLiteral:            FLOAT_LITERAL;
hexFloatLiteral:                HEX_FLOAT_LITERAL;

positiveNumericLiteral:
    decimalNumericLiteral
    | hexNumericLiteral
    | octNumericLiteral
    | binaryNumericLiteral
    | floatNumericLiteral
    | hexFloatLiteral
    | FLOAT_SPECIAL
    ;

numericLiteral:                 negativeNumericLiteral | positiveNumericLiteral;

// This rule is necessary, since some keywords like "annotation" aren't context free.
identifier:
    (
        IDENTIFIER
        | VOID_TYPE
        | BOOLEAN_TYPE
        | BYTE_TYPE
        | SHORT_TYPE
        | CHAR_TYPE
        | INT_TYPE
        | LONG_TYPE
        | FLOAT_TYPE
        | DOUBLE_TYPE
        | CONSTRUCTOR
        | ANNOTATION
        | BRIDGE
        | NATIVE
        | VARARGS
        | SYNTHETIC
        | PUBLIC
        | PRIVATE
        | PROTECTED
        | FINAL
        | STATIC
        | ABSTRACT
        | ENUM
        | INTERFACE
        | TRANSIENT
        | VOLATILE
    )+
    ;

// Types
referenceType:                  QUALIFIED_TYPE_NAME;
voidType:                       VOID_TYPE;
booleanType:                    BOOLEAN_TYPE;
byteType:                       BYTE_TYPE;
shortType:                      SHORT_TYPE;
charType:                       CHAR_TYPE;
intType:                        INT_TYPE;
longType:                       LONG_TYPE;
floatType:                      FLOAT_TYPE;
doubleType:                     DOUBLE_TYPE;

primitiveType:
    booleanType
    | byteType
    | shortType
    | charType
    | intType
    | longType
    | floatType
    | doubleType
    ;

nonArrayType:               primitiveType | referenceType;

methodParameterLiteral:     (COMPOUND_METHOD_ARG_LITERAL | IDENTIFIER);     // strings like IIS

arrayType:                  LBRACK (nonArrayType | arrayType | methodParameterLiteral);

referenceOrArrayType:       referenceType | arrayType;

nonVoidType:                nonArrayType | arrayType;

anyType:                    nonVoidType | voidType | arrayType;

nullLiteral:                NULL_LITERAL;

booleanLiteral:             BOOL_LITERAL;

charLiteral:                CHAR_LITERAL;

assignableValue:            anyType | stringLiteral | charLiteral | numericLiteral | nullLiteral | booleanLiteral;

// Modifiers

classModifier:
    PUBLIC
    | PRIVATE
    | PROTECTED
    | FINAL
    | ANNOTATION
    | SYNTHETIC
    | STATIC
    | ABSTRACT
    | ENUM
    | INTERFACE
    ;

methodModifier:
    PUBLIC
    | PRIVATE
    | PROTECTED
    | FINAL
    | SYNTHETIC
    | STATIC
    | ABSTRACT
    | CONSTRUCTOR
    | BRIDGE
    | DECLARED_SYNCHRONIZED
    | STRICTFP
    | VARARGS
    | NATIVE
    ;

fieldModifier:
    PUBLIC
    | PRIVATE
    | PROTECTED
    | FINAL
    | SYNTHETIC
    | STATIC
    | TRANSIENT
    | VOLATILE
    | ENUM
    ;

labelName:                      identifier;

label:                          COLON labelName;

// Instructions

leftRegister:                   registerIdentifier;

rightRegister:                  registerIdentifier;

registerListRegisters:          registerIdentifier (COMMA registerIdentifier)*;

registerRange:                  LBRACE leftRegister DOT DOT rightRegister RBRACE;

registerList:                   LBRACE RBRACE | LBRACE registerListRegisters RBRACE;

gotoInstruction:                OP_GOTO label;

goto16Instruction:              OP_GOTO_16 label;

goto32Instruction:              OP_GOTO_32 label;

moveResultInstruction:          OP_MOVE_RESULT registerIdentifier;

moveResultWideInstruction:      OP_MOVE_RESULT_WIDE registerIdentifier;

moveResultObjectInstruction:    OP_MOVE_RESULT_OBJECT registerIdentifier;

moveExceptionInstruction:       OP_MOVE_EXCEPTION registerIdentifier;

returnInstruction:              OP_RETURN registerIdentifier;

returnWideInstruction:          OP_RETURN_WIDE registerIdentifier;

returnObjectInstruction:        OP_RETURN_OBJECT registerIdentifier;

monitorEnterInstruction:        OP_MONITOR_ENTER registerIdentifier;

monitorExitInstruction:         OP_MONITOR_EXIT registerIdentifier;

throwInstruction:               OP_THROW registerIdentifier;

returnVoidInstruction:          OP_RETURN_VOID;

nopInstruction:                 OP_NOP;

moveInstruction:                OP_MOVE leftRegister COMMA rightRegister;

moveFrom16Instruction:          OP_MOVE_FROM16 leftRegister COMMA rightRegister;

move16Instruction:              OP_MOVE_16 leftRegister COMMA rightRegister;

moveWideInstruction:            OP_MOVE_WIDE leftRegister COMMA rightRegister;

moveWideFrom16Instruction:      OP_MOVE_WIDE_FROM16 leftRegister COMMA rightRegister;

moveWide16Instruction:          OP_MOVE_WIDE_16 leftRegister COMMA rightRegister;

moveObjectInstruction:          OP_MOVE_OBJECT leftRegister COMMA rightRegister;

moveObjectFrom16Instruction:    OP_MOVE_OBJECT_FROM16 leftRegister COMMA rightRegister;

moveObject16Instruction:        OP_MOVE_OBJECT_16 leftRegister COMMA rightRegister;

constInstruction:               OP_CONST registerIdentifier COMMA numericLiteral;

const4Instruction:              OP_CONST_4 registerIdentifier COMMA numericLiteral;

const16Instruction:             OP_CONST_16 registerIdentifier COMMA numericLiteral;

constHigh16Instruction:         OP_CONST_HIGH16 registerIdentifier COMMA numericLiteral;

constWide16Instruction:         OP_CONST_WIDE_16 registerIdentifier COMMA numericLiteral;

constWide32Instruction:         OP_CONST_WIDE_32 registerIdentifier COMMA numericLiteral;

constWideInstruction:           OP_CONST_WIDE registerIdentifier COMMA numericLiteral;

constWideHigh16Instruction:     OP_CONST_WIDE_HIGH16 registerIdentifier COMMA numericLiteral;

constString:                    OP_CONST_STRING registerIdentifier COMMA stringLiteral;

constStringJumbo:               OP_CONST_STRING_JUMBO registerIdentifier COMMA stringLiteral;

constClass:                     OP_CONST_CLASS registerIdentifier COMMA referenceOrArrayType;

sGetInstruction:                OP_SGET registerIdentifier COMMA fieldInvocationTarget;

sGetWideInstruction:            OP_SGET_WIDE registerIdentifier COMMA fieldInvocationTarget;

sGetObjectInstruction:          OP_SGET_OBJECT registerIdentifier COMMA fieldInvocationTarget;

sGetBooleanInstruction:         OP_SGET_BOOLEAN registerIdentifier COMMA fieldInvocationTarget;

sGetByteInstruction:            OP_SGET_BYTE registerIdentifier COMMA fieldInvocationTarget;

sGetCharInstruction:            OP_SGET_CHAR registerIdentifier COMMA fieldInvocationTarget;

sGetShortInstruction:           OP_SGET_SHORT registerIdentifier COMMA fieldInvocationTarget;

sPutInstruction:                OP_SPUT registerIdentifier COMMA fieldInvocationTarget;

sPutWideInstruction:            OP_SPUT_WIDE registerIdentifier COMMA fieldInvocationTarget;

sPutObjectInstruction:          OP_SPUT_OBJECT registerIdentifier COMMA fieldInvocationTarget;

sPutBooleanInstruction:         OP_SPUT_BOOLEAN registerIdentifier COMMA fieldInvocationTarget;

sPutByteInstruction:            OP_SPUT_BYTE registerIdentifier COMMA fieldInvocationTarget;

sPutCharInstruction:            OP_SPUT_CHAR registerIdentifier COMMA fieldInvocationTarget;

sPutShortInstruction:           OP_SPUT_SHORT registerIdentifier COMMA fieldInvocationTarget;

invokeVirtualInstruction:       OP_INVOKE_VIRTUAL registerList COMMA methodInvocationTarget;

invokeSuperInstruction:         OP_INVOKE_SUPER registerList COMMA methodInvocationTarget;

invokeDirectInstruction:        OP_INVOKE_DIRECT registerList COMMA methodInvocationTarget;

invokeStaticInstruction:        OP_INVOKE_STATIC registerList COMMA methodInvocationTarget;

invokeInterfaceInstruction:     OP_INVOKE_INTERFACE registerList COMMA methodInvocationTarget;

invokeVirtualRangeInstruction:  OP_INVOKE_VIRTUAL_RANGE registerRange COMMA methodInvocationTarget;

invokeSuperRangeInstruction:    OP_INVOKE_SUPER_RANGE registerRange COMMA methodInvocationTarget;

invokeDirectRangeInstruction:   OP_INVOKE_DIRECT_RANGE registerRange COMMA methodInvocationTarget;

invokeStaticRangeInstruction:   OP_INVOKE_STATIC_RANGE registerRange COMMA methodInvocationTarget;

invokeInterfaceRangeInstruction:OP_INVOKE_INTERFACE_RANGE registerRange COMMA methodInvocationTarget;

intToLongInstruction:           OP_INT_TO_LONG leftRegister COMMA rightRegister;

intToFloatInstruction:          OP_INT_TO_FLOAT leftRegister COMMA rightRegister;

intToDoubleInstruction:         OP_INT_TO_DOUBLE leftRegister COMMA rightRegister;

longToIntInstruction:           OP_LONG_TO_INT leftRegister COMMA rightRegister;

longToFloatInstruction:         OP_LONG_TO_FLOAT leftRegister COMMA rightRegister;

longToDoubleInstruction:        OP_LONG_TO_DOUBLE leftRegister COMMA rightRegister;

floatToIntInstruction:          OP_FLOAT_TO_INT leftRegister COMMA rightRegister;

floatToLongInstruction:         OP_FLOAT_TO_LONG leftRegister COMMA rightRegister;

floatToDoubleInstruction:       OP_FLOAT_TO_DOUBLE leftRegister COMMA rightRegister;

doubleToIntInstruction:         OP_DOUBLE_TO_INT leftRegister COMMA rightRegister;

doubleToLongInstruction:        OP_DOUBLE_TO_LONG leftRegister COMMA rightRegister;

doubleToFloatInstruction:       OP_DOUBLE_TO_FLOAT leftRegister COMMA rightRegister;

intToByteInstruction:           OP_INT_TO_BYTE leftRegister COMMA rightRegister;

intToCharInstruction:           OP_INT_TO_CHAR leftRegister COMMA rightRegister;

intToShortInstruction:          OP_INT_TO_SHORT leftRegister COMMA rightRegister;

ifLabel:                        label;

ifEqzInstruction:               OP_IF_EQZ registerIdentifier COMMA ifLabel;

ifNezInstruction:               OP_IF_NEZ registerIdentifier COMMA ifLabel;

ifLtzInstruction:               OP_IF_LTZ registerIdentifier COMMA ifLabel;

ifGezInstruction:               OP_IF_GEZ registerIdentifier COMMA ifLabel;

ifGtzInstruction:               OP_IF_GTZ registerIdentifier COMMA ifLabel;

ifLezInstruction:               OP_IF_LEZ registerIdentifier COMMA ifLabel;

negIntInstruction:              OP_NEG_INT leftRegister COMMA rightRegister;

notIntInstruction:              OP_NOT_INT leftRegister COMMA rightRegister;

negLongInstruction:             OP_NEG_LONG leftRegister COMMA rightRegister;

notLongInstruction:             OP_NOT_LONG leftRegister COMMA rightRegister;

negFloatInstruction:            OP_NEG_FLOAT leftRegister COMMA rightRegister;

negDoubleInstruction:           OP_NEG_DOUBLE leftRegister COMMA rightRegister;

ifEqInstruction:                OP_IF_EQ leftRegister COMMA rightRegister COMMA ifLabel;

ifNeInstruction:                OP_IF_NE leftRegister COMMA rightRegister COMMA ifLabel;

ifLtInstruction:                OP_IF_LT leftRegister COMMA rightRegister COMMA ifLabel;

ifGeInstruction:                OP_IF_GE leftRegister COMMA rightRegister COMMA ifLabel;

ifGtInstruction:                OP_IF_GT leftRegister COMMA rightRegister COMMA ifLabel;

ifLeInstruction:                OP_IF_LE leftRegister COMMA rightRegister COMMA ifLabel;

addInt2addrInstruction:         OP_ADD_INT_2ADDR leftRegister COMMA rightRegister;

subInt2addrInstruction:         OP_SUB_INT_2ADDR leftRegister COMMA rightRegister;

mulInt2addrInstruction:         OP_MUL_INT_2ADDR leftRegister COMMA rightRegister;

divInt2addrInstruction:         OP_DIV_INT_2ADDR leftRegister COMMA rightRegister;

remInt2addrInstruction:         OP_REM_INT_2ADDR leftRegister COMMA rightRegister;

andInt2addrInstruction:         OP_AND_INT_2ADDR leftRegister COMMA rightRegister;

orInt2addrInstruction:          OP_OR_INT_2ADDR leftRegister COMMA rightRegister;

xorInt2addrInstruction:         OP_XOR_INT_2ADDR leftRegister COMMA rightRegister;

shlInt2addrInstruction:         OP_SHL_INT_2ADDR leftRegister COMMA rightRegister;

shrInt2addrInstruction:         OP_SHR_INT_2ADDR leftRegister COMMA rightRegister;

ushrInt2addrInstruction:        OP_USHR_INT_2ADDR leftRegister COMMA rightRegister;

addLong2addrInstruction:        OP_ADD_LONG_2ADDR leftRegister COMMA rightRegister;

subLong2addrInstruction:        OP_SUB_LONG_2ADDR leftRegister COMMA rightRegister;

mulLong2addrInstruction:        OP_MUL_LONG_2ADDR leftRegister COMMA rightRegister;

divLong2addrInstruction:        OP_DIV_LONG_2ADDR leftRegister COMMA rightRegister;

remLong2addrInstruction:        OP_REM_LONG_2ADDR leftRegister COMMA rightRegister;

andLong2addrInstruction:        OP_AND_LONG_2ADDR leftRegister COMMA rightRegister;

orLong2addrInstruction:         OP_OR_LONG_2ADDR leftRegister COMMA rightRegister;

xorLong2addrInstruction:        OP_XOR_LONG_2ADDR leftRegister COMMA rightRegister;

shlLong2addrInstruction:        OP_SHL_LONG_2ADDR leftRegister COMMA rightRegister;

shrLong2addrInstruction:        OP_SHR_LONG_2ADDR leftRegister COMMA rightRegister;

ushrLong2addrInstruction:       OP_USHR_LONG_2ADDR leftRegister COMMA rightRegister;

addFloat2addrInstruction:       OP_ADD_FLOAT_2ADDR leftRegister COMMA rightRegister;

subFloat2addrInstruction:       OP_SUB_FLOAT_2ADDR leftRegister COMMA rightRegister;

mulFloat2addrInstruction:       OP_MUL_FLOAT_2ADDR leftRegister COMMA rightRegister;

divFloat2addrInstruction:       OP_DIV_FLOAT_2ADDR leftRegister COMMA rightRegister;

remFloat2addrInstruction:       OP_REM_FLOAT_2ADDR leftRegister COMMA rightRegister;

addDouble2addrInstruction:      OP_ADD_DOUBLE_2ADDR leftRegister COMMA rightRegister;

subDouble2addrInstruction:      OP_SUB_DOUBLE_2ADDR leftRegister COMMA rightRegister;

mulDouble2addrInstruction:      OP_MUL_DOUBLE_2ADDR leftRegister COMMA rightRegister;

divDouble2addrInstruction:      OP_DIV_DOUBLE_2ADDR leftRegister COMMA rightRegister;

remDouble2addrInstruction:      OP_REM_DOUBLE_2ADDR leftRegister COMMA rightRegister;

cmplFloatInstruction:           OP_CMPL_FLOAT targetRegister COMMA leftRegister COMMA rightRegister;

cmpgFloatInstruction:           OP_CMPG_FLOAT targetRegister COMMA leftRegister COMMA rightRegister;

cmplDoubleInstruction:          OP_CMPL_DOUBLE targetRegister COMMA leftRegister COMMA rightRegister;

cmpgDoubleInstruction:          OP_CMPG_DOUBLE targetRegister COMMA leftRegister COMMA rightRegister;

cmpLongInstruction:             OP_CMP_LONG targetRegister COMMA leftRegister COMMA rightRegister;

field:                          registerIdentifier;

arrayRegister:                  registerIdentifier;

// Allow hex literals as array indices (Fix for BUG-003)
indexRegister:                  registerIdentifier | numericLiteral;

instanceRegister:               registerIdentifier;

sourceRegister:                 registerIdentifier;

targetRegister:                 registerIdentifier;

instanceField:                  fieldInvocationTarget;


agetInstruction:                OP_AGET targetRegister COMMA arrayRegister COMMA indexRegister;

agetWideInstruction:            OP_AGET_WIDE targetRegister COMMA arrayRegister COMMA indexRegister;

agetObjectInstruction:          OP_AGET_OBJECT targetRegister COMMA arrayRegister COMMA indexRegister;

agetBooleanInstruction:         OP_AGET_BOOLEAN targetRegister COMMA arrayRegister COMMA indexRegister;

agetByteInstruction:            OP_AGET_BYTE targetRegister COMMA arrayRegister COMMA indexRegister;

agetCharInstruction:            OP_AGET_CHAR targetRegister COMMA arrayRegister COMMA indexRegister;

agetShortInstruction:           OP_AGET_SHORT targetRegister COMMA arrayRegister COMMA indexRegister;


aputInstruction:                OP_APUT sourceRegister COMMA arrayRegister COMMA indexRegister;

aputWideInstruction:            OP_APUT_WIDE sourceRegister COMMA arrayRegister COMMA indexRegister;

aputObjectInstruction:          OP_APUT_OBJECT sourceRegister COMMA arrayRegister COMMA indexRegister;

aputBooleanInstruction:         OP_APUT_BOOLEAN sourceRegister COMMA arrayRegister COMMA indexRegister;

aputByteInstruction:            OP_APUT_BYTE sourceRegister COMMA arrayRegister COMMA indexRegister;

aputCharInstruction:            OP_APUT_CHAR sourceRegister COMMA arrayRegister COMMA indexRegister;

aputShortInstruction:           OP_APUT_SHORT sourceRegister COMMA arrayRegister COMMA indexRegister;


igetInstruction:                OP_IGET targetRegister COMMA instanceRegister COMMA instanceField;

igetWideInstruction:            OP_IGET_WIDE targetRegister COMMA instanceRegister COMMA instanceField;

igetObjectInstruction:          OP_IGET_OBJECT targetRegister COMMA instanceRegister COMMA instanceField;

igetBooleanInstruction:         OP_IGET_BOOLEAN targetRegister COMMA instanceRegister COMMA instanceField;

igetByteInstruction:            OP_IGET_BYTE targetRegister COMMA instanceRegister COMMA instanceField;

igetCharInstruction:            OP_IGET_CHAR targetRegister COMMA instanceRegister COMMA instanceField;

igetShortInstruction:           OP_IGET_SHORT targetRegister COMMA instanceRegister COMMA instanceField;


iputInstruction:                OP_IPUT sourceRegister COMMA instanceRegister COMMA instanceField;

iputWideInstruction:            OP_IPUT_WIDE sourceRegister COMMA instanceRegister COMMA instanceField;

iputObjectInstruction:          OP_IPUT_OBJECT sourceRegister COMMA instanceRegister COMMA instanceField;

iputBooleanInstruction:         OP_IPUT_BOOLEAN sourceRegister COMMA instanceRegister COMMA instanceField;

iputByteInstruction:            OP_IPUT_BYTE sourceRegister COMMA instanceRegister COMMA instanceField;

iputCharInstruction:            OP_IPUT_CHAR sourceRegister COMMA instanceRegister COMMA instanceField;

iputShortInstruction:           OP_IPUT_SHORT sourceRegister COMMA instanceRegister COMMA instanceField;


addIntInstruction:              OP_ADD_INT targetRegister COMMA leftRegister COMMA rightRegister;

subIntInstruction:              OP_SUB_INT targetRegister COMMA leftRegister COMMA rightRegister;

mulIntInstruction:              OP_MUL_INT targetRegister COMMA leftRegister COMMA rightRegister;

divIntInstruction:              OP_DIV_INT targetRegister COMMA leftRegister COMMA rightRegister;

remIntInstruction:              OP_REM_INT targetRegister COMMA leftRegister COMMA rightRegister;

andIntInstruction:              OP_AND_INT targetRegister COMMA leftRegister COMMA rightRegister;

orIntInstruction:               OP_OR_INT targetRegister COMMA leftRegister COMMA rightRegister;

xorIntInstruction:              OP_XOR_INT targetRegister COMMA leftRegister COMMA rightRegister;

shlIntInstruction:              OP_SHL_INT targetRegister COMMA leftRegister COMMA rightRegister;

shrIntInstruction:              OP_SHR_INT targetRegister COMMA leftRegister COMMA rightRegister;

ushrIntInstruction:             OP_USHR_INT targetRegister COMMA leftRegister COMMA rightRegister;

// rsub-int can take either a register or a literal (Fix for BUG-003)
rsubIntInstruction:             OP_RSUB_INT targetRegister COMMA leftRegister COMMA (rightRegister | numericLiteral);

addLongInstruction:             OP_ADD_LONG targetRegister COMMA leftRegister COMMA rightRegister;

subLongInstruction:             OP_SUB_LONG targetRegister COMMA leftRegister COMMA rightRegister;

mulLongInstruction:             OP_MUL_LONG targetRegister COMMA leftRegister COMMA rightRegister;

divLongInstruction:             OP_DIV_LONG targetRegister COMMA leftRegister COMMA rightRegister;

remLongInstruction:             OP_REM_LONG targetRegister COMMA leftRegister COMMA rightRegister;

andLongInstruction:             OP_AND_LONG targetRegister COMMA leftRegister COMMA rightRegister;

orLongInstruction:              OP_OR_LONG targetRegister COMMA leftRegister COMMA rightRegister;

xorLongInstruction:             OP_XOR_LONG targetRegister COMMA leftRegister COMMA rightRegister;

shlLongInstruction:             OP_SHL_LONG targetRegister COMMA leftRegister COMMA rightRegister;

shrLongInstruction:             OP_SHR_LONG targetRegister COMMA leftRegister COMMA rightRegister;

ushrLongInstruction:            OP_USHR_LONG targetRegister COMMA leftRegister COMMA rightRegister;

addFloatInstruction:            OP_ADD_FLOAT targetRegister COMMA leftRegister COMMA rightRegister;

subFloatInstruction:            OP_SUB_FLOAT targetRegister COMMA leftRegister COMMA rightRegister;

mulFloatInstruction:            OP_MUL_FLOAT targetRegister COMMA leftRegister COMMA rightRegister;

divFloatInstruction:            OP_DIV_FLOAT targetRegister COMMA leftRegister COMMA rightRegister;

remFloatInstruction:            OP_REM_FLOAT targetRegister COMMA leftRegister COMMA rightRegister;

addDoubleInstruction:           OP_ADD_DOUBLE targetRegister COMMA leftRegister COMMA rightRegister;

subDoubleInstruction:           OP_SUB_DOUBLE targetRegister COMMA leftRegister COMMA rightRegister;

mulDoubleInstruction:           OP_MUL_DOUBLE targetRegister COMMA leftRegister COMMA rightRegister;

divDoubleInstruction:           OP_DIV_DOUBLE targetRegister COMMA leftRegister COMMA rightRegister;

remDoubleInstruction:           OP_REM_DOUBLE targetRegister COMMA leftRegister COMMA rightRegister;

addIntLit16Instruction:         OP_ADD_INT_LIT16 targetRegister COMMA leftRegister COMMA numericLiteral;

mulIntLit16Instruction:         OP_MUL_INT_LIT16 targetRegister COMMA leftRegister COMMA numericLiteral;

divIntLit16Instruction:         OP_DIV_INT_LIT16 targetRegister COMMA leftRegister COMMA numericLiteral;

remIntLit16Instruction:         OP_REM_INT_LIT16 targetRegister COMMA leftRegister COMMA numericLiteral;

andIntLit16Instruction:         OP_AND_INT_LIT16 targetRegister COMMA leftRegister COMMA numericLiteral;

orIntLit16Instruction:          OP_OR_INT_LIT16 targetRegister COMMA leftRegister COMMA numericLiteral;

xorIntLit16Instruction:         OP_XOR_INT_LIT16 targetRegister COMMA leftRegister COMMA numericLiteral;


addIntLit8Instruction:          OP_ADD_INT_LIT8 targetRegister COMMA leftRegister COMMA numericLiteral;

rsubIntLit8Instruction:         OP_RSUB_INT_LIT8 targetRegister COMMA leftRegister COMMA numericLiteral;

mulIntLit8Instruction:          OP_MUL_INT_LIT8 targetRegister COMMA leftRegister COMMA numericLiteral;

divIntLit8Instruction:          OP_DIV_INT_LIT8 targetRegister COMMA leftRegister COMMA numericLiteral;

remIntLit8Instruction:          OP_REM_INT_LIT8 targetRegister COMMA leftRegister COMMA numericLiteral;

andIntLit8Instruction:          OP_AND_INT_LIT8 targetRegister COMMA leftRegister COMMA numericLiteral;

orIntLit8Instruction:           OP_OR_INT_LIT8 targetRegister COMMA leftRegister COMMA numericLiteral;

xorIntLit8Instruction:          OP_XOR_INT_LIT8 targetRegister COMMA leftRegister COMMA numericLiteral;

shlIntLit8Instruction:          OP_SHL_INT_LIT8 targetRegister COMMA leftRegister COMMA numericLiteral;

shrIntLit8Instruction:          OP_SHR_INT_LIT8 targetRegister COMMA leftRegister COMMA numericLiteral;

ushrIntLit8Instruction:         OP_USHR_INT_LIT8 targetRegister COMMA leftRegister COMMA numericLiteral;

newInstanceType:                referenceType;

newInstanceInstruction:         OP_NEW_INSTANCE targetRegister COMMA newInstanceType;

checkCastType:                  referenceOrArrayType;

checkCastInstruction:           OP_CHECK_CAST targetRegister COMMA checkCastType;

arrayLengthInstruction:         OP_ARRAY_LENGTH targetRegister COMMA arrayRegister;

arrayElementType:               nonVoidType;

arrayElementRegisterRange:      registerRange;

arrayElementRegisters:          registerList;

filledNewArrayRangeInstruction:            OP_FILLED_NEW_ARRAY_RANGE arrayElementRegisterRange COMMA arrayElementType;

filledNewArrayInstruction:                 OP_FILLED_NEW_ARRAY arrayElementRegisters COMMA arrayElementType;

filledArrayDataLabel:           label;

fillArrayDataInstruction:                  OP_FILL_ARRAY_DATA targetRegister COMMA filledArrayDataLabel;

checkInstanceType:              nonVoidType;

instanceOfInstruction:          OP_INSTANCE_OF targetRegister COMMA instanceRegister COMMA checkInstanceType;

arraySizeRegister:              registerIdentifier;

newArrayInstruction:            OP_NEW_ARRAY targetRegister COMMA arraySizeRegister COMMA arrayElementType;

packedSwitchRegister:           registerIdentifier;

packedSwitchLabel:              label;

sparseSwitchRegister:           registerIdentifier;

sparseSwitchLabel:              label;

packedSwitchInstruction:        OP_PACKED_SWITCH packedSwitchRegister COMMA packedSwitchLabel;

sparseSwitchInstruction:        OP_SPARSE_SWITCH sparseSwitchRegister COMMA sparseSwitchLabel;

invokePolymorphicInstruction:   OP_INVOKE_POLYMORPHIC;

invokePolymorphicRangeInstruction:  OP_INVOKE_POLYMORPHIC_RANGE;

invokeCustomInstruction:        OP_INVOKE_CUSTOM;

invokeCustomRangeInstruction:   OP_INVOKE_CUSTOM_RANGE;

invokeConstMethodHandleInstruction: OP_CONST_METHOD_HANDLE;

invokeConstMethodTypeInstruction:   OP_CONST_METHOD_TYPE;

binaryInstruction:
    filledNewArrayRangeInstruction
    | filledNewArrayInstruction
    | fillArrayDataInstruction
    | arrayLengthInstruction

    | packedSwitchInstruction
    | sparseSwitchInstruction

    | newInstanceInstruction
    | checkCastInstruction

    | moveInstruction
    | moveFrom16Instruction
    | move16Instruction
    | moveWideInstruction
    | moveWideFrom16Instruction
    | moveWide16Instruction
    | moveObjectInstruction
    | moveObjectFrom16Instruction
    | moveObject16Instruction

    | constInstruction
    | const4Instruction
    | const16Instruction
    | constHigh16Instruction
    | constWide16Instruction
    | constWide32Instruction
    | constWideInstruction
    | constWideHigh16Instruction

    | constString
    | constStringJumbo
    | constClass

    | sGetInstruction
    | sGetWideInstruction
    | sGetObjectInstruction
    | sGetBooleanInstruction
    | sGetByteInstruction
    | sGetCharInstruction
    | sGetShortInstruction

    | sPutInstruction
    | sPutWideInstruction
    | sPutObjectInstruction
    | sPutBooleanInstruction
    | sPutByteInstruction
    | sPutCharInstruction
    | sPutShortInstruction

    | invokeVirtualInstruction
    | invokeSuperInstruction
    | invokeDirectInstruction
    | invokeStaticInstruction
    | invokeInterfaceInstruction

    | invokeVirtualRangeInstruction
    | invokeSuperRangeInstruction
    | invokeDirectRangeInstruction
    | invokeStaticRangeInstruction
    | invokeInterfaceRangeInstruction

    | intToLongInstruction
    | intToFloatInstruction
    | intToDoubleInstruction
    | longToIntInstruction
    | longToFloatInstruction
    | longToDoubleInstruction
    | floatToIntInstruction
    | floatToLongInstruction
    | floatToDoubleInstruction
    | doubleToIntInstruction
    | doubleToLongInstruction
    | doubleToFloatInstruction
    | intToByteInstruction
    | intToCharInstruction
    | intToShortInstruction

    | ifEqzInstruction
    | ifNezInstruction
    | ifLtzInstruction
    | ifGezInstruction
    | ifGtzInstruction
    | ifLezInstruction

    | negIntInstruction
    | notIntInstruction
    | negLongInstruction
    | notLongInstruction
    | negFloatInstruction
    | negDoubleInstruction

    | ifEqInstruction
    | ifNeInstruction
    | ifLtInstruction
    | ifGeInstruction
    | ifGtInstruction
    | ifLeInstruction

    | addInt2addrInstruction
    | subInt2addrInstruction
    | mulInt2addrInstruction
    | divInt2addrInstruction
    | remInt2addrInstruction
    | andInt2addrInstruction
    | orInt2addrInstruction
    | xorInt2addrInstruction
    | shlInt2addrInstruction
    | shrInt2addrInstruction
    | ushrInt2addrInstruction
    | addLong2addrInstruction
    | subLong2addrInstruction
    | mulLong2addrInstruction
    | divLong2addrInstruction
    | remLong2addrInstruction
    | andLong2addrInstruction
    | orLong2addrInstruction
    | xorLong2addrInstruction
    | shlLong2addrInstruction
    | shrLong2addrInstruction
    | ushrLong2addrInstruction
    | addFloat2addrInstruction
    | subFloat2addrInstruction
    | mulFloat2addrInstruction
    | divFloat2addrInstruction
    | remFloat2addrInstruction
    | addDouble2addrInstruction
    | subDouble2addrInstruction
    | mulDouble2addrInstruction
    | divDouble2addrInstruction
    | remDouble2addrInstruction

    | cmplFloatInstruction
    | cmpgFloatInstruction
    | cmplDoubleInstruction
    | cmpgDoubleInstruction
    | cmpLongInstruction
    ;

ternaryInstruction:
    invokePolymorphicInstruction
    | invokePolymorphicRangeInstruction
    | invokeCustomInstruction
    | invokeCustomRangeInstruction
    | invokeConstMethodHandleInstruction
    | invokeConstMethodTypeInstruction

    | instanceOfInstruction
    | newArrayInstruction

    | agetInstruction
    | agetWideInstruction
    | agetObjectInstruction
    | agetBooleanInstruction
    | agetByteInstruction
    | agetCharInstruction
    | agetShortInstruction

    | aputInstruction
    | aputWideInstruction
    | aputObjectInstruction
    | aputBooleanInstruction
    | aputByteInstruction
    | aputCharInstruction
    | aputShortInstruction

    | igetInstruction
    | igetWideInstruction
    | igetObjectInstruction
    | igetBooleanInstruction
    | igetByteInstruction
    | igetCharInstruction
    | igetShortInstruction

    | iputInstruction
    | iputWideInstruction
    | iputObjectInstruction
    | iputBooleanInstruction
    | iputByteInstruction
    | iputCharInstruction
    | iputShortInstruction

    | addIntInstruction
    | subIntInstruction
    | mulIntInstruction
    | divIntInstruction
    | remIntInstruction
    | andIntInstruction
    | orIntInstruction
    | xorIntInstruction
    | shlIntInstruction
    | shrIntInstruction
    | ushrIntInstruction
    | rsubIntInstruction

    | addLongInstruction
    | subLongInstruction
    | mulLongInstruction
    | divLongInstruction
    | remLongInstruction
    | andLongInstruction
    | orLongInstruction
    | xorLongInstruction
    | shlLongInstruction
    | shrLongInstruction
    | ushrLongInstruction

    | addFloatInstruction
    | subFloatInstruction
    | mulFloatInstruction
    | divFloatInstruction
    | remFloatInstruction

    | addDoubleInstruction
    | subDoubleInstruction
    | mulDoubleInstruction
    | divDoubleInstruction
    | remDoubleInstruction

    | addIntLit16Instruction
    | mulIntLit16Instruction
    | divIntLit16Instruction
    | remIntLit16Instruction
    | andIntLit16Instruction
    | orIntLit16Instruction
    | xorIntLit16Instruction

    | addIntLit8Instruction
    | rsubIntLit8Instruction
    | mulIntLit8Instruction
    | divIntLit8Instruction
    | remIntLit8Instruction
    | andIntLit8Instruction
    | orIntLit8Instruction
    | xorIntLit8Instruction
    | shlIntLit8Instruction
    | shrIntLit8Instruction
    | ushrIntLit8Instruction
    ;

instruction:
    ternaryInstruction
    | binaryInstruction

    | returnVoidInstruction
    | nopInstruction

    | gotoInstruction
    | goto16Instruction
    | goto32Instruction

    | moveResultInstruction
    | moveResultWideInstruction
    | moveResultObjectInstruction
    | moveExceptionInstruction

    | returnInstruction
    | returnWideInstruction
    | returnObjectInstruction

    | monitorEnterInstruction
    | monitorExitInstruction
    | throwInstruction
    ;

methodInvocationTarget:         referenceOrArrayType ARROW methodSignature;

fieldInvocationTarget:          referenceOrArrayType ARROW fieldNameAndType;

// Fields and methods

fieldName:                  SUB? identifier;  // Allow -prefixed field names

fieldType:                  anyType;

fieldNameAndType:           fieldName COLON fieldType;

fieldDirective:             FIELD_DIRECTIVE fieldModifier* fieldNameAndType (ASSIGN assignableValue)? fieldBody? FIELD_END_DIRECTIVE?;

fieldBody:                  annotationDirective+;

className:                  referenceType;

classDirective:             CLASS_DIRECTIVE classModifier* className;

superName:                  referenceType;

superDirective:             SUPER_DIRECTIVE superName;

sourceName:                 stringLiteral;

sourceDirective:            SOURCE_DIRECTIVE sourceName;

methodName:
        IDENTIFIER
        | VOID_TYPE
        | BOOLEAN_TYPE
        | BYTE_TYPE
        | SHORT_TYPE
        | CHAR_TYPE
        | INT_TYPE
        | LONG_TYPE
        | FLOAT_TYPE
        | DOUBLE_TYPE
        | CONSTRUCTOR
        | ANNOTATION
        | BRIDGE
        | NATIVE
        | VARARGS
        | SYNTHETIC
        | PUBLIC
        | PRIVATE
        | PROTECTED
        | FINAL
        | STATIC
        | ABSTRACT
        | ENUM
        | INTERFACE
        | TRANSIENT
        | VOLATILE
        | OP_MOVE
        // Single token only - no + operator
    ;

methodIdentifier:           SUB? methodName | LT methodName GT;  // Allow -deprecated_name style

methodReturnType:           anyType;

methodParameterType:        (nonVoidType | methodParameterLiteral);

methodArguments:            methodParameterType+;

methodSignature:            methodIdentifier LPAREN methodArguments? RPAREN methodReturnType;

methodDeclaration:          methodModifier* methodSignature;

annotationScope:            IDENTIFIER;

annotationType:             referenceType;

enumFieldReference:         ENUM_DIRECTIVE fieldInvocationTarget;

methodReference:            referenceOrArrayType ARROW methodSignature;

subannotation:              SUBANNOTATION_DIRECTIVE annotationType annotationField* SUBANNOTATION_END_DIRECTIVE;

annotationFieldValue:       (assignableValue | methodReference | referenceType | enumFieldReference | subannotation | annotationDirective);

annotationValueScoped:      LBRACE (annotationFieldValue (COMMA annotationFieldValue)*)? RBRACE;

annotationField:            fieldName ASSIGN (annotationFieldValue|annotationValueScoped);

annotationDirective:        ANNOTATION_DIRECTIVE annotationScope annotationType annotationField* ANNOTATION_END_DIRECTIVE;

locaDirectiveVariableName:  stringLiteral;

localDirectiveType:         nonVoidType;

localDirectiveGenericHint:  stringLiteral;

localDirectiveRegister:     registerIdentifier;

localDirective:             LOCAL_DIRECTIVE localDirectiveRegister COMMA locaDirectiveVariableName (COLON localDirectiveType)? (COMMA localDirectiveGenericHint)?;

localEndDirective:          LOCAL_END_DIRECTIVE registerIdentifier;

localRestartDirective:      RESTART_LOCAL_DIRECTIVE registerIdentifier;

prologueDirective:          PROLOGUE_DIRECTIVE;

epilogueDirective:          EPILOGUE_DIRECTIVE;

lineLabel:                  label;

methodBodyStatement:
    registersDirective
    | localsDirective
    | paramDirective
    | lineDirective
    | prologueDirective
    | epilogueDirective
    | instruction
    | lineLabel
    | catchDirective
    | catchAllDirective
    | annotationDirective
    | localDirective
    | localEndDirective
    | localRestartDirective
    | packedSwitchDirective
    | arrayDataDirective
    | sparseSwitchDirective
    ;

methodBody:                 methodBodyStatement+;

packedSwitchIdent:          numericLiteral;

packedSwitchDirectiveLabel: label;

packedSwitchDirectiveLabels:packedSwitchDirectiveLabel+;

packedSwitchDirective:      PACKED_SWITCH_DIRECTIVE packedSwitchIdent packedSwitchDirectiveLabels? PACKED_SWITCH_END_DIRECTIVE;

methodDirective:            METHOD_DIRECTIVE methodDeclaration methodBody? METHOD_END_DIRECTIVE;

registersDirective:         REGISTERS_DIRECTIVE numericLiteral;

localsDirective:            LOCALS_DIRECTIVE numericLiteral;

simpleParamDirective:       COMMA stringLiteral;

extendedParamDirective:     annotationDirective* PARAM_END_DIRECTIVE;

paramDirective:             PARAM_DIRECTIVE registerIdentifier (extendedParamDirective | simpleParamDirective);

lineDirective:              LINE_DIRECTIVE numericLiteral;

catchFromLabel:             label;

catchToLabel:               label;

catchGotoLabel:             label;

catchExceptionType:         referenceType;

catchDirective:             CATCH_DIRECTIVE catchExceptionType LBRACE catchFromLabel DOT DOT catchToLabel RBRACE catchGotoLabel;

catchAllDirective:          CATCHALL_DIRECTIVE LBRACE catchFromLabel DOT DOT catchToLabel RBRACE catchGotoLabel;

arrayDataDirective:         ARRAY_DATA_DIRECTIVE numericLiteral arrayDataEntry* ARRAY_DATA_END_DIRECTIVE;

arrayDataEntry:             numericLiteral IDENTIFIER?;

sparseSwitchDirectiveValue: numericLiteral ARROW label;

sparseSwitchDirective:      SPARSE_SWITCH_DIRECTIVE sparseSwitchDirectiveValue* SPARSE_SWITCH_END_DIRECTIVE;

implementsDirective:        IMPLEMENTS_DIRECTIVE referenceType;

statement:
    classDirective
    | superDirective
    | sourceDirective
    | implementsDirective
    | fieldDirective
    | methodDirective
    ;

parse:
    (statement | annotationDirective)+
    ;