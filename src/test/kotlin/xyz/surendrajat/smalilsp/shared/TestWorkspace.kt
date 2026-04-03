package xyz.surendrajat.smalilsp.shared

import xyz.surendrajat.smalilsp.shared.TestUtils

import java.io.File
import java.nio.file.Files
import org.eclipse.lsp4j.Position

import xyz.surendrajat.smalilsp.shared.TestWorkspace
/**
 * Represents a test workspace with Smali files for E2E testing.
 * Can be created from a real directory (like Mastodon APK) or as a temporary workspace.
 */
class TestWorkspace(val baseDir: File) {
    
    init {
        require(baseDir.exists()) { "Base directory does not exist: ${baseDir.absolutePath}" }
        require(baseDir.isDirectory) { "Base directory is not a directory: ${baseDir.absolutePath}" }
    }
    
    /**
     * Get root URI for LSP initialize
     */
    val rootUri: String
        get() = baseDir.toURI().toString()
    
    /**
     * Get file URI for a relative path
     */
    fun getFileUri(relativePath: String): String {
        return File(baseDir, relativePath).toURI().toString()
    }
    
    /**
     * Read file content
     */
    fun readFile(relativePath: String): String {
        val file = File(baseDir, relativePath)
        require(file.exists()) { "File does not exist: ${file.absolutePath}" }
        return file.readText()
    }
    
    /**
     * Write file content (creates parent directories)
     */
    fun writeFile(relativePath: String, content: String) {
        val file = File(baseDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
    
    /**
     * Delete a file
     */
    fun deleteFile(relativePath: String): Boolean {
        val file = File(baseDir, relativePath)
        return file.delete()
    }
    
    /**
     * Check if file exists
     */
    fun fileExists(relativePath: String): Boolean {
        return File(baseDir, relativePath).exists()
    }
    
    /**
     * List all .smali files in the workspace
     */
    fun listSmaliFiles(): List<String> {
        return baseDir.walkTopDown()
            .filter { it.isFile && it.extension == "smali" }
            .map { it.relativeTo(baseDir).path }
            .toList()
    }
    
    /**
     * Find files matching a pattern (simple contains match)
     */
    fun findFiles(pattern: String): List<String> {
        return listSmaliFiles().filter { it.contains(pattern, ignoreCase = true) }
    }
    
    /**
     * Find the line number containing a pattern (0-indexed)
     * Returns -1 if not found
     */
    fun findLine(relativePath: String, pattern: String): Int {
        val lines = readFile(relativePath).lines()
        return lines.indexOfFirst { it.contains(pattern) }
    }
    
    /**
     * Find position of first occurrence of pattern in file
     * Throws if not found
     */
    fun findPosition(relativePath: String, pattern: String): Position {
        val lines = readFile(relativePath).lines()
        lines.forEachIndexed { lineIndex, line ->
            val charIndex = line.indexOf(pattern)
            if (charIndex != -1) {
                return Position(lineIndex, charIndex)
            }
        }
        throw IllegalArgumentException("Pattern '$pattern' not found in $relativePath")
    }
    
    /**
     * Find all positions of pattern in file
     */
    fun findAllPositions(relativePath: String, pattern: String): List<Position> {
        val positions = mutableListOf<Position>()
        val lines = readFile(relativePath).lines()
        lines.forEachIndexed { lineIndex, line ->
            var startIndex = 0
            while (true) {
                val charIndex = line.indexOf(pattern, startIndex)
                if (charIndex == -1) break
                positions.add(Position(lineIndex, charIndex))
                startIndex = charIndex + 1
            }
        }
        return positions
    }
    
    /**
     * Get line content (0-indexed)
     */
    fun getLine(relativePath: String, lineNumber: Int): String {
        val lines = readFile(relativePath).lines()
        return lines.getOrNull(lineNumber) ?: ""
    }
    
    /**
     * Get file size in lines
     */
    fun getLineCount(relativePath: String): Int {
        return readFile(relativePath).lines().size
    }
    
    /**
     * Get total count of Smali files
     */
    fun getFileCount(): Int {
        return listSmaliFiles().size
    }
    
    /**
     * Get workspace statistics
     */
    fun getStats(): WorkspaceStats {
        val files = listSmaliFiles()
        val totalLines = files.sumOf { 
            try { getLineCount(it) } catch (e: Exception) { 0 }
        }
        val totalSize = files.sumOf { 
            try { File(baseDir, it).length() } catch (e: Exception) { 0L }
        }
        
        return WorkspaceStats(
            fileCount = files.size,
            totalLines = totalLines,
            totalSizeBytes = totalSize
        )
    }
    
    data class WorkspaceStats(
        val fileCount: Int,
        val totalLines: Int,
        val totalSizeBytes: Long
    ) {
        val totalSizeMB: Double get() = totalSizeBytes / (1024.0 * 1024.0)
        
        override fun toString(): String = 
            "$fileCount files, $totalLines lines, ${"%.2f".format(totalSizeMB)} MB"
    }
    
    companion object {
        /**
         * Create workspace from Mastodon APK decompiled sources
         */
        fun fromMastodon(basePath: String = TestUtils.getMastodonApk()?.absolutePath ?: ""): TestWorkspace {
            val dir = File(basePath)
            require(dir.exists()) { "Mastodon APK not found at: $basePath" }
            return TestWorkspace(dir)
        }
        
        /**
         * Create workspace from based1111 APK decompiled sources
         */
        fun fromBased1111(basePath: String = TestUtils.getMastodonApk()?.absolutePath ?: ""): TestWorkspace {
            val dir = File(basePath)
            require(dir.exists()) { "based1111 APK not found at: $basePath" }
            return TestWorkspace(dir)
        }
        
        /**
         * Create temporary workspace with single file content (defaults to Test.smali)
         */
        fun createTemp(content: String): TestWorkspace {
            return createTemp(mapOf("Test.smali" to content))
        }
        
        /**
         * Create temporary workspace with given files
         */
        fun createTemp(files: Map<String, String>): TestWorkspace {
            val tempDir = Files.createTempDirectory("smali-lsp-test").toFile()
            tempDir.deleteOnExit()
            
            val workspace = TestWorkspace(tempDir)
            files.forEach { (path, content) ->
                workspace.writeFile(path, content)
            }
            
            return workspace
        }
        
        /**
         * Create empty temporary workspace
         */
        fun createEmpty(): TestWorkspace {
            val tempDir = Files.createTempDirectory("smali-lsp-test").toFile()
            tempDir.deleteOnExit()
            return TestWorkspace(tempDir)
        }
    }
    
    /**
     * Clean up workspace (for temp workspaces)
     */
    fun cleanup() {
        if (baseDir.name.startsWith("smali-lsp-test")) {
            baseDir.deleteRecursively()
        }
    }
}
