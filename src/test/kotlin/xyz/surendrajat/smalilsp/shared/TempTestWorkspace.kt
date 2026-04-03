package xyz.surendrajat.smalilsp.shared

import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

import xyz.surendrajat.smalilsp.shared.TempTestWorkspace
/**
 * Test utility for creating multi-file workspaces with realistic structure.
 * 
 * This allows testing cross-file scenarios like:
 * - Goto definition across files
 * - Hover information with file lookups
 * - Find all references spanning multiple files
 * - Real workspace indexing and lookup
 * 
 * Example:
 * ```kotlin
 * val workspace = TempTestWorkspace()
 * workspace.addFile("Base.smali", ".class public LBase;\n.super Ljava/lang/Object;")
 * workspace.addFile("Derived.smali", ".class public LDerived;\n.super LBase;")
 * val index = workspace.buildIndex()
 * ```
 */
class TempTestWorkspace {
    private val tempDir: Path = Files.createTempDirectory("smali-lsp-test-")
    private val files = mutableMapOf<String, String>()
    private val parser = SmaliParser()
    
    /**
     * Add a file to the workspace.
     * 
     * @param relativePath Path relative to workspace root (e.g., "com/example/MyClass.smali")
     * @param content Full Smali source code
     */
    fun addFile(relativePath: String, content: String) {
        files[relativePath] = content
        
        // Write to temp directory for realistic file:// URIs
        val filePath = tempDir.resolve(relativePath)
        filePath.parent?.let { Files.createDirectories(it) }
        Files.writeString(filePath, content)
    }
    
    /**
     * Get file content by relative path.
     */
    fun getContent(relativePath: String): String {
        return files[relativePath] ?: throw IllegalArgumentException("File not found: $relativePath")
    }
    
    /**
     * Get file:// URI for a relative path.
     */
    fun getUri(relativePath: String): String {
        return tempDir.resolve(relativePath).toUri().toString()
    }
    
    /**
     * Get absolute file path.
     */
    fun getPath(relativePath: String): Path {
        return tempDir.resolve(relativePath)
    }
    
    /**
     * Parse a file in the workspace.
     */
    fun parseFile(relativePath: String): SmaliFile {
        val content = getContent(relativePath)
        val uri = getUri(relativePath)
        return parser.parse(uri, content)
            ?: throw IllegalStateException("Failed to parse $relativePath")
    }
    
    /**
     * Build a WorkspaceIndex from all files in the workspace.
     * 
     * This simulates the real indexing process:
     * 1. Parse all .smali files
     * 2. Index each file's declarations
     * 3. Return fully populated index
     * 
     * @return WorkspaceIndex with all files indexed
     */
    fun buildIndex(): WorkspaceIndex {
        val index = WorkspaceIndex()
        
        for ((relativePath, content) in files) {
            val uri = getUri(relativePath)
            val smaliFile = parser.parse(uri, content)
                ?: throw IllegalStateException("Failed to parse $relativePath")
            index.indexFile(smaliFile)
        }
        
        return index
    }
    
    /**
     * Get workspace root directory.
     */
    fun getRootDir(): File {
        return tempDir.toFile()
    }
    
    /**
     * Clean up temporary files.
     * Should be called in @AfterEach or try-finally block.
     */
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }
    
    companion object {
        /**
         * Create a new test workspace.
         */
        fun create(): TempTestWorkspace {
            return TempTestWorkspace()
        }
        
        /**
         * Create workspace with standard base classes pre-loaded.
         * 
         * Includes:
         * - java/lang/Object.smali
         * - java/lang/String.smali
         * - android/app/Activity.smali
         * 
         * Useful for tests that need common Android/Java types.
         */
        fun createWithStandardClasses(): TempTestWorkspace {
            val workspace = TempTestWorkspace()
            
            workspace.addFile("java/lang/Object.smali", """
                .class public Ljava/lang/Object;
                .super Ljava/lang/Object;
                
                .method public constructor <init>()V
                    .registers 1
                    return-void
                .end method
                
                .method public toString()Ljava/lang/String;
                    .registers 1
                    const/4 v0, 0x0
                    return-object v0
                .end method
                
                .method public equals(Ljava/lang/Object;)Z
                    .registers 2
                    const/4 v0, 0x0
                    return v0
                .end method
            """.trimIndent())
            
            workspace.addFile("java/lang/String.smali", """
                .class public final Ljava/lang/String;
                .super Ljava/lang/Object;
                
                .method public length()I
                    .registers 1
                    const/4 v0, 0x0
                    return v0
                .end method
                
                .method public charAt(I)C
                    .registers 2
                    const/4 v0, 0x0
                    return v0
                .end method
            """.trimIndent())
            
            workspace.addFile("android/app/Activity.smali", """
                .class public Landroid/app/Activity;
                .super Ljava/lang/Object;
                
                .method protected onCreate(Landroid/os/Bundle;)V
                    .registers 2
                    return-void
                .end method
                
                .method public finish()V
                    .registers 1
                    return-void
                .end method
            """.trimIndent())
            
            return workspace
        }
    }
}
