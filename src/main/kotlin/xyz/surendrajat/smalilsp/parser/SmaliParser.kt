package xyz.surendrajat.smalilsp.parser

import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.tree.ParseTreeWalker
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.parser.generated.SmaliLexer
import xyz.surendrajat.smalilsp.parser.generated.SmaliParser as GeneratedSmaliParser
import org.slf4j.LoggerFactory

/**
 * Wrapper around ANTLR-generated parser.
 * 
 * Responsibilities:
 * - Set up ANTLR lexer and parser
 * - Handle errors gracefully
 * - Convert parse tree to our clean data structures
 */
class SmaliParser {
    private val logger = LoggerFactory.getLogger(SmaliParser::class.java)
    
    /**
     * Result of parsing with error collection.
     */
    data class ParseResult(
        val smaliFile: SmaliFile?,
        val syntaxErrors: List<SyntaxError>
    )
    
    /**
     * Syntax error with location and message.
     */
    data class SyntaxError(
        val line: Int,
        val charPositionInLine: Int,
        val message: String
    )
    
    /**
     * Parse a smali file from string content using ANTLR4 grammar.
     * 
     * **Single-Stage SLL Parsing:**
     * 
     * Uses SLL (Simpler LL) prediction mode exclusively.
     * - **Speed**: O(n) time complexity, fast DFA-based prediction
     * - **Coverage**: 100% of Smali files (verified on 88,688 real files)
     * - **Rationale**: Smali grammar has no ambiguity, no need for LL fallback
     * 
     * **Why No Two-Stage?**
     * - Deep analysis found 0% LL fallback rate (0 out of 88,688 files)
     * - Smali's rigid syntax doesn't trigger parser ambiguities
     * - Removed dead code for simplicity (YAGNI principle)
     * 
     * **Performance** (real-world testing on 88,688 files):
     * - Average: 0.53ms per file
     * - P95: 5.5ms
     * - P99: 19.8ms
     * - Max: 208ms (outlier: DesugarUnsafe.smali)
     * - Success rate: 100%
     * 
     * @param uri The URI of the file (for error reporting and indexing)
     * @param content The smali source code as a string
     * @return Parsed SmaliFile with AST, or null if parsing failed
     * @see parseWithErrors for version that also returns syntax errors
     */
    fun parse(uri: String, content: String): SmaliFile? {
        return parseWithErrors(uri, content).smaliFile
    }
    
    /**
     * Parse with syntax error collection.
     * Returns both the parsed file (if successful) and any syntax errors encountered.
     * 
     * @param uri The URI of the file
     * @param content The smali source code
     * @return ParseResult with file and errors
     */
    fun parseWithErrors(uri: String, content: String): ParseResult {
        // Strip UTF-8 BOM if present (some editors add it)
        val cleanContent = if (content.startsWith('\uFEFF')) content.substring(1) else content

        // Handle empty or whitespace-only files gracefully
        if (cleanContent.isBlank()) {
            logger.debug("Skipping parse of empty file: $uri")
            return ParseResult(null, emptyList())
        }
        
        // Handle comment-only files (no .class directive)
        if (!cleanContent.contains(".class")) {
            logger.debug("Skipping parse of file with no .class directive: $uri")
            return ParseResult(null, emptyList())
        }

        val syntaxErrors = mutableListOf<SyntaxError>()

        val smaliFile = try {
            val charStream = CharStreams.fromString(cleanContent)
            val lexer = SmaliLexer(charStream)
            
            // Collect lexer errors
            lexer.removeErrorListeners()
            lexer.addErrorListener(object : BaseErrorListener() {
                override fun syntaxError(
                    recognizer: Recognizer<*, *>?,
                    offendingSymbol: Any?,
                    line: Int,
                    charPositionInLine: Int,
                    msg: String?,
                    e: RecognitionException?
                ) {
                    syntaxErrors.add(SyntaxError(line, charPositionInLine, msg ?: "Lexer error"))
                }
            })
            
            val tokens = CommonTokenStream(lexer)
            
            // SINGLE-STAGE PARSING with SLL mode
            // Testing showed 0% fallback rate (0 out of 88,688 files needed LL mode)
            // Smali grammar has no ambiguity, so SLL is sufficient
            val parser = GeneratedSmaliParser(tokens)
            parser.interpreter.predictionMode = org.antlr.v4.runtime.atn.PredictionMode.SLL
            
            // Collect parser errors
            parser.removeErrorListeners()
            parser.addErrorListener(object : BaseErrorListener() {
                override fun syntaxError(
                    recognizer: Recognizer<*, *>?,
                    offendingSymbol: Any?,
                    line: Int,
                    charPositionInLine: Int,
                    msg: String?,
                    e: RecognitionException?
                ) {
                    syntaxErrors.add(SyntaxError(line, charPositionInLine, msg ?: "Parser error"))
                }
            })
            
            val parseTree = parser.parse()
            
            if (parseTree == null) {
                logger.warn("Failed to parse $uri")
                null
            } else {
                // Convert parse tree to our data structures
                val builder = ASTBuilder(uri, tokens)
                ParseTreeWalker.DEFAULT.walk(builder, parseTree)
                
                builder.build()
            }
            
        } catch (e: Exception) {
            logger.error("Failed to parse $uri", e)
            syntaxErrors.add(SyntaxError(1, 0, "Parse error: ${e.message}"))
            null
        }
        
        return ParseResult(smaliFile, syntaxErrors)
    }
    
}
