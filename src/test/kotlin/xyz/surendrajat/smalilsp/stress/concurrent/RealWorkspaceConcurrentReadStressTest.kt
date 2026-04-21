package xyz.surendrajat.smalilsp.stress.concurrent

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.shared.PerformanceTestLock
import xyz.surendrajat.smalilsp.shared.TestUtils
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-workspace concurrent read stress coverage.
 *
 * The smaller synthetic concurrent tests prove thread-safety for mutation and lookup,
 * but this test exercises hover, goto-definition, and find-references concurrently
 * against an actual indexed APK workspace.
 */
class RealWorkspaceConcurrentReadStressTest {

    @Test
    fun `concurrent provider reads stay stable on Mastodon workspace`() = PerformanceTestLock.withExclusiveLock("RealWorkspaceConcurrentReadStressTest") {
        val apkDir = TestUtils.requireMastodonApk()
        val index = WorkspaceIndex()
        val scanner = WorkspaceScanner(index)

        runBlocking {
            scanner.scanDirectory(apkDir)
        }

        val hoverProvider = HoverProvider(index)
        val definitionProvider = DefinitionProvider(index)
        val referenceProvider = ReferenceProvider(index)

        data class Operation(val name: String, val run: () -> Boolean)

        val hoverOps = index.getAllFiles()
            .take(10)
            .map { file ->
                Operation("hover ${file.classDefinition.name}") {
                    val range = file.classDefinition.range
                    hoverProvider.provideHover(
                        file.uri,
                        Position(range.start.line, range.start.character + 5)
                    ) != null
                }
            }

        val definitionOps = index.getAllFiles()
            .asSequence()
            .filter { file ->
                val superClass = file.classDefinition.superClass
                file.classDefinition.superClassRange != null &&
                    superClass != null &&
                    index.findClass(superClass) != null
            }
            .take(10)
            .map { file ->
                Operation("definition ${file.classDefinition.name}") {
                    val range = file.classDefinition.superClassRange!!
                    definitionProvider.findDefinition(
                        file.uri,
                        Position(range.start.line, range.start.character + 1)
                    ).isNotEmpty()
                }
            }
            .toList()

        val referenceOps = index.getAllClassNames()
            .asSequence()
            .filter { className ->
                !className.startsWith("Ljava/") &&
                    !className.startsWith("Landroid/") &&
                    (index.findClassUsages(className).isNotEmpty() || index.findClassRefLocations(className).isNotEmpty())
            }
            .mapNotNull { className ->
                val file = index.findClass(className) ?: return@mapNotNull null
                val range = file.classDefinition.range
                Operation("references $className") {
                    referenceProvider.findReferences(
                        file.uri,
                        Position(range.start.line, range.start.character + 5),
                        includeDeclaration = false
                    ).isNotEmpty()
                }
            }
            .take(10)
            .toList()

        val operations = (hoverOps + definitionOps + referenceOps)

        assertEquals(30, operations.size, "Expected a balanced real-workspace operation set")

        val threadCount = 12
        val iterations = 3
        val barrier = CyclicBarrier(threadCount)
        val attempts = AtomicInteger(0)
        val successes = AtomicInteger(0)
        val exceptions = ConcurrentLinkedQueue<String>()
        val failures = ConcurrentLinkedQueue<String>()

        val threads = (0 until threadCount).map { threadId ->
            Thread {
                try {
                    barrier.await()
                    repeat(iterations) {
                        operations.forEach { operation ->
                            attempts.incrementAndGet()
                            try {
                                if (operation.run()) {
                                    successes.incrementAndGet()
                                } else {
                                    failures.add("thread-$threadId ${operation.name}")
                                }
                            } catch (e: Exception) {
                                exceptions.add("thread-$threadId ${operation.name}: ${e::class.simpleName}: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    exceptions.add("thread-$threadId barrier/setup: ${e::class.simpleName}: ${e.message}")
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join(60_000) }

        val aliveThreads = threads.count { it.isAlive }
        val successRate = successes.get() * 100.0 / attempts.get()

        assertEquals(0, aliveThreads, "All concurrent reader threads should finish")
        assertTrue(exceptions.isEmpty(), "Concurrent real-workspace reads should not throw. Sample: ${exceptions.take(5)}")
        assertTrue(successRate >= 99.0, "Concurrent real-workspace reads should succeed >=99% of the time, got ${String.format("%.2f", successRate)}%")
        assertTrue(failures.isEmpty(), "All sampled real-workspace operations should return meaningful results. Sample: ${failures.take(5)}")
    }
}