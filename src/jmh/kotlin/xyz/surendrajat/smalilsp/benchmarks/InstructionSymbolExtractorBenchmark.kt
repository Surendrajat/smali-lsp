package xyz.surendrajat.smalilsp.benchmarks

import org.openjdk.jmh.annotations.*
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.FieldAccessInstruction
import xyz.surendrajat.smalilsp.core.TypeInstruction
import xyz.surendrajat.smalilsp.core.range
import xyz.surendrajat.smalilsp.util.InstructionSymbolExtractor
import xyz.surendrajat.smalilsp.util.InstructionSymbolExtractorRegex
import java.util.concurrent.TimeUnit

/**
 * JMH Benchmarks to compare String operations vs Regex implementations
 * 
 * Run with: ./gradlew jmh
 * 
 * Results will show:
 * - Throughput (ops/sec) - higher is better
 * - Average time (ns/op) - lower is better
 * - Memory allocation - lower is better
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
open class InstructionSymbolExtractorBenchmark {
    
    // Test data - simple invoke
    private lateinit var simpleInvokeInstruction: InvokeInstruction
    private val simpleInvokeLine = "    invoke-virtual {v0}, Ljava/lang/Object;->toString()Ljava/lang/String;"
    private val simpleInvokeCursorPos = 45  // On "toString"
    
    // Test data - complex invoke with multiple same-type parameters
    private lateinit var complexInvokeInstruction: InvokeInstruction
    private val complexInvokeLine = "    invoke-virtual {v0, v1, v2}, Ljava/lang/StringBuilder;->concat(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
    private val complexInvokeCursorPos = 85  // On second String parameter
    
    // Test data - field access
    private lateinit var fieldAccessInstruction: FieldAccessInstruction
    private val fieldAccessLine = "    iget-object v0, v1, Ljava/lang/String;->value:[C"
    private val fieldAccessCursorPos = 36  // On field name "value"
    
    // Test data - type instruction
    private lateinit var typeInstruction: TypeInstruction
    private val typeLine = "    new-instance v0, Ljava/util/ArrayList;"
    private val typeCursorPos = 28  // On class name
    
    // Test data - worst case: very long descriptor
    private lateinit var worstCaseInstruction: InvokeInstruction
    private val worstCaseLine = "    invoke-static {v0, v1, v2, v3, v4, v5}, Lcom/example/Utils;->process(Ljava/lang/String;Ljava/util/List;Ljava/util/Map;Ljava/io/File;Ljava/net/URL;[Ljava/lang/Object;)Ljava/lang/String;"
    private val worstCaseCursorPos = 120  // On last parameter type
    
    @Setup
    fun setup() {
        // Initialize test instructions
        simpleInvokeInstruction = InvokeInstruction(
            opcode = "invoke-virtual",
            className = "Ljava/lang/Object;",
            methodName = "toString",
            descriptor = "()Ljava/lang/String;",
            range = range(0, 0, 0, 80)
        )
        
        complexInvokeInstruction = InvokeInstruction(
            opcode = "invoke-virtual",
            className = "Ljava/lang/StringBuilder;",
            methodName = "concat",
            descriptor = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            range = range(0, 0, 0, 120)
        )
        
        fieldAccessInstruction = FieldAccessInstruction(
            opcode = "iget-object",
            className = "Ljava/lang/String;",
            fieldName = "value",
            fieldType = "[C",
            range = range(0, 0, 0, 60)
        )
        
        typeInstruction = TypeInstruction(
            opcode = "new-instance",
            className = "Ljava/util/ArrayList;",
            range = range(0, 0, 0, 45)
        )
        
        worstCaseInstruction = InvokeInstruction(
            opcode = "invoke-static",
            className = "Lcom/example/Utils;",
            methodName = "process",
            descriptor = "(Ljava/lang/String;Ljava/util/List;Ljava/util/Map;Ljava/io/File;Ljava/net/URL;[Ljava/lang/Object;)Ljava/lang/String;",
            range = range(0, 0, 0, 200)
        )
    }
    
    //
    // STRING OPERATIONS BENCHMARKS
    //
    
    @Benchmark
    fun stringOps_simpleInvoke(): Any? {
        return InstructionSymbolExtractor.extractSymbol(
            simpleInvokeInstruction,
            simpleInvokeLine,
            simpleInvokeCursorPos
        )
    }
    
    @Benchmark
    fun stringOps_complexInvoke(): Any? {
        return InstructionSymbolExtractor.extractSymbol(
            complexInvokeInstruction,
            complexInvokeLine,
            complexInvokeCursorPos
        )
    }
    
    @Benchmark
    fun stringOps_fieldAccess(): Any? {
        return InstructionSymbolExtractor.extractSymbol(
            fieldAccessInstruction,
            fieldAccessLine,
            fieldAccessCursorPos
        )
    }
    
    @Benchmark
    fun stringOps_typeInstruction(): Any? {
        return InstructionSymbolExtractor.extractSymbol(
            typeInstruction,
            typeLine,
            typeCursorPos
        )
    }
    
    @Benchmark
    fun stringOps_worstCase(): Any? {
        return InstructionSymbolExtractor.extractSymbol(
            worstCaseInstruction,
            worstCaseLine,
            worstCaseCursorPos
        )
    }
    
    //
    // REGEX BENCHMARKS
    //
    
    @Benchmark
    fun regex_simpleInvoke(): Any? {
        return InstructionSymbolExtractorRegex.extractSymbol(
            simpleInvokeInstruction,
            simpleInvokeLine,
            simpleInvokeCursorPos
        )
    }
    
    @Benchmark
    fun regex_complexInvoke(): Any? {
        return InstructionSymbolExtractorRegex.extractSymbol(
            complexInvokeInstruction,
            complexInvokeLine,
            complexInvokeCursorPos
        )
    }
    
    @Benchmark
    fun regex_fieldAccess(): Any? {
        return InstructionSymbolExtractorRegex.extractSymbol(
            fieldAccessInstruction,
            fieldAccessLine,
            fieldAccessCursorPos
        )
    }
    
    @Benchmark
    fun regex_typeInstruction(): Any? {
        return InstructionSymbolExtractorRegex.extractSymbol(
            typeInstruction,
            typeLine,
            typeCursorPos
        )
    }
    
    @Benchmark
    fun regex_worstCase(): Any? {
        return InstructionSymbolExtractorRegex.extractSymbol(
            worstCaseInstruction,
            worstCaseLine,
            worstCaseCursorPos
        )
    }
}
