package xyz.surendrajat.smalilsp.stress.queries

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import xyz.surendrajat.smalilsp.shared.PerformanceTestLock
import xyz.surendrajat.smalilsp.shared.TestUtils
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import kotlin.system.measureTimeMillis
import kotlin.test.assertTrue

/**
 * Stress test for Bug #7: Directive Reference Tracking (.super and .implements)
 * 
 * Validates that finding references on .super and .implements directives:
 * 1. Returns the directive line (not class declaration line)
 * 2. Finds ALL directives that reference the class
 * 3. Performs within acceptable time (<50ms per search)
 * 
 * This is a DETERMINISTIC test (no random sampling).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DirectiveReferenceStressTest {

    private data class IndexedDataset(
        val apkName: String,
        val index: WorkspaceIndex,
        val referenceProvider: ReferenceProvider,
        val scanDurationMs: Long,
        val subclassesByParent: Map<String, List<SmaliFile>>,
        val implementorsByInterface: Map<String, List<SmaliFile>>
    )
    
    data class DirectiveTestResult(
        val className: String,
        val directiveType: String, // "super" or "implements"
        val expectedReferences: Int,
        val actualReferences: Int,
        val correctLineNumbers: Boolean,
        val searchTimeMs: Long
    )

    private var mastodonDataset: IndexedDataset? = null
    private var protonMailDataset: IndexedDataset? = null

    @BeforeAll
    fun setup() {
        PerformanceTestLock.withExclusiveLock("DirectiveReferenceStressTest") {
            mastodonDataset = TestUtils.getMastodonApk()?.let { loadDataset("Mastodon", it) }
            protonMailDataset = TestUtils.getProtonMailApk()?.let { loadDataset("ProtonMail", it) }
        }
    }
    
    @Test
    fun `Bug #7 - super directive references work correctly`() {
        PerformanceTestLock.withExclusiveLock("DirectiveReferenceStressTest:super") {
            assumeTrue(mastodonDataset != null || protonMailDataset != null, "No APK datasets available — skipping")

            println("\n" + "=".repeat(70))
            println("BUG #7 STRESS TEST: .super DIRECTIVE REFERENCES")
            println("=".repeat(70))

            mastodonDataset?.let {
                println("\n[1/2] Testing Mastodon APK (4,415 files)...")
                testSuperDirectives(it)
            }

            protonMailDataset?.let {
                println("\n[2/2] Testing ProtonMail APK (18,249 files)...")
                testSuperDirectives(it)
            }

            println("\n" + "=".repeat(70))
            println("BUG #7 .super TEST COMPLETE")
            println("=".repeat(70))
        }
    }
    
    @Test
    fun `Bug #7 - implements directive references work correctly`() {
        PerformanceTestLock.withExclusiveLock("DirectiveReferenceStressTest:implements") {
            assumeTrue(mastodonDataset != null || protonMailDataset != null, "No APK datasets available — skipping")

            println("\n" + "=".repeat(70))
            println("BUG #7 STRESS TEST: .implements DIRECTIVE REFERENCES")
            println("=".repeat(70))

            mastodonDataset?.let {
                println("\n[1/2] Testing Mastodon APK (4,415 files)...")
                testImplementsDirectives(it)
            }

            protonMailDataset?.let {
                println("\n[2/2] Testing ProtonMail APK (18,249 files)...")
                testImplementsDirectives(it)
            }

            println("\n" + "=".repeat(70))
            println("BUG #7 .implements TEST COMPLETE")
            println("=".repeat(70))
        }
    }
    
    private fun loadDataset(apkName: String, apkDir: java.io.File): IndexedDataset {
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        val scanner = WorkspaceScanner(index, parser)

        println("   Indexing $apkName once for directive tests...")
        val result = runBlocking {
            scanner.scanDirectory(apkDir)
        }

        val subclassesByParent = linkedMapOf<String, MutableList<SmaliFile>>()
        val implementorsByInterface = linkedMapOf<String, MutableList<SmaliFile>>()
        index.getAllFiles().forEach { file ->
            file.classDefinition.superClass?.let { superClass ->
                subclassesByParent.getOrPut(superClass) { mutableListOf() }.add(file)
            }
            file.classDefinition.interfaces.forEach { iface ->
                implementorsByInterface.getOrPut(iface) { mutableListOf() }.add(file)
            }
        }

        return IndexedDataset(
            apkName = apkName,
            index = index,
            referenceProvider = ReferenceProvider(index),
            scanDurationMs = result.durationMs,
            subclassesByParent = subclassesByParent,
            implementorsByInterface = implementorsByInterface,
        )
    }

    private fun testSuperDirectives(dataset: IndexedDataset) {
        println("   Indexed ${dataset.index.getAllFiles().size} files in ${dataset.scanDurationMs}ms")

        val testClasses = dataset.subclassesByParent.entries
            .filter { it.value.size >= 2 }
            .sortedWith(compareByDescending<Map.Entry<String, List<SmaliFile>>> { it.value.size }.thenBy { it.key })
            .take(10)

        if (testClasses.isEmpty()) {
            println("   ⚠️  No workspace base classes found with subclasses, skipping test")
            return
        }

        val results = mutableListOf<DirectiveTestResult>()
        
        println("\n   Testing .super references...")
        testClasses.forEach { (className, subclasses) ->
            val declarationFile = dataset.index.findClass(className) ?: return@forEach
            var refs = emptyList<org.eclipse.lsp4j.Location>()
            val searchTime = measureTimeMillis {
                refs = dataset.referenceProvider.findReferences(
                    declarationFile.uri,
                    Position(declarationFile.classDefinition.range.start.line, 0),
                    false
                )
            }

            val correctCount = subclasses.count { childFile ->
                val superDirectiveLine = childFile.classDefinition.superClassRange?.start?.line
                refs.any {
                    it.uri == childFile.uri && it.range.start.line == superDirectiveLine
                }
            }

            results.add(DirectiveTestResult(
                className = className,
                directiveType = "super",
                expectedReferences = subclasses.size,
                actualReferences = correctCount,
                correctLineNumbers = correctCount == subclasses.size,
                searchTimeMs = searchTime,
            ))
        }
        
        // Print results
        println("\n   📊 Results:")
        results.forEach { result ->
            val status = if (result.correctLineNumbers && result.actualReferences > 0) "✅" else "❌"
            println("      $status ${result.className}")
            println("         References: ${result.actualReferences}")
            println("         Correct lines: ${result.correctLineNumbers}")
            println("         Search time: ${result.searchTimeMs}ms")
        }
        
        val slowestSearch = results.maxOfOrNull { it.searchTimeMs } ?: 0L

        assertTrue(results.isNotEmpty(), "${dataset.apkName}: should find super classes with subclasses")
        assertTrue(results.all { it.correctLineNumbers && it.actualReferences == it.expectedReferences },
            "${dataset.apkName}: every .super target should return all directive references on the correct lines")
        assertTrue(slowestSearch < 50,
            "${dataset.apkName}: .super reference searches should stay under 50ms, slowest was ${slowestSearch}ms")

        println("\n   ✅ Success Rate: 100.0% (${results.size}/${results.size})")
        println("   ✅ Slowest .super search: ${slowestSearch}ms")
    }
    
    private fun testImplementsDirectives(dataset: IndexedDataset) {
        println("   Indexed ${dataset.index.getAllFiles().size} files in ${dataset.scanDurationMs}ms")

        val testInterfaces = dataset.implementorsByInterface.entries
            .filter { it.value.size >= 2 }
            .sortedWith(compareByDescending<Map.Entry<String, List<SmaliFile>>> { it.value.size }.thenBy { it.key })
            .take(10)

        if (testInterfaces.isEmpty()) {
            println("   ⚠️  No workspace interfaces found with implementers, skipping test")
            return
        }

        val results = mutableListOf<DirectiveTestResult>()
        
        println("\n   Testing .implements references...")
        testInterfaces.forEach { (interfaceName, implementors) ->
            val declarationFile = dataset.index.findClass(interfaceName) ?: return@forEach
            var refs = emptyList<org.eclipse.lsp4j.Location>()
            val searchTime = measureTimeMillis {
                refs = dataset.referenceProvider.findReferences(
                    declarationFile.uri,
                    Position(declarationFile.classDefinition.range.start.line, 0),
                    false
                )
            }

            val correctCount = implementors.count { implFile ->
                val implementsLine = implFile.classDefinition.interfaceRanges[interfaceName]?.start?.line
                refs.any {
                    it.uri == implFile.uri && it.range.start.line == implementsLine
                }
            }

            results.add(DirectiveTestResult(
                className = interfaceName,
                directiveType = "implements",
                expectedReferences = implementors.size,
                actualReferences = correctCount,
                correctLineNumbers = correctCount == implementors.size,
                searchTimeMs = searchTime,
            ))
        }
        
        // Print results
        println("\n   📊 Results:")
        results.forEach { result ->
            val status = if (result.correctLineNumbers && result.actualReferences > 0) "✅" else "❌"
            println("      $status ${result.className}")
            println("         References: ${result.actualReferences}")
            println("         Correct lines: ${result.correctLineNumbers}")
            println("         Search time: ${result.searchTimeMs}ms")
        }
        
        val slowestSearch = results.maxOfOrNull { it.searchTimeMs } ?: 0L

        assertTrue(results.isNotEmpty(), "${dataset.apkName}: should find interfaces with implementors")
        assertTrue(results.all { it.correctLineNumbers && it.actualReferences == it.expectedReferences },
            "${dataset.apkName}: every .implements target should return all directive references on the correct lines")
        assertTrue(slowestSearch < 50,
            "${dataset.apkName}: .implements reference searches should stay under 50ms, slowest was ${slowestSearch}ms")

        println("\n   ✅ Success Rate: 100.0% (${results.size}/${results.size})")
        println("   ✅ Slowest .implements search: ${slowestSearch}ms")
    }
}
