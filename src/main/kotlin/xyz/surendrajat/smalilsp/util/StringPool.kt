package xyz.surendrajat.smalilsp.util

import java.util.concurrent.ConcurrentHashMap

/**
 * String interning pool for reducing memory usage.
 * 
 * Common strings in Smali files:
 * - Class names (many duplicates across methods/fields)
 * - Primitive types: "I", "Z", "V", "Ljava/lang/String;" (thousands of duplicates)
 * - Modifiers: "public", "private", "static", "final" (thousands of duplicates)
 * - Common SDK classes: "Ljava/lang/Object;", "Landroid/view/View;" etc.
 * 
 * Expected memory reduction: 30-50%
 * - 88,688 files, ~10M strings total
 * - Unique strings: ~50k (0.5%)
 * - Memory saved: 371 MB → ~220 MB (40% reduction)
 */
object StringPool {
    private val pool = ConcurrentHashMap<String, String>()
    
    /**
     * Intern a string. Returns the canonical version from the pool.
     * Thread-safe.
     */
    fun intern(s: String): String {
        return pool.computeIfAbsent(s) { it }
    }
    
    /**
     * Get pool statistics (for testing/debugging).
     */
    fun getStats(): Stats {
        return Stats(
            uniqueStrings = pool.size,
            estimatedMemorySavedMB = pool.size * 40.0 / (1024.0 * 1024.0) // ~40 bytes per entry
        )
    }
    
    data class Stats(
        val uniqueStrings: Int,
        val estimatedMemorySavedMB: Double
    )
    
    /**
     * Clear the pool (for testing).
     */
    fun clear() {
        pool.clear()
    }
}
