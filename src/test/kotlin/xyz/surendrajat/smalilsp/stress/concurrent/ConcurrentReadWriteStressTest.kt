package xyz.surendrajat.smalilsp.stress.concurrent

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Concurrent read/write stress test.
 * Verifies that 50 reader threads + 5 writer threads can
 * operate on the index simultaneously without corruption.
 */
class ConcurrentReadWriteStressTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var parser: SmaliParser
    private lateinit var defProvider: DefinitionProvider
    private lateinit var hoverProvider: HoverProvider
    private lateinit var refProvider: ReferenceProvider

    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        parser = SmaliParser()
        defProvider = DefinitionProvider(index)
        hoverProvider = HoverProvider(index)
        refProvider = ReferenceProvider(index)

        // Pre-populate index with base classes
        for (i in 0 until 100) {
            val content = """
                .class public Lcom/example/Class$i;
                .super Ljava/lang/Object;
                .method public constructor <init>()V
                    .registers 1
                    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                    return-void
                .end method
                .method public method$i()V
                    .registers 2
                    const-string v0, "value$i"
                    return-void
                .end method
            """.trimIndent()
            val file = parser.parse("file:///test/Class$i.smali", content)
            assertNotNull(file)
            index.indexFile(file)
        }
    }

    @Test
    fun `concurrent reads from 50 threads with no errors`() {
        val threadCount = 50
        val errors = AtomicInteger(0)
        val successes = AtomicInteger(0)
        val barrier = CyclicBarrier(threadCount)

        val threads = (0 until threadCount).map { threadId ->
            Thread {
                try {
                    barrier.await() // all threads start at once
                    repeat(20) { iteration ->
                        val classIdx = (threadId + iteration) % 100
                        val uri = "file:///test/Class$classIdx.smali"

                        // Random read operations
                        when (iteration % 3) {
                            0 -> {
                                val defs = defProvider.findDefinition(uri, Position(0, 20))
                                // May or may not find definitions depending on cursor position
                            }
                            1 -> {
                                val hover = hoverProvider.provideHover(uri, Position(0, 20))
                                // Hover on class directive should work
                            }
                            2 -> {
                                val refs = refProvider.findReferences(uri, Position(0, 20), true)
                                // References may be empty for uncalled classes
                            }
                        }
                        successes.incrementAndGet()
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }

        assertEquals(0, errors.get(), "No errors should occur during concurrent reads")
        assertEquals(threadCount * 20, successes.get(), "All operations should complete")
    }

    @Test
    fun `50 readers + 5 writers with no corruption`() {
        val readerCount = 50
        val writerCount = 5
        val readErrors = AtomicInteger(0)
        val writeErrors = AtomicInteger(0)
        val readSuccesses = AtomicInteger(0)
        val writeSuccesses = AtomicInteger(0)
        val totalOps = readerCount + writerCount
        val barrier = CyclicBarrier(totalOps)

        val readers = (0 until readerCount).map { threadId ->
            Thread {
                try {
                    barrier.await()
                    repeat(10) { iteration ->
                        val classIdx = (threadId + iteration) % 100
                        val uri = "file:///test/Class$classIdx.smali"

                        // Read operations - should not throw even during concurrent writes
                        try {
                            defProvider.findDefinition(uri, Position(0, 20))
                            hoverProvider.provideHover(uri, Position(0, 20))
                            readSuccesses.incrementAndGet()
                        } catch (e: Exception) {
                            readErrors.incrementAndGet()
                        }
                    }
                } catch (e: Exception) {
                    readErrors.incrementAndGet()
                }
            }
        }

        val writers = (0 until writerCount).map { writerId ->
            Thread {
                try {
                    barrier.await()
                    repeat(10) { iteration ->
                        // Re-index classes (simulates file changes during analysis)
                        val classIdx = (writerId * 20 + iteration) % 100
                        val content = """
                            .class public Lcom/example/Class$classIdx;
                            .super Ljava/lang/Object;
                            .method public constructor <init>()V
                                .registers 1
                                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                                return-void
                            .end method
                            .method public updatedMethod${writerId}_$iteration()V
                                .registers 2
                                const-string v0, "updated_$iteration"
                                return-void
                            .end method
                        """.trimIndent()
                        try {
                            val file = parser.parse("file:///test/Class$classIdx.smali", content)
                            if (file != null) {
                                index.indexFile(file)
                            }
                            writeSuccesses.incrementAndGet()
                        } catch (e: Exception) {
                            writeErrors.incrementAndGet()
                        }
                    }
                } catch (e: Exception) {
                    writeErrors.incrementAndGet()
                }
            }
        }

        (readers + writers).forEach { it.start() }
        (readers + writers).forEach { it.join(30_000) }

        assertEquals(0, readErrors.get(), "No read errors during concurrent read/write")
        assertEquals(0, writeErrors.get(), "No write errors during concurrent read/write")
        assertTrue(readSuccesses.get() > 0, "Readers should complete successfully")
        assertTrue(writeSuccesses.get() > 0, "Writers should complete successfully")

        // Index should still be consistent after all operations
        val stats = index.getStats()
        assertTrue(stats.classes >= 100, "Index should still have all classes (got ${stats.classes})")
    }

    @Test
    fun `index consistency after rapid file updates`() {
        // Rapidly re-index the same file 100 times in parallel
        val updateCount = 100
        val errors = AtomicInteger(0)
        val barrier = CyclicBarrier(updateCount)

        val threads = (0 until updateCount).map { i ->
            Thread {
                try {
                    barrier.await()
                    val content = """
                        .class public Lcom/example/Rapidly;
                        .super Ljava/lang/Object;
                        .method public constructor <init>()V
                            .registers 1
                            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                            return-void
                        .end method
                        .method public version()I
                            .registers 2
                            const/16 v0, $i
                            return v0
                        .end method
                    """.trimIndent()
                    val file = parser.parse("file:///test/Rapidly.smali", content)
                    if (file != null) {
                        index.indexFile(file)
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }

        assertEquals(0, errors.get(), "No errors during rapid updates")

        // File should be consistently indexed (one version wins)
        val file = index.findClass("Lcom/example/Rapidly;")
        assertNotNull(file, "Rapidly should still be in index after concurrent updates")
        assertEquals(2, file.methods.size, "Should have exactly 2 methods")
    }
}
