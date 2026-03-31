package xyz.surendrajat.smalilsp.performance

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.integration.lsp.TempTestWorkspace
import kotlin.test.assertTrue

import xyz.surendrajat.smalilsp.providers.DocumentSymbolProvider
import xyz.surendrajat.smalilsp.integration.lsp.TestWorkspace
/**
 * Performance tests for DocumentSymbolProvider.
 * 
 * Target: < 20ms average for document symbol extraction
 * Real-world: Large Android files can have 200+ methods
 */
class DocumentSymbolProviderPerformanceTest {
    
    @Test
    fun `small file performance - under 5ms`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Small.smali", """
            .class public Ltest/Small;
            .super Ljava/lang/Object;
            
            .field public name:Ljava/lang/String;
            
            .method public getName()Ljava/lang/String;
                const/4 v0, 0x0
                return-object v0
            .end method
            
            .method public setName(Ljava/lang/String;)V
                return-void
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/Small.smali")
        val provider = DocumentSymbolProvider()
        
        // Warm up
        provider.provideDocumentSymbols(file)
        
        // Measure
        val iterations = 100
        val start = System.nanoTime()
        repeat(iterations) {
            provider.provideDocumentSymbols(file)
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000 // Convert to ms
        val avgTime = elapsed / iterations
        
        println("Small file (3 members): ${avgTime}ms average over $iterations runs")
        assertTrue(avgTime < 5, "Should be under 5ms, was ${avgTime}ms")
        
        workspace.cleanup()
    }
    
    @Test
    fun `medium file performance - under 10ms`() {
        val workspace = TempTestWorkspace.create()
        
        // Generate 50 methods
        val methods = (1..50).map { i ->
            """
            .method public method$i(I)Ljava/lang/String;
                const/4 v0, 0x0
                return-object v0
            .end method
            """.trimIndent()
        }.joinToString("\n\n")
        
        workspace.addFile("test/Medium.smali", """
            .class public Ltest/Medium;
            .super Ljava/lang/Object;
            
            $methods
        """.trimIndent())
        
        val file = workspace.parseFile("test/Medium.smali")
        val provider = DocumentSymbolProvider()
        
        // Warm up
        provider.provideDocumentSymbols(file)
        
        // Measure
        val iterations = 50
        val start = System.nanoTime()
        repeat(iterations) {
            provider.provideDocumentSymbols(file)
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000
        val avgTime = elapsed / iterations
        
        println("Medium file (50 methods): ${avgTime}ms average over $iterations runs")
        assertTrue(avgTime < 10, "Should be under 10ms, was ${avgTime}ms")
        
        workspace.cleanup()
    }
    
    @Test
    fun `large file performance - under 20ms`() {
        val workspace = TempTestWorkspace.create()
        
        // Generate 200 methods (typical large Android file)
        val methods = (1..200).map { i ->
            """
            .method public method$i(ILjava/lang/String;Z)V
                return-void
            .end method
            """.trimIndent()
        }.joinToString("\n\n")
        
        // Generate 50 fields
        val fields = (1..50).map { i ->
            ".field private field$i:I"
        }.joinToString("\n")
        
        workspace.addFile("test/Large.smali", """
            .class public Ltest/Large;
            .super Ljava/lang/Object;
            
            $fields
            
            $methods
        """.trimIndent())
        
        val file = workspace.parseFile("test/Large.smali")
        val provider = DocumentSymbolProvider()
        
        // Warm up
        provider.provideDocumentSymbols(file)
        
        // Measure
        val iterations = 20
        val start = System.nanoTime()
        repeat(iterations) {
            provider.provideDocumentSymbols(file)
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000
        val avgTime = elapsed / iterations
        
        println("Large file (250 members): ${avgTime}ms average over $iterations runs")
        assertTrue(avgTime < 20, "Should be under 20ms, was ${avgTime}ms")
        
        workspace.cleanup()
    }
    
    @Test
    fun `very large file performance - under 50ms`() {
        val workspace = TempTestWorkspace.create()
        
        // Generate 500 methods (extreme case - RecyclerView-sized)
        val methods = (1..500).map { i ->
            """
            .method public method$i()V
                return-void
            .end method
            """.trimIndent()
        }.joinToString("\n\n")
        
        workspace.addFile("test/VeryLarge.smali", """
            .class public Ltest/VeryLarge;
            .super Ljava/lang/Object;
            
            $methods
        """.trimIndent())
        
        val file = workspace.parseFile("test/VeryLarge.smali")
        val provider = DocumentSymbolProvider()
        
        // Warm up
        provider.provideDocumentSymbols(file)
        
        // Measure
        val iterations = 10
        val start = System.nanoTime()
        repeat(iterations) {
            provider.provideDocumentSymbols(file)
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000
        val avgTime = elapsed / iterations
        
        println("Very large file (500 methods): ${avgTime}ms average over $iterations runs")
        assertTrue(avgTime < 50, "Should be under 50ms, was ${avgTime}ms")
        
        workspace.cleanup()
    }
    
    @Test
    fun `repeated calls are consistent`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Repeated.smali", """
            .class public Ltest/Repeated;
            .super Ljava/lang/Object;
            
            .method public foo()V
                return-void
            .end method
        """.trimIndent())
        
        val file = workspace.parseFile("test/Repeated.smali")
        val provider = DocumentSymbolProvider()
        
        // Measure 1000 calls to ensure no memory leaks or performance degradation
        val times = mutableListOf<Long>()
        repeat(1000) {
            val start = System.nanoTime()
            provider.provideDocumentSymbols(file)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            times.add(elapsed)
        }
        
        val avgTime = times.average()
        val maxTime = times.maxOrNull() ?: 0L
        
        println("1000 repeated calls: avg=${avgTime.toLong()}ms, max=${maxTime}ms")
        assertTrue(avgTime < 5, "Average should stay under 5ms, was ${avgTime.toLong()}ms")
        // Note: Increased from 20ms to 30ms to account for GC/system variance in 1000 iterations
        assertTrue(maxTime < 30, "Max time should stay under 30ms (no degradation), was ${maxTime}ms")
        
        workspace.cleanup()
    }
}
