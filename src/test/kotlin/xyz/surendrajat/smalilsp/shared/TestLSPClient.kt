package xyz.surendrajat.smalilsp.shared

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import xyz.surendrajat.smalilsp.shared.TestLSPClient
/**
 * Test implementation of LSP LanguageClient for E2E testing.
 * Records all notifications and messages received from the server.
 */
class TestLSPClient : LanguageClient {
    
    // All notifications received (for inspection)
    private val receivedNotifications = mutableListOf<Any>()
    
    // Diagnostics by URI
    private val receivedDiagnostics = ConcurrentHashMap<String, List<Diagnostic>>()
    
    // Log messages
    private val receivedLogMessages = mutableListOf<MessageParams>()
    
    // Show messages
    private val receivedShowMessages = mutableListOf<MessageParams>()
    
    // Telemetry events
    private val receivedTelemetry = mutableListOf<Any>()
    
    // Progress notifications
    private val receivedProgress = mutableListOf<ProgressParams>()
    
    override fun publishDiagnostics(params: PublishDiagnosticsParams) {
        receivedNotifications.add(params)
        receivedDiagnostics[params.uri] = params.diagnostics
    }
    
    override fun logMessage(params: MessageParams) {
        receivedLogMessages.add(params)
    }
    
    override fun showMessage(params: MessageParams) {
        receivedShowMessages.add(params)
    }
    
    override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> {
        receivedShowMessages.add(MessageParams(params.type, params.message))
        return CompletableFuture.completedFuture(null)
    }
    
    override fun telemetryEvent(obj: Any) {
        receivedTelemetry.add(obj)
    }
    
    override fun createProgress(params: WorkDoneProgressCreateParams): CompletableFuture<Void> {
        return CompletableFuture.completedFuture(null)
    }
    
    override fun notifyProgress(params: ProgressParams) {
        receivedProgress.add(params)
    }
    
    // ===== Helper methods for tests =====
    
    /**
     * Get diagnostics for a URI (returns empty list if none)
     */
    fun getDiagnostics(uri: String): List<Diagnostic> = 
        receivedDiagnostics[uri] ?: emptyList()
    
    /**
     * Wait for diagnostics to be published for a URI.
     * Throws TimeoutException if diagnostics don't arrive in time.
     */
    fun waitForDiagnostics(uri: String, timeoutMs: Long = 5000): List<Diagnostic> {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            receivedDiagnostics[uri]?.let { return it }
            Thread.sleep(50)
        }
        throw TimeoutException("No diagnostics received for $uri within ${timeoutMs}ms")
    }
    
    /**
     * Wait for diagnostics to be cleared (empty list published)
     */
    fun waitForDiagnosticsCleared(uri: String, timeoutMs: Long = 5000) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val diags = receivedDiagnostics[uri]
            if (diags != null && diags.isEmpty()) return
            Thread.sleep(50)
        }
        throw TimeoutException("Diagnostics not cleared for $uri within ${timeoutMs}ms")
    }
    
    /**
     * Get all log messages at a specific level
     */
    fun getLogMessages(type: MessageType): List<String> = 
        receivedLogMessages
            .filter { it.type == type }
            .map { it.message }
    
    /**
     * Get all error log messages
     */
    fun getErrorMessages(): List<String> = getLogMessages(MessageType.Error)
    
    /**
     * Get all warning log messages
     */
    fun getWarningMessages(): List<String> = getLogMessages(MessageType.Warning)
    
    /**
     * Get all info log messages
     */
    fun getInfoMessages(): List<String> = getLogMessages(MessageType.Info)
    
    /**
     * Clear all recorded notifications/messages
     */
    fun clearAll() {
        receivedNotifications.clear()
        receivedDiagnostics.clear()
        receivedLogMessages.clear()
        receivedShowMessages.clear()
        receivedTelemetry.clear()
        receivedProgress.clear()
    }
    
    /**
     * Clear diagnostics for a specific URI
     */
    fun clearDiagnostics(uri: String) {
        receivedDiagnostics.remove(uri)
    }
    
    /**
     * Get count of all notifications received
     */
    fun getNotificationCount(): Int = receivedNotifications.size
    
    /**
     * Get all URIs that have received diagnostics
     */
    fun getDiagnosticUris(): Set<String> = receivedDiagnostics.keys.toSet()
    
    /**
     * Check if any errors were logged
     */
    fun hasErrors(): Boolean = receivedLogMessages.any { it.type == MessageType.Error }
    
    /**
     * Get summary of all received data (for debugging)
     */
    fun getSummary(): String = buildString {
        appendLine("TestLSPClient Summary:")
        appendLine("  Total notifications: ${receivedNotifications.size}")
        appendLine("  Diagnostics for ${receivedDiagnostics.size} files:")
        receivedDiagnostics.forEach { (uri, diags) ->
            appendLine("    $uri: ${diags.size} diagnostics")
        }
        appendLine("  Log messages: ${receivedLogMessages.size} (${getErrorMessages().size} errors)")
        appendLine("  Show messages: ${receivedShowMessages.size}")
        appendLine("  Telemetry events: ${receivedTelemetry.size}")
    }
}
