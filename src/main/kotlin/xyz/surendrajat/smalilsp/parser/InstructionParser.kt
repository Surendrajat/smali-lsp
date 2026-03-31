package xyz.surendrajat.smalilsp.parser

import xyz.surendrajat.smalilsp.core.*
import org.eclipse.lsp4j.Position

/**
 * Instruction parser for navigation-critical instructions.
 * 
 * This parses key instructions from method bodies to enable:
 * - Click on invoke-virtual → goto method definition
 * - Click on iget/iput → goto field definition
 * - Click on new-instance → goto class definition
 * 
 * Implementation: Regex-based parsing of common patterns.
 * This is pragmatic and sufficient for navigation.
 * 
 * Full ANTLR-based instruction parsing would require extending the grammar
 * significantly, which is not needed for basic LSP navigation.
 */
object InstructionParser {
    
    // Patterns for key instructions
    private val INVOKE_PATTERN = Regex("""(invoke-\w+(?:/range)?)\s+\{[^}]*\},\s*([^-]+)->([^(]+)(\([^)]*\).+)""")
    private val FIELD_ACCESS_PATTERN = Regex("""([is][gs]et[^-]*)\s+v\d+,\s*([^-]+)->([^:]+):(.+)""")
    private val TYPE_PATTERN = Regex("""(new-instance|check-cast|instance-of)\s+v\d+,\s*(.+)""")
    
    /**
     * Parse instructions from method body text.
     * Returns list of Instruction objects with ranges.
     */
    fun parseInstructions(methodBody: String, startLine: Int): List<Instruction> {
        val instructions = mutableListOf<Instruction>()
        val lines = methodBody.lines()
        
        lines.forEachIndexed { index, line ->
            val lineNum = startLine + index
            val trimmed = line.trim()
            
            // Parse invoke instructions
            INVOKE_PATTERN.find(trimmed)?.let { match ->
                val opcode = match.groupValues[1]
                val className = match.groupValues[2]
                val methodName = match.groupValues[3]
                val descriptor = match.groupValues[4]
                
                instructions.add(InvokeInstruction(
                    opcode = opcode,
                    className = className,
                    methodName = methodName,
                    descriptor = descriptor,
                    range = range(lineNum, 0, lineNum, line.length)
                ))
            }
            
            // Parse field access instructions
            FIELD_ACCESS_PATTERN.find(trimmed)?.let { match ->
                val opcode = match.groupValues[1]
                val className = match.groupValues[2]
                val fieldName = match.groupValues[3]
                val fieldType = match.groupValues[4]
                
                instructions.add(FieldAccessInstruction(
                    opcode = opcode,
                    className = className,
                    fieldName = fieldName,
                    fieldType = fieldType,
                    range = range(lineNum, 0, lineNum, line.length)
                ))
            }
            
            // Parse type instructions
            TYPE_PATTERN.find(trimmed)?.let { match ->
                val opcode = match.groupValues[1]
                val className = match.groupValues[2]
                
                instructions.add(TypeInstruction(
                    opcode = opcode,
                    className = className,
                    range = range(lineNum, 0, lineNum, line.length)
                ))
            }
        }
        
        return instructions
    }
    
    /**
     * Extract method body from full file content.
     * Returns the text between .method and .end method
 directives.
     */
    fun extractMethodBody(fileContent: String, methodName: String, descriptor: String): Pair<String, Int>? {
        val lines = fileContent.lines()
        val methodPattern = Regex(""".method\s+.*\s+${Regex.escape(methodName)}${Regex.escape(descriptor)}""")
        
        var startIndex = -1
        var endIndex = -1
        var startLine = 0
        
        for ((index, line) in lines.withIndex()) {
            if (startIndex == -1 && methodPattern.containsMatchIn(line)) {
                startIndex = index
                startLine = index + 1  // Start after .method directive
            } else if (startIndex != -1 && line.trim().startsWith(".end method")) {
                endIndex = index
                break
            }
        }
        
        return if (startIndex != -1 && endIndex != -1) {
            val body = lines.subList(startLine, endIndex).joinToString("\n")
            Pair(body, startLine)
        } else {
            null
        }
    }
}
