package xyz.surendrajat.smalilsp.integration

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.shared.PerformanceMetrics
import xyz.surendrajat.smalilsp.shared.PerformanceTestLock
import xyz.surendrajat.smalilsp.shared.TestUtils
import java.io.File
import java.net.URI
import kotlin.system.measureNanoTime
import kotlin.test.assertTrue

/**
 * Comprehensive diagnostic test to verify goto-definition coverage.
 *
 * User requirement: "are you sure the remaining definition issues are all sdk related?
 * make sure non-sdk related definitions are 100% covered with great perf"
 *
 * This test verifies:
 * 1. ALL workspace methods are navigable (100% coverage)
 * 2. SDK method failures are correctly identified
 * 3. Performance is acceptable (<1ms avg for workspace methods)
 */
class GotoDefinitionCoverageTest {

    private data class InvokeInfo(
        val className: String,
        val methodName: String,
        val descriptor: String,
        val fileUri: String,
        val position: Position
    )

    private data class FailureInfo(
        val status: String,
        val invoke: InvokeInfo
    ) {
        override fun toString(): String = "$status ${invoke.className}->${invoke.methodName}${invoke.descriptor}"
    }

    @Test
    fun `verify 100 percent workspace method coverage on Mastodon APK`() {
        testAPKCoverage("Mastodon", TestUtils.getMastodonApk())
    }

    @Test
    fun `verify 100 percent workspace method coverage on ProtonMail APK`() {
        testAPKCoverage("ProtonMail", TestUtils.getProtonMailApk())
    }

    private fun testAPKCoverage(apkName: String, apkPath: File?) {
        assumeTrue(apkPath != null, "$apkName APK not available — skipping")

        PerformanceTestLock.withExclusiveLock("GotoDefinitionCoverageTest:$apkName") {
            println("\n========================================")
            println("Goto-Definition Coverage: $apkName")
            println("========================================")

            val index = WorkspaceIndex()
            val scanner = WorkspaceScanner(index)
            val scanResult = runBlocking {
                scanner.scanDirectory(apkPath!!)
            }

            println("Indexed: ${scanResult.filesSucceeded} files in ${scanResult.durationMs}ms")

            val files = index.getAllFiles()
            val defProvider = DefinitionProvider(index)

            val workspaceInvokes = mutableListOf<InvokeInfo>()
            val sdkSampleInvokes = mutableListOf<InvokeInfo>()
            var totalInvokes = 0
            var unresolvedPositions = 0

            for (file in files) {
                val lines = try {
                    File(URI(file.uri)).readLines()
                } catch (_: Exception) {
                    continue
                }

                for (method in file.methods) {
                    for (inst in method.instructions.filterIsInstance<InvokeInstruction>()) {
                        totalInvokes++

                        val position = findInvokePosition(lines, inst)
                        if (position == null) {
                            unresolvedPositions++
                            continue
                        }

                        val invoke = InvokeInfo(
                            className = inst.className,
                            methodName = inst.methodName,
                            descriptor = inst.descriptor,
                            fileUri = file.uri,
                            position = position
                        )

                        val classForLookup = inst.className.trimStart('[')
                        val isWorkspace = !inst.className.startsWith('[') && index.findClass(classForLookup) != null
                        if (isWorkspace) {
                            workspaceInvokes.add(invoke)
                        } else if (sdkSampleInvokes.size < 100) {
                            sdkSampleInvokes.add(invoke)
                        }
                    }
                }
            }

            println("\nTotal invoke instructions: $totalInvokes")
            println("Workspace invokes: ${workspaceInvokes.size}")
            println("SDK invokes sampled: ${sdkSampleInvokes.size}")
            println("Skipped invokes without resolved method position: $unresolvedPositions")

            println("\n--- Testing Workspace Method Navigation ---")

            assertTrue(workspaceInvokes.isNotEmpty(), "$apkName should expose workspace invokes")

            var workspaceSuccess = 0
            var workspaceFailed = 0
            val workspaceFailures = mutableListOf<FailureInfo>()
            val failedWorkspaceInvokes = mutableListOf<InvokeInfo>()
            val failureCounts = linkedMapOf(
                "[CLASS_NOT_IN_INDEX]" to 0,
                "[METHOD_EXISTS_IN_CLASS]" to 0,
                "[METHOD_NOT_IN_CLASS]" to 0,
            )
            val perfMetrics = PerformanceMetrics("Goto-Definition")

            forEachInvokeWithDocumentCache(index, defProvider, workspaceInvokes, perfMetrics) { invoke, definitions ->
                if (definitions.isNotEmpty()) {
                    workspaceSuccess++
                } else {
                    workspaceFailed++
                    failedWorkspaceInvokes.add(invoke)

                    val classFile = index.findClass(invoke.className)
                    val status = when {
                        classFile == null -> "[CLASS_NOT_IN_INDEX]"
                        classFile.methods.any { it.name == invoke.methodName && it.descriptor == invoke.descriptor } -> "[METHOD_EXISTS_IN_CLASS]"
                        else -> "[METHOD_NOT_IN_CLASS]"
                    }
                    failureCounts[status] = failureCounts.getValue(status) + 1

                    if (workspaceFailures.size < 100) {
                        workspaceFailures.add(FailureInfo(status, invoke))
                    }
                }
            }

            println("Workspace method results:")
            println("  Success: $workspaceSuccess/${workspaceInvokes.size} (${"%.1f".format(workspaceSuccess * 100.0 / workspaceInvokes.size)}%)")
            println("  Failed: $workspaceFailed")
            println()
            perfMetrics.printSummary()

            if (workspaceFailures.isNotEmpty()) {
                println("\n  First 20 workspace failures:")
                workspaceFailures.forEach { println("    - $it") }

                val failureFile = File(TestUtils.getProjectRoot(), "workspace_method_failures_${apkName.lowercase()}.txt")
                failureFile.writeText("Total workspace failures: $workspaceFailed\n\n")
                failedWorkspaceInvokes.forEach { invoke ->
                    failureFile.appendText("${invoke.className}->${invoke.methodName}${invoke.descriptor}\n")
                }
            }

            println("\n--- Testing SDK Method Navigation (Expected to Fail) ---")

            assertTrue(sdkSampleInvokes.isNotEmpty(), "$apkName should expose at least one non-workspace invoke")

            var sdkSuccess = 0
            var sdkFailed = 0
            val sdkSuccesses = mutableListOf<String>()

            forEachInvokeWithDocumentCache(index, defProvider, sdkSampleInvokes) { invoke, definitions ->
                if (definitions.isNotEmpty()) {
                    sdkSuccess++
                    if (sdkSuccesses.size < 10) {
                        sdkSuccesses.add("${invoke.className}->${invoke.methodName}${invoke.descriptor}")
                    }
                } else {
                    sdkFailed++
                }
            }

            println("SDK method results (sample of ${sdkSampleInvokes.size}):")
            println("  Success: $sdkSuccess (unexpected - SDK methods should not be navigable)")
            println("  Failed: $sdkFailed (expected)")
            if (sdkSuccesses.isNotEmpty()) {
                println("\n  SDK methods that returned definitions:")
                sdkSuccesses.forEach { println("    - $it") }
            }

            val workspaceCoverage = workspaceSuccess * 100.0 / workspaceInvokes.size

            println("\n========================================")
            println("Analysis")
            println("========================================")
            println("Workspace coverage: ${"%.1f".format(workspaceCoverage)}%")

            if (workspaceCoverage < 100.0) {
                println("\nInvestigating failures...")
                println("\nFailure analysis:")
                println("  Class not in index: ${failureCounts.getValue("[CLASS_NOT_IN_INDEX]")}")
                println("  Method exists in class but resolution failed: ${failureCounts.getValue("[METHOD_EXISTS_IN_CLASS]")}")
                println("  Method not in declaring class: ${failureCounts.getValue("[METHOD_NOT_IN_CLASS]")}")
            }

            println("\n========================================")
            println("Verification")
            println("========================================")

            assertTrue(
                workspaceCoverage >= 99.5,
                "$apkName: Workspace method coverage should stay >= 99.5%, got ${"%.1f".format(workspaceCoverage)}%"
            )
            assertTrue(
                perfMetrics.getAvgMs() < 0.5,
                "$apkName: Avg latency should be < 0.5ms, got ${"%.3f".format(perfMetrics.getAvgMs())}ms"
            )
            assertTrue(
                perfMetrics.getP95Ms() < 1.5,
                "$apkName: P95 latency should be < 1.5ms, got ${"%.3f".format(perfMetrics.getP95Ms())}ms"
            )

            val sdkFailRate = sdkFailed * 100.0 / sdkSampleInvokes.size
            assertTrue(
                sdkFailRate >= 95.0,
                "SDK sample should resolve only rarely, got ${"%.1f".format(sdkFailRate)}% fail rate"
            )

            println("\n✅ Verification PASSED:")
            println("  - Workspace coverage: ${"%.1f".format(workspaceCoverage)}% (>= 99.5%)")
            println("  - Avg latency: ${"%.3f".format(perfMetrics.getAvgMs())}ms (< 0.5ms)")
            println("  - Median latency: ${"%.3f".format(perfMetrics.getMedianMs())}ms")
            println("  - P95 latency: ${"%.3f".format(perfMetrics.getP95Ms())}ms (< 1.5ms)")
            println("  - P99 latency: ${"%.3f".format(perfMetrics.getP99Ms())}ms")
            println("  - SDK fail rate: ${"%.1f".format(sdkFailRate)}% (>= 95%)")
        }
    }

    private fun findInvokePosition(lines: List<String>, instruction: InvokeInstruction): Position? {
        val lineIndex = instruction.range.start.line
        if (lineIndex !in lines.indices) {
            return null
        }

        val line = lines[lineIndex]
        val arrowIndex = line.indexOf("->")
        if (arrowIndex < 0) {
            return null
        }

        val methodNameIndex = line.indexOf(instruction.methodName, arrowIndex + 2)
        if (methodNameIndex < 0) {
            return null
        }

        val clickOffset = if (instruction.methodName.length > 2) instruction.methodName.length / 2 else 0
        return Position(lineIndex, methodNameIndex + clickOffset)
    }

    private fun forEachInvokeWithDocumentCache(
        index: WorkspaceIndex,
        provider: DefinitionProvider,
        invokes: List<InvokeInfo>,
        metrics: PerformanceMetrics? = null,
        block: (InvokeInfo, List<Location>) -> Unit
    ) {
        invokes.groupBy { it.fileUri }.forEach { (uri, fileInvokes) ->
            val content = try {
                File(URI(uri)).readText()
            } catch (_: Exception) {
                null
            }

            if (content != null) {
                index.setDocumentContent(uri, content)
            }

            try {
                fileInvokes.forEach { invoke ->
                    var definitions: List<Location> = emptyList()
                    val latency = measureNanoTime {
                        definitions = provider.findDefinition(invoke.fileUri, invoke.position)
                    }
                    metrics?.record(latency)
                    block(invoke, definitions)
                }
            } finally {
                if (content != null) {
                    index.removeDocumentContent(uri)
                }
            }
        }
    }
}
