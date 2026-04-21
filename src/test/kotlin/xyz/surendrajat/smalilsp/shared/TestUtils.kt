package xyz.surendrajat.smalilsp.shared

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/**
 * Test utility functions for portable test data access.
 */
object TestUtils {
    /**
     * Get the project root directory (where build.gradle.kts is located).
     * Works regardless of working directory.
     */
    fun getProjectRoot(): File {
        // Start from this class file location and walk up to find project root
        val classFile = File(TestUtils::class.java.protectionDomain.codeSource.location.path)
        var current = classFile
        
        // Walk up until we find build.gradle.kts
        while (current != null && current.exists()) {
            if (File(current, "build.gradle.kts").exists()) {
                return current
            }
            current = current.parentFile
        }
        
        // Fallback: use system property if available
        System.getProperty("user.dir")?.let { return File(it) }
        
        error("Could not find project root. Please run tests from project directory.")
    }
    
    /**
     * Get the test-data directory containing decompiled APKs for testing.
     */
    fun getTestDataDir(): File {
        return File(getProjectRoot(), "test-data").also { dir ->
            require(dir.exists()) { "test-data directory not found: ${dir.absolutePath}. Run apktool to decompile APKs first." }
        }
    }
    
    /**
     * Get a specific APK decompiled directory.
     * Returns null if the APK or test-data directory doesn't exist (allows tests to skip gracefully).
     */
    fun getApk(name: String): File? {
        val testDataDir = File(getProjectRoot(), "test-data")
        if (!testDataDir.exists()) return null
        val apkDir = File(testDataDir, name)
        return if (apkDir.exists() && apkDir.isDirectory) apkDir else null
    }
    
    /**
     * Get Mastodon APK directory (4,415 files), or null if not available.
     */
    fun getMastodonApk(): File? = getApk("mastodon")

    /**
     * Require Mastodon APK directory, otherwise skip the current test.
     */
    fun requireMastodonApk(): File {
        val apk = getMastodonApk()
        assumeTrue(apk?.exists() == true, "Mastodon APK not available — skipping")
        return apk!!
    }
    
    /**
     * Get ProtonMail APK directory (18,249 files), or null if not available.
     */
    fun getProtonMailApk(): File? = getApk("protonmail")

    /**
     * Require ProtonMail APK directory, otherwise skip the current test.
     */
    fun requireProtonMailApk(): File {
        val apk = getProtonMailApk()
        assumeTrue(apk?.exists() == true, "ProtonMail APK not available — skipping")
        return apk!!
    }
    
    /**
     * Get all available APK directories for stress testing.
     * Returns list of available APK directories for testing.
     */
    fun getAllApks(): List<File> {
        return listOfNotNull(
            getMastodonApk(),
            getProtonMailApk(),
        )
    }
    
    /**
     * Get count of smali files in an APK directory.
     */
    fun countSmaliFiles(apkDir: File): Int {
        return apkDir.walkTopDown()
            .filter { it.extension == "smali" }
            .count()
    }
}
