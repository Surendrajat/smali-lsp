package xyz.surendrajat.smalilsp.indexer

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scans workspace and indexes all Smali files.
 * 
 * Design:
 * - Parallel file processing using coroutines
 * - Progress tracking
 * - Error handling (one bad file doesn't break everything)
 * - Performance: optimized for 900+ files/sec (achievable with current optimizations)
 */
class WorkspaceScanner(
    private val index: WorkspaceIndex,
    private val parser: SmaliParser = SmaliParser()
) {
    private val logger = LoggerFactory.getLogger(WorkspaceScanner::class.java)
    
    /**
     * Scan a directory and index all .smali files.
     * 
     * Uses optimized strategy:
     * 1. Pre-load file paths
     * 2. Read + Parse + Index in parallel using all CPU cores
     * 3. Use Dispatchers.Default for CPU-bound work
     * 
     * @param directory Root directory to scan
     * @param progressCallback Optional callback for progress updates (filesProcessed, totalFiles)
     * @return ScanResult with statistics
     */
    suspend fun scanDirectory(
        directory: File,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): ScanResult {
        val startTime = System.currentTimeMillis()
        
        // Find all .smali files
        logger.info("Scanning directory: ${directory.absolutePath}")
        val smaliFiles = findSmaliFiles(directory)
        val totalFiles = smaliFiles.size
        logger.info("Found $totalFiles .smali files")
        
        if (totalFiles == 0) {
            return ScanResult(
                filesProcessed = 0,
                filesSucceeded = 0,
                filesFailed = 0,
                durationMs = System.currentTimeMillis() - startTime
            )
        }
        
        // Process files in parallel using a bounded channel + fixed worker pool.
        // For large projects (100K+ files), launching one coroutine per file creates
        // 100K Deferred objects simultaneously → GC thrashing.  A fixed worker pool
        // gives identical throughput with O(workers) live objects.
        val succeeded = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val processed = AtomicInteger(0)
        val workerCount = maxOf(2, Runtime.getRuntime().availableProcessors())
        // Buffer one batch ahead per worker so workers are never starved.
        val channel = Channel<File>(capacity = workerCount * 4)

        coroutineScope {
            // Producer: feed files into the channel
            launch {
                smaliFiles.forEach { channel.send(it) }
                channel.close()
            }

            // Workers: drain the channel, one coroutine per CPU core
            repeat(workerCount) {
                launch(Dispatchers.Default) {
                    for (file in channel) {
                        try {
                            processFile(file)
                            succeeded.incrementAndGet()
                        } catch (e: Exception) {
                            logger.warn("Failed to process ${file.absolutePath}: ${e.message}")
                            failed.incrementAndGet()
                        } finally {
                            val current = processed.incrementAndGet()
                            if (current % 1000 == 0 || current == totalFiles) {
                                progressCallback?.invoke(current, totalFiles)
                            }
                        }
                    }
                }
            }
        }
        
        val duration = System.currentTimeMillis() - startTime
        val result = ScanResult(
            filesProcessed = totalFiles,
            filesSucceeded = succeeded.get(),
            filesFailed = failed.get(),
            durationMs = duration
        )
        
        logger.info("Scan complete: ${result.filesSucceeded} succeeded, ${result.filesFailed} failed in ${duration}ms")
        logger.info("Index stats: ${index.getStats()}")
        
        return result
    }
    
    /**
     * Process a single file: parse and index.
     */
    private fun processFile(file: File) {
        val uri = file.toURI().toString()
        val content = file.readText(Charsets.UTF_8)
        
        val smaliFile = parser.parse(uri, content)
        if (smaliFile != null) {
            index.indexFile(smaliFile)
        } else {
            logger.warn("Parser returned null for $uri")
        }
    }
    
    /**
     * PROFILING VERSION: Process a single file with detailed timing.
     * Returns ProfileTiming for this file.
     */
    private fun processFileWithProfiling(file: File): ProfileTiming {
        val totalStart = System.nanoTime()
        
        // 1. File I/O
        val ioStart = System.nanoTime()
        val uri = file.toURI().toString()
        val content = file.readText(Charsets.UTF_8)
        val ioTime = System.nanoTime() - ioStart
        
        // 2. Parsing (ANTLR)
        val parseStart = System.nanoTime()
        val smaliFile = parser.parse(uri, content)
        val parseTime = System.nanoTime() - parseStart
        
        // 3. Indexing
        val indexStart = System.nanoTime()
        if (smaliFile != null) {
            index.indexFile(smaliFile)
        }
        val indexTime = System.nanoTime() - indexStart
        
        val totalTime = System.nanoTime() - totalStart
        
        return ProfileTiming(
            fileIONanos = ioTime,
            parseNanos = parseTime,
            indexNanos = indexTime,
            totalNanos = totalTime,
            fileSize = content.length
        )
    }
    
    /**
     * Scan with detailed profiling to identify bottlenecks.
     */
    suspend fun scanDirectoryWithProfiling(
        directory: File,
        sampleSize: Int = 1000,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): ProfileResult {
        val startTime = System.currentTimeMillis()
        
        // Find all .smali files
        logger.info("Scanning directory for profiling: ${directory.absolutePath}")
        val smaliFiles = findSmaliFiles(directory)
        val sampled = smaliFiles.take(sampleSize)
        val totalFiles = sampled.size
        logger.info("Profiling $totalFiles files (sample of ${smaliFiles.size})")
        
        if (totalFiles == 0) {
            return ProfileResult(
                totalFiles = 0,
                avgFileIOMs = 0.0,
                avgParseMs = 0.0,
                avgIndexMs = 0.0,
                avgTotalMs = 0.0,
                totalDurationMs = 0,
                filesPerSec = 0.0
            )
        }
        
        // Process files and collect timings
        val timings = mutableListOf<ProfileTiming>()
        val processed = AtomicInteger(0)
        
        coroutineScope {
            // Process all files in parallel
            sampled.map { file ->
                async(Dispatchers.Default) {
                    try {
                        val timing = processFileWithProfiling(file)
                        synchronized(timings) {
                            timings.add(timing)
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to profile ${file.absolutePath}: ${e.message}")
                    } finally {
                        val current = processed.incrementAndGet()
                        if (current % 200 == 0 || current == totalFiles) {
                            progressCallback?.invoke(current, totalFiles)
                        }
                    }
                }
            }.awaitAll()
        }
        
        val duration = System.currentTimeMillis() - startTime
        
        // Calculate averages
        val avgFileIO = timings.map { it.fileIONanos }.average() / 1_000_000.0  // Convert to ms
        val avgParse = timings.map { it.parseNanos }.average() / 1_000_000.0
        val avgIndex = timings.map { it.indexNanos }.average() / 1_000_000.0
        val avgTotal = timings.map { it.totalNanos }.average() / 1_000_000.0
        
        return ProfileResult(
            totalFiles = totalFiles,
            avgFileIOMs = avgFileIO,
            avgParseMs = avgParse,
            avgIndexMs = avgIndex,
            avgTotalMs = avgTotal,
            totalDurationMs = duration,
            filesPerSec = if (duration > 0) (totalFiles * 1000.0) / duration else 0.0
        )
    }
    
    /**
     * Recursively find all .smali files in a directory.
     */
    private fun findSmaliFiles(directory: File): List<File> {
        if (!directory.exists() || !directory.isDirectory) {
            return emptyList()
        }
        
        return directory.walkTopDown()
            .filter { it.isFile && it.extension == "smali" }
            .toList()
    }
}

/**
 * Result of scanning a workspace.
 */
data class ScanResult(
    val filesProcessed: Int,
    val filesSucceeded: Int,
    val filesFailed: Int,
    val durationMs: Long
) {
    val filesPerSecond: Double
        get() = if (durationMs > 0) (filesProcessed * 1000.0) / durationMs else 0.0
}

/**
 * Timing data for a single file.
 */
data class ProfileTiming(
    val fileIONanos: Long,
    val parseNanos: Long,
    val indexNanos: Long,
    val totalNanos: Long,
    val fileSize: Int
)

/**
 * Profiling results with breakdown.
 */
data class ProfileResult(
    val totalFiles: Int,
    val avgFileIOMs: Double,
    val avgParseMs: Double,
    val avgIndexMs: Double,
    val avgTotalMs: Double,
    val totalDurationMs: Long,
    val filesPerSec: Double
) {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger(ProfileResult::class.java)
    }
    
    fun printBreakdown() {
        logger.info("=== PERFORMANCE PROFILE ===")
        logger.info("Files: $totalFiles")
        logger.info("Total time: ${totalDurationMs}ms")
        logger.info("Rate: ${"%.1f".format(filesPerSec)} files/sec")
        logger.info("Per-file breakdown (average):")
        logger.info("  File I/O:   ${"%.3f".format(avgFileIOMs)}ms (${"%.1f".format(avgFileIOMs / avgTotalMs * 100)}%)")
        logger.info("  ANTLR Parse: ${"%.3f".format(avgParseMs)}ms (${"%.1f".format(avgParseMs / avgTotalMs * 100)}%)")
        logger.info("  Indexing:    ${"%.3f".format(avgIndexMs)}ms (${"%.1f".format(avgIndexMs / avgTotalMs * 100)}%)")
        logger.info("  Total:       ${"%.3f".format(avgTotalMs)}ms")
        logger.info("===============================")
    }
}
