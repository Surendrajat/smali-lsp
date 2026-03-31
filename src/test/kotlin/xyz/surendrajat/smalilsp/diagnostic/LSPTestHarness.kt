package xyz.surendrajat.smalilsp.diagnostic

import xyz.surendrajat.smalilsp.TestUtils
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import java.io.File

/**
 * Test harness for LSP feature testing with consistent patterns.
 * Eliminates code duplication across stress/performance tests.
 */
object LSPTestHarness {
    
    /**
     * Result of parsing and indexing an APK.
     */
    data class IndexedAPK(
        val name: String,
        val path: File,
        val index: WorkspaceIndex,
        val fileCount: Int,
        val parseTimeMs: Long,
        val indexTimeMs: Long,
        val failedFiles: Int,
        val definitionProvider: DefinitionProvider,
        val hoverProvider: HoverProvider,
        val referenceProvider: ReferenceProvider
    ) {
        fun printSummary() {
            println("\\nAPK: $name")
            println("  Path: ${path.name}")
            println("  Files: $fileCount (${failedFiles} failed)")
            println("  Parse time: ${parseTimeMs}ms")
            println("  Index time: ${indexTimeMs}ms")
            println("  Parse rate: ${"%.1f".format(fileCount.toDouble() / parseTimeMs * 1000)} files/sec")
        }
    }
    
    /**
     * Parse and index an APK directory.
     * Returns IndexedAPK with all providers ready to use.
     */
    fun parseAndIndexAPK(apkName: String, apkPath: File): IndexedAPK {
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        var fileCount = 0
        var failedFiles = 0
        
        val parseTime = kotlin.system.measureTimeMillis {
            apkPath.walkTopDown()
                .filter { it.extension == "smali" }
                .forEach { file ->
                    fileCount++
                    try {
                        val content = file.readText()
                        val result = parser.parse(file.toURI().toString(), content)
                        if (result != null) {
                            index.indexFile(result)
                        } else {
                            failedFiles++
                        }
                    } catch (e: Exception) {
                        failedFiles++
                    }
                }
        }
        
        return IndexedAPK(
            name = apkName,
            path = apkPath,
            index = index,
            fileCount = fileCount,
            parseTimeMs = parseTime,
            indexTimeMs = parseTime, // Combined for now
            failedFiles = failedFiles,
            definitionProvider = DefinitionProvider(index),
            hoverProvider = HoverProvider(index),
            referenceProvider = ReferenceProvider(index)
        )
    }
    
    /**
     * Get all available APK paths for testing.
     * Returns map of APK name to path.
     */
    fun getAvailableAPKs(): Map<String, File> {
        val apks = mutableMapOf<String, File>()
        
        TestUtils.getMastodonApk()?.let { apks["Mastodon"] = it }
        TestUtils.getProtonMailApk()?.let { apks["ProtonMail"] = it }
        
        // Check for additional APKs in apk/ directory
        val apkDir = File(TestUtils.getProjectRoot(), "apk")
        if (apkDir.exists()) {
            apkDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name.endsWith("_decompiled")) {
                    val name = dir.name.removeSuffix("_decompiled")
                        .split("_")
                        .joinToString(" ") { it.capitalize() }
                    if (!apks.containsKey(name)) {
                        apks[name] = dir
                    }
                }
            }
        }
        
        return apks
    }
    
    /**
     * Check if class is SDK (not navigable).
     */
    fun isSDKClass(className: String): Boolean {
        val baseType = className.trimStart('[')
        
        if (!baseType.startsWith("L")) {
            return true // Primitives
        }
        
        return baseType.startsWith("Ljava/") ||
               baseType.startsWith("Ljavax/") ||
               baseType.startsWith("Lj$/") ||      // Java 8 desugaring
               baseType.startsWith("Landroid/") ||
               baseType.startsWith("Ldalvik/") ||
               baseType.startsWith("Lkotlin/") ||
               baseType.startsWith("Lkotlinx/")
    }
    
    /**
     * Save metrics to a markdown file.
     */
    fun saveMetricsToMarkdown(
        fileName: String,
        apkResults: List<Pair<String, Map<String, Any>>>
    ) {
        val outputFile = File(TestUtils.getProjectRoot(), fileName)
        val content = buildString {
            appendLine("# LSP Performance Metrics")
            appendLine("**Generated:** ${java.time.LocalDateTime.now()}")
            appendLine()
            
            for ((apkName, metrics) in apkResults) {
                appendLine("## $apkName")
                appendLine()
                appendLine("| Metric | Value |")
                appendLine("|--------|-------|")
                metrics.forEach { (key, value) ->
                    val displayKey = key.replace("_", " ").capitalize()
                    val displayValue = when (value) {
                        is Double -> "${"%.3f".format(value)}"
                        is Float -> "${"%.3f".format(value)}"
                        else -> value.toString()
                    }
                    appendLine("| $displayKey | $displayValue |")
                }
                appendLine()
            }
        }
        outputFile.writeText(content)
        println("\\nMetrics saved to: ${outputFile.name}")
    }
}
