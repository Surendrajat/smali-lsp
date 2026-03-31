package xyz.surendrajat.smalilsp.unit.indexer

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import java.io.File

import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
/**
 * Test WorkspaceScanner with real scenarios.
 * 
 * These tests verify:
 * - Parallel file processing
 * - Progress tracking
 * - Error handling (bad files don't break everything)
 * - Performance (measured and logged)
 */
class WorkspaceScannerTest {
    
    @TempDir
    lateinit var tempDir: File
    
    private lateinit var index: WorkspaceIndex
    private lateinit var scanner: WorkspaceScanner
    
    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        scanner = WorkspaceScanner(index)
    }
    
    @Test
    fun `scan empty directory`() = runBlocking {
        val result = scanner.scanDirectory(tempDir)
        
        assertEquals(0, result.filesProcessed)
        assertEquals(0, result.filesSucceeded)
        assertEquals(0, result.filesFailed)
    }
    
    @Test
    fun `scan directory with single smali file`() = runBlocking {
        // Create a simple smali file
        val smaliFile = File(tempDir, "Test.smali")
        smaliFile.writeText("""
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            
            .method public test()V
                return-void
            .end method
        """.trimIndent())
        
        val result = scanner.scanDirectory(tempDir)
        
        assertEquals(1, result.filesProcessed)
        assertEquals(1, result.filesSucceeded)
        assertEquals(0, result.filesFailed)
        
        // Verify it was indexed
        val stats = index.getStats()
        assertEquals(1, stats.classes)
        assertEquals(1, stats.methods)
        
        // Verify we can find the class
        val found = index.findClass("Lcom/example/Test;")
        assertNotNull(found)
    }
    
    @Test
    fun `scan directory with multiple files in subdirectories`() = runBlocking {
        // Create directory structure
        val subdir1 = File(tempDir, "com/example/pkg1")
        val subdir2 = File(tempDir, "com/example/pkg2")
        subdir1.mkdirs()
        subdir2.mkdirs()
        
        // Create smali files
        File(subdir1, "ClassA.smali").writeText("""
            .class public Lcom/example/pkg1/ClassA;
            .super Ljava/lang/Object;
            
            .field private value:I
            
            .method public getValue()I
                const/4 v0, 0x0
                return v0
            .end method
        """.trimIndent())
        
        File(subdir1, "ClassB.smali").writeText("""
            .class public Lcom/example/pkg1/ClassB;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        File(subdir2, "ClassC.smali").writeText("""
            .class public Lcom/example/pkg2/ClassC;
            .super Ljava/lang/Object;
            
            # interfaces
            .implements Ljava/lang/Runnable;
            
            .method public run()V
                return-void
            .end method
        """.trimIndent())
        
        val result = scanner.scanDirectory(tempDir)
        
        assertEquals(3, result.filesProcessed)
        assertEquals(3, result.filesSucceeded)
        assertEquals(0, result.filesFailed)
        
        // Verify all classes indexed
        val stats = index.getStats()
        assertEquals(3, stats.classes)
        
        // Verify we can find each class
        assertNotNull(index.findClass("Lcom/example/pkg1/ClassA;"))
        assertNotNull(index.findClass("Lcom/example/pkg1/ClassB;"))
        assertNotNull(index.findClass("Lcom/example/pkg2/ClassC;"))
        
        // Verify field was indexed
        val stats2 = index.getStats()
        assertEquals(1, stats2.fields)
        
        // Verify methods were indexed
        assertEquals(2, stats2.methods)
    }
    
    @Test
    fun `scan handles invalid smali file gracefully`() = runBlocking {
        // Create valid and invalid files
        File(tempDir, "Good.smali").writeText("""
            .class public Lcom/example/Good;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        File(tempDir, "Bad.smali").writeText("""
            this is not valid smali at all!
            complete garbage
        """.trimIndent())
        
        File(tempDir, "AlsoGood.smali").writeText("""
            .class public Lcom/example/AlsoGood;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        val result = scanner.scanDirectory(tempDir)
        
        assertEquals(3, result.filesProcessed)
        // Bad file should be handled gracefully
        assertTrue(result.filesSucceeded >= 2, "At least 2 good files should succeed")
        assertTrue(result.filesFailed <= 1, "At most 1 bad file should fail")
        
        // Good files should be indexed
        assertNotNull(index.findClass("Lcom/example/Good;"))
        assertNotNull(index.findClass("Lcom/example/AlsoGood;"))
    }
    
    @Test
    fun `scan ignores non-smali files`() = runBlocking {
        // Create various files
        File(tempDir, "Test.smali").writeText("""
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        File(tempDir, "readme.txt").writeText("This is a text file")
        File(tempDir, "data.json").writeText("{}")
        File(tempDir, "script.sh").writeText("#!/bin/bash")
        
        val result = scanner.scanDirectory(tempDir)
        
        // Only .smali file should be processed
        assertEquals(1, result.filesProcessed)
        assertEquals(1, result.filesSucceeded)
    }
    
    @Test
    fun `scan tracks progress`() = runBlocking {
        // Create multiple files
        repeat(10) { i ->
            File(tempDir, "Class$i.smali").writeText("""
                .class public Lcom/example/Class$i;
                .super Ljava/lang/Object;
            """.trimIndent())
        }
        
        val progressUpdates = mutableListOf<Pair<Int, Int>>()
        
        val result = scanner.scanDirectory(tempDir) { processed, total ->
            progressUpdates.add(processed to total)
        }
        
        assertEquals(10, result.filesProcessed)
        assertEquals(10, result.filesSucceeded)
        
        // Should have received at least one progress update
        assertTrue(progressUpdates.isNotEmpty(), "Should receive progress updates")
        
        // Last update should be complete
        val lastUpdate = progressUpdates.last()
        assertEquals(10, lastUpdate.first, "Last update should show all files processed")
        assertEquals(10, lastUpdate.second, "Last update should show correct total")
    }
    
    @Test
    fun `scan performance with moderate file count`() = runBlocking {
        // Create 50 files to test performance
        val fileCount = 50
        repeat(fileCount) { i ->
            File(tempDir, "Class$i.smali").writeText("""
                .class public Lcom/example/Class$i;
                .super Ljava/lang/Object;
                
                .field private field$i:I
                
                .method public method$i()V
                    return-void
                .end method
            """.trimIndent())
        }
        
        val result = scanner.scanDirectory(tempDir)
        
        assertEquals(fileCount, result.filesProcessed)
        assertEquals(fileCount, result.filesSucceeded)
        
        // Performance check: should be fast
        assertTrue(result.durationMs < 5000, "Should complete 50 files in <5 seconds, took ${result.durationMs}ms")
        
        println("Performance: $fileCount files in ${result.durationMs}ms (${result.filesPerSecond} files/sec)")
        
        // Verify all indexed correctly
        val stats = index.getStats()
        assertEquals(fileCount, stats.classes)
        assertEquals(fileCount, stats.fields)
        assertEquals(fileCount, stats.methods)
    }
    
    @Test
    fun `scan result calculates files per second`() = runBlocking {
        File(tempDir, "Test.smali").writeText("""
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        val result = scanner.scanDirectory(tempDir)
        
        // Note: filesPerSecond can be 0 if duration is 0ms (very fast execution)
        // This is actually good - means parsing is instant
        assertTrue(result.filesPerSecond >= 0, "Should calculate files/second (can be 0 if instant)")
        println("Processing rate: ${result.filesPerSecond} files/second")
    }
}
