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

lexer grammar SmaliLexer;

// Types
// TypeNameComponent allows hyphen at start (for -FileSystem, etc) since it's in context of L.../...;
QUALIFIED_TYPE_NAME:        ('L' TypeNameComponent (SLASH TypeNameComponent)* ';') | ('L' (TypeNameComponent (SLASH TypeNameComponent)* SLASH)? 'package-info;');
VOID_TYPE:                  'V';
BOOLEAN_TYPE:               'Z';
BYTE_TYPE:                  'B';
SHORT_TYPE:                 'S';
CHAR_TYPE:                  'C';
INT_TYPE:                   'I';
LONG_TYPE:                  'J';
FLOAT_TYPE:                 'F';
DOUBLE_TYPE:                'D';

COMPOUND_METHOD_ARG_LITERAL:
    (
        BOOLEAN_TYPE
        | BYTE_TYPE
        | SHORT_TYPE
        | CHAR_TYPE
        | INT_TYPE
        | LONG_TYPE
        | FLOAT_TYPE
        | DOUBLE_TYPE
    )+ QUALIFIED_TYPE_NAME
    ;

LBRACK:                     '[';
RBRACK:                     ']';
LPAREN:                     '(';
RPAREN:                     ')';
LBRACE:                     '{';
RBRACE:                     '}';
COLON:                      ':';
ASSIGN:                     '=';
DOT:                        '.';
SUB:                        '-';
COMMA:                      ',';
SLASH:                      '/';
LT:                         '<';
GT:                         '>';
ARROW:                      '->';
SEMI:                       ';';
// Directives

METHOD_DIRECTIVE:               '.method';
METHOD_END_DIRECTIVE:           '.end method';
CLASS_DIRECTIVE:                '.class';
SOURCE_DIRECTIVE:               '.source';
SUPER_DIRECTIVE:                '.super';
IMPLEMENTS_DIRECTIVE:           '.implements';
FIELD_DIRECTIVE:                '.field';
FIELD_END_DIRECTIVE:            '.end field';
REGISTERS_DIRECTIVE:            '.registers';
LOCALS_DIRECTIVE:               '.locals';
PARAM_DIRECTIVE:                '.param';
LINE_DIRECTIVE:                 '.line';
CATCH_DIRECTIVE:                '.catch';
CATCHALL_DIRECTIVE:             '.catchall';
ANNOTATION_DIRECTIVE:           '.annotation';
ANNOTATION_END_DIRECTIVE:       '.end annotation';
PROLOGUE_DIRECTIVE:             '.prologue';
EPILOGUE_DIRECTIVE:             '.epilogue';
LOCAL_DIRECTIVE:                '.local';
LOCAL_END_DIRECTIVE:            '.end local';
RESTART_LOCAL_DIRECTIVE:        '.restart local';
PACKED_SWITCH_DIRECTIVE:        '.packed-switch';
PACKED_SWITCH_END_DIRECTIVE:    '.end packed-switch';
ARRAY_DATA_DIRECTIVE:           '.array-data';
ARRAY_DATA_END_DIRECTIVE:       '.end array-data';
SPARSE_SWITCH_DIRECTIVE:        '.sparse-switch';
SPARSE_SWITCH_END_DIRECTIVE:    '.end sparse-switch';
PARAM_END_DIRECTIVE:            '.end param';
ENUM_DIRECTIVE:                 '.enum';
SUBANNOTATION_DIRECTIVE:        '.subannotation';
SUBANNOTATION_END_DIRECTIVE:    '.end subannotation';

// Modifiers

PUBLIC:                         'public';
PRIVATE:                        'private';
PROTECTED:                      'protected';
FINAL:                          'final';
ANNOTATION:                     'annotation';
STATIC:                         'static';
SYNTHETIC:                      'synthetic';
CONSTRUCTOR:                    'constructor';
ABSTRACT:                       'abstract';
ENUM:                           'enum';
INTERFACE:                      'interface';
TRANSIENT:                      'transient';
BRIDGE:                         'bridge';
DECLARED_SYNCHRONIZED:          'declared-synchronized';
VOLATILE:                       'volatile';
STRICTFP:                       'strictfp';
VARARGS:                        'varargs';
NATIVE:                         'native';

// Instructions
OP_NOP:                         'nop';

OP_MOVE:                        'move';
OP_MOVE_FROM16:                 'move/from16';
OP_MOVE_16:                     'move/16';
OP_MOVE_WIDE:                   'move-wide';
OP_MOVE_WIDE_FROM16:            'move-wide/from16';
OP_MOVE_WIDE_16:                'move-wide/16';
OP_MOVE_OBJECT:                 'move-object';
OP_MOVE_OBJECT_FROM16:          'move-object/from16';
OP_MOVE_OBJECT_16:              'move-object/16';

OP_MOVE_RESULT:                 'move-result';
OP_MOVE_RESULT_WIDE:            'move-result-wide';
OP_MOVE_RESULT_OBJECT:          'move-result-object';
OP_MOVE_EXCEPTION:              'move-exception';

OP_RETURN_VOID:                 'return-void';

OP_RETURN:                      'return';
OP_RETURN_WIDE:                 'return-wide';
OP_RETURN_OBJECT:               'return-object';

OP_CONST_4:                     'const/4';
OP_CONST_16:                    'const/16';
OP_CONST:                       'const';
OP_CONST_HIGH16:                'const/high16';
OP_CONST_WIDE_16:               'const-wide/16';
OP_CONST_WIDE_32:               'const-wide/32';
OP_CONST_WIDE:                  'const-wide';
OP_CONST_WIDE_HIGH16:           'const-wide/high16';

OP_CONST_STRING:                'const-string';
OP_CONST_STRING_JUMBO:          'const-string/jumbo';
OP_CONST_CLASS:                 'const-class';

OP_MONITOR_ENTER:               'monitor-enter';
OP_MONITOR_EXIT:                'monitor-exit';

OP_CHECK_CAST:                  'check-cast';
OP_INSTANCE_OF:                 'instance-of';
OP_ARRAY_LENGTH:                'array-length';
OP_NEW_INSTANCE:                'new-instance';

OP_NEW_ARRAY:                   'new-array';

OP_FILLED_NEW_ARRAY:            'filled-new-array';
OP_FILLED_NEW_ARRAY_RANGE:      'filled-new-array/range';
OP_FILL_ARRAY_DATA:             'fill-array-data';

OP_THROW:                       'throw';
OP_GOTO:                        'goto';
OP_GOTO_16:                     'goto/16';
OP_GOTO_32:                     'goto/32';

OP_CMPL_FLOAT:                  'cmpl-float';
OP_CMPG_FLOAT:                  'cmpg-float';
OP_CMPL_DOUBLE:                 'cmpl-double';
OP_CMPG_DOUBLE:                 'cmpg-double';
OP_CMP_LONG:                    'cmp-long';

OP_IF_EQ:                       'if-eq';
OP_IF_NE:                       'if-ne';
OP_IF_LT:                       'if-lt';
OP_IF_GE:                       'if-ge';
OP_IF_GT:                       'if-gt';
OP_IF_LE:                       'if-le';

OP_IF_EQZ:                      'if-eqz';
OP_IF_NEZ:                      'if-nez';
OP_IF_LTZ:                      'if-ltz';
OP_IF_GEZ:                      'if-gez';
OP_IF_GTZ:                      'if-gtz';
OP_IF_LEZ:                      'if-lez';

OP_AGET:                        'aget';
OP_AGET_WIDE:                   'aget-wide';
OP_AGET_OBJECT:                 'aget-object';
OP_AGET_BOOLEAN:                'aget-boolean';
OP_AGET_BYTE:                   'aget-byte';
OP_AGET_CHAR:                   'aget-char';
OP_AGET_SHORT:                  'aget-short';

OP_APUT:                        'aput';
OP_APUT_WIDE:                   'aput-wide';
OP_APUT_OBJECT:                 'aput-object';
OP_APUT_BOOLEAN:                'aput-boolean';
OP_APUT_BYTE:                   'aput-byte';
OP_APUT_CHAR:                   'aput-char';
OP_APUT_SHORT:                  'aput-short';

OP_IGET:                        'iget';
OP_IGET_WIDE:                   'iget-wide';
OP_IGET_OBJECT:                 'iget-object';
OP_IGET_BOOLEAN:                'iget-boolean';
OP_IGET_BYTE:                   'iget-byte';
OP_IGET_CHAR:                   'iget-char';
OP_IGET_SHORT:                  'iget-short';

OP_IPUT:                        'iput';
OP_IPUT_WIDE:                   'iput-wide';
OP_IPUT_OBJECT:                 'iput-object';
OP_IPUT_BOOLEAN:                'iput-boolean';
OP_IPUT_BYTE:                   'iput-byte';
OP_IPUT_CHAR:                   'iput-char';
OP_IPUT_SHORT:                  'iput-short';

OP_SGET:                        'sget';
OP_SGET_WIDE:                   'sget-wide';
OP_SGET_OBJECT:                 'sget-object';
OP_SGET_BOOLEAN:                'sget-boolean';
OP_SGET_BYTE:                   'sget-byte';
OP_SGET_CHAR:                   'sget-char';
OP_SGET_SHORT:                  'sget-short';

OP_SPUT:                        'sput';
OP_SPUT_WIDE:                   'sput-wide';
OP_SPUT_OBJECT:                 'sput-object';
OP_SPUT_BOOLEAN:                'sput-boolean';
OP_SPUT_BYTE:                   'sput-byte';
OP_SPUT_CHAR:                   'sput-char';
OP_SPUT_SHORT:                  'sput-short';

OP_INVOKE_VIRTUAL:              'invoke-virtual';
OP_INVOKE_SUPER:                'invoke-super';
OP_INVOKE_DIRECT:               'invoke-direct';
OP_INVOKE_STATIC:               'invoke-static';
OP_INVOKE_INTERFACE:            'invoke-interface';

OP_INVOKE_VIRTUAL_RANGE:        'invoke-virtual/range';
OP_INVOKE_SUPER_RANGE:          'invoke-super/range';
OP_INVOKE_DIRECT_RANGE:         'invoke-direct/range';
OP_INVOKE_STATIC_RANGE:         'invoke-static/range';
OP_INVOKE_INTERFACE_RANGE:      'invoke-interface/range';

OP_NEG_INT:                     'neg-int';
OP_NOT_INT:                     'not-int';
OP_NEG_LONG:                    'neg-long';
OP_NOT_LONG:                    'not-long';
OP_NEG_FLOAT:                   'neg-float';
OP_NEG_DOUBLE:                  'neg-double';
OP_INT_TO_LONG:                 'int-to-long';
OP_INT_TO_FLOAT:                'int-to-float';
OP_INT_TO_DOUBLE:               'int-to-double';
OP_LONG_TO_INT:                 'long-to-int';
OP_LONG_TO_FLOAT:               'long-to-float';
OP_LONG_TO_DOUBLE:              'long-to-double';
OP_FLOAT_TO_INT:                'float-to-int';
OP_FLOAT_TO_LONG:               'float-to-long';
OP_FLOAT_TO_DOUBLE:             'float-to-double';
OP_DOUBLE_TO_INT:               'double-to-int';
OP_DOUBLE_TO_LONG:              'double-to-long';
OP_DOUBLE_TO_FLOAT:             'double-to-float';
OP_INT_TO_BYTE:                 'int-to-byte';
OP_INT_TO_CHAR:                 'int-to-char';
OP_INT_TO_SHORT:                'int-to-short';

OP_ADD_INT:                     'add-int';
OP_SUB_INT:                     'sub-int';
OP_MUL_INT:                     'mul-int';
OP_DIV_INT:                     'div-int';
OP_REM_INT:                     'rem-int';
OP_AND_INT:                     'and-int';
OP_OR_INT:                      'or-int';
OP_XOR_INT:                     'xor-int';
OP_SHL_INT:                     'shl-int';
OP_SHR_INT:                     'shr-int';
OP_USHR_INT:                    'ushr-int';
OP_ADD_LONG:                    'add-long';
OP_SUB_LONG:                    'sub-long';
OP_MUL_LONG:                    'mul-long';
OP_DIV_LONG:                    'div-long';
OP_REM_LONG:                    'rem-long';
OP_AND_LONG:                    'and-long';
OP_OR_LONG:                     'or-long';
OP_XOR_LONG:                    'xor-long';
OP_SHL_LONG:                    'shl-long';
OP_SHR_LONG:                    'shr-long';
OP_USHR_LONG:                   'ushr-long';
OP_ADD_FLOAT:                   'add-float';
OP_SUB_FLOAT:                   'sub-float';
OP_MUL_FLOAT:                   'mul-float';
OP_DIV_FLOAT:                   'div-float';
OP_REM_FLOAT:                   'rem-float';
OP_ADD_DOUBLE:                  'add-double';
OP_SUB_DOUBLE:                  'sub-double';
OP_MUL_DOUBLE:                  'mul-double';
OP_DIV_DOUBLE:                  'div-double';
OP_REM_DOUBLE:                  'rem-double';

OP_ADD_INT_2ADDR:               'add-int/2addr';
OP_SUB_INT_2ADDR:               'sub-int/2addr';
OP_MUL_INT_2ADDR:               'mul-int/2addr';
OP_DIV_INT_2ADDR:               'div-int/2addr';
OP_REM_INT_2ADDR:               'rem-int/2addr';
OP_AND_INT_2ADDR:               'and-int/2addr';
OP_OR_INT_2ADDR:                'or-int/2addr';
OP_XOR_INT_2ADDR:               'xor-int/2addr';
OP_SHL_INT_2ADDR:               'shl-int/2addr';
OP_SHR_INT_2ADDR:               'shr-int/2addr';
OP_USHR_INT_2ADDR:              'ushr-int/2addr';
OP_ADD_LONG_2ADDR:              'add-long/2addr';
OP_SUB_LONG_2ADDR:              'sub-long/2addr';
OP_MUL_LONG_2ADDR:              'mul-long/2addr';
OP_DIV_LONG_2ADDR:              'div-long/2addr';
OP_REM_LONG_2ADDR:              'rem-long/2addr';
OP_AND_LONG_2ADDR:              'and-long/2addr';
OP_OR_LONG_2ADDR:               'or-long/2addr';
OP_XOR_LONG_2ADDR:              'xor-long/2addr';
OP_SHL_LONG_2ADDR:              'shl-long/2addr';
OP_SHR_LONG_2ADDR:              'shr-long/2addr';
OP_USHR_LONG_2ADDR:             'ushr-long/2addr';
OP_ADD_FLOAT_2ADDR:             'add-float/2addr';
OP_SUB_FLOAT_2ADDR:             'sub-float/2addr';
OP_MUL_FLOAT_2ADDR:             'mul-float/2addr';
OP_DIV_FLOAT_2ADDR:             'div-float/2addr';
OP_REM_FLOAT_2ADDR:             'rem-float/2addr';
OP_ADD_DOUBLE_2ADDR:            'add-double/2addr';
OP_SUB_DOUBLE_2ADDR:            'sub-double/2addr';
OP_MUL_DOUBLE_2ADDR:            'mul-double/2addr';
OP_DIV_DOUBLE_2ADDR:            'div-double/2addr';
OP_REM_DOUBLE_2ADDR:            'rem-double/2addr';

OP_ADD_INT_LIT16:               'add-int/lit16';
OP_RSUB_INT:                    'rsub-int';
OP_MUL_INT_LIT16:               'mul-int/lit16';
OP_DIV_INT_LIT16:               'div-int/lit16';
OP_REM_INT_LIT16:               'rem-int/lit16';
OP_AND_INT_LIT16:               'and-int/lit16';
OP_OR_INT_LIT16:                'or-int/lit16';
OP_XOR_INT_LIT16:               'xor-int/lit16';
OP_ADD_INT_LIT8:                'add-int/lit8';
OP_RSUB_INT_LIT8:               'rsub-int/lit8';
OP_MUL_INT_LIT8:                'mul-int/lit8';
OP_DIV_INT_LIT8:                'div-int/lit8';
OP_REM_INT_LIT8:                'rem-int/lit8';
OP_AND_INT_LIT8:                'and-int/lit8';
OP_OR_INT_LIT8:                 'or-int/lit8';
OP_XOR_INT_LIT8:                'xor-int/lit8';
OP_SHL_INT_LIT8:                'shl-int/lit8';
OP_SHR_INT_LIT8:                'shr-int/lit8';
OP_USHR_INT_LIT8:               'ushr-int/lit8';

OP_INVOKE_POLYMORPHIC:          'invoke-polymorphic';
OP_INVOKE_POLYMORPHIC_RANGE:    'invoke-polymorphic/range';
OP_INVOKE_CUSTOM:               'invoke-custom';
OP_INVOKE_CUSTOM_RANGE:         'invoke-custom/range';
OP_CONST_METHOD_HANDLE:         'const-method-handle';
OP_CONST_METHOD_TYPE:           'const-method-type';

OP_PACKED_SWITCH:               'packed-switch';
OP_SPARSE_SWITCH:               'sparse-switch';

// Literals

DECIMAL_LITERAL:    ('0' | [1-9] (Digits? | '_'+ Digits)) [lL]?;
HEX_LITERAL:        '0' [xX] [0-9a-fA-F] ([0-9a-fA-F_]* [0-9a-fA-F])? [lL]?;
OCT_LITERAL:        '0' '_'* [0-7] ([0-7_]* [0-7])? [lL]?;
BINARY_LITERAL:     '0' [bB] [01] ([01_]* [01])? [lL]?;

FLOAT_LITERAL:      (Digits '.' Digits? | '.' Digits) ExponentPart? [fFdD]?
             |       Digits (ExponentPart [fFdD]? | [fFdD])
             ;

HEX_FLOAT_LITERAL:  '0' [xX] (HexDigits '.'? | HexDigits? '.' HexDigits) [pP] [+-]? Digits [fFdD]?;

// IEEE 754 special values (Fix for BUG-004)
FLOAT_SPECIAL:      'Infinity' [fF]? | 'NaN' [fF]?;

BOOL_LITERAL:       'true'
            |       'false'
            ;

NULL_LITERAL:       'null';

CHAR_LITERAL:       '\'' (CHARACTER) '\'';

STRING_LITERAL:     '"' (CHARACTER)* '"';

IDENTIFIER:         SimpleName;

fragment CHARACTER: (~['\\\r\n] | EscapeSequence);

WS  :  [ \t\r\n\u000C]+ -> skip
    ;

// Comments

//COMMENT
//    :   '/*' .*? '*/' -> channel(HIDDEN)
//    ;

LINE_COMMENT
    :   '#' ~[\r\n]* -> channel(HIDDEN)
    ;

// Fragment rules
fragment SimpleName:        LetterNoHyphen (LetterOrDigit)*;  // Start with non-hyphen to allow SUB token before literals

// Fragment rules

fragment ExponentPart
    : [eE] [+-]? Digits
    ;

fragment EscapeSequence
    : '\\' [btnfr"'\\]
    | '\\' ([0-3]? [0-7])? [0-7]
    | '\\' 'u'+ HexDigit HexDigit HexDigit HexDigit
    ;
fragment HexDigits
    : HexDigit ((HexDigit | '_')* HexDigit)?
    ;
fragment HexDigit
    : [0-9a-fA-F]
    ;
fragment Digits
    : [0-9] ([0-9_]* [0-9])?
    ;
fragment LetterOrDigit
    : Letter
    | [0-9]
    ;
fragment LetterNoHyphen
    : [a-zA-Z$_] // these are the "java letters" below 0x7F (no hyphen at start)
    | ~[\u0000-\u007F\uD800-\uDBFF] // covers all characters above 0x7F which are not a surrogate
    | [\uD800-\uDBff] [\uDC00-\uDFFF] // covers UTF-16 surrogate pairs encodings for U+10000 to U+10FFFF
    ;
fragment Letter
    : [a-zA-Z$_\-] // these are the "java letters" below 0x7F, plus hyphen for Smali method names
    | ~[\u0000-\u007F\uD800-\uDBFF] // covers all characters above 0x7F which are not a surrogate
    | [\uD800-\uDBff] [\uDC00-\uDFFF] // covers UTF-16 surrogate pairs encodings for U+10000 to U+10FFFF
    ;

// TypeNameComponent allows hyphen and digit starts for obfuscated class names like 2K, -FileSystem
fragment TypeNameComponent
    : LetterOrDigit (LetterOrDigit)*  // LetterOrDigit includes Letter (hyphen, letters) + digits
    ;
