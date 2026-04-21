package xyz.surendrajat.smalilsp.shared

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

object PerformanceTestLock {
    private val lockPath = TestUtils.getProjectRoot().toPath().resolve("build/test-performance.lock")

    fun <T> withExclusiveLock(owner: String, block: () -> T): T {
        Files.createDirectories(lockPath.parent)

        FileChannel.open(lockPath, CREATE, WRITE).use { channel ->
            val lock = channel.lock()
            try {
                println("[perf-lock] $owner")
                return block()
            } finally {
                lock.release()
            }
        }
    }
}