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

    // Max pool size — safety valve against pathological inputs.
    // Normal workspaces top out at ~100K entries (~10MB).
    private const val MAX_SIZE = 500_000

    init {
        // Pre-intern strings that appear thousands of times across smali files.
        // Avoids hash lookups on the hottest paths during parsing.
        val common = listOf(
            // Modifiers
            "public", "private", "protected", "static", "final",
            "abstract", "synthetic", "bridge", "varargs", "native",
            "transient", "volatile", "synchronized", "strictfp", "enum",
            "interface", "annotation", "constructor",
            // Primitive types and void
            "V", "I", "Z", "J", "B", "C", "S", "F", "D",
            // Very common descriptors
            "()V", "(I)V", "(Z)V", "()I", "()Z", "()Ljava/lang/String;",
            // Common method names
            "<init>", "<clinit>", "toString", "hashCode", "equals",
            // Common SDK types
            "Ljava/lang/Object;", "Ljava/lang/String;", "Ljava/lang/Class;",
            "Ljava/lang/Throwable;", "Ljava/lang/Exception;",
            "Ljava/lang/Runnable;", "Ljava/lang/StringBuilder;",
            "Landroid/content/Context;", "Landroid/view/View;",
            "Landroid/os/Bundle;", "Landroid/app/Activity;"
        )
        for (s in common) pool[s] = s
    }

    /**
     * Intern a string. Returns the canonical version from the pool.
     * Thread-safe. Stops interning beyond MAX_SIZE (returns input as-is).
     */
    fun intern(s: String): String {
        // Fast path: already in pool
        val existing = pool[s]
        if (existing != null) return existing

        // Safety valve: stop growing beyond limit
        if (pool.size >= MAX_SIZE) return s

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
