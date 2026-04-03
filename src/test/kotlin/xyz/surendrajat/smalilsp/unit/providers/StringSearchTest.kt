package xyz.surendrajat.smalilsp.unit.providers

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.ConstStringInstruction
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StringSearchTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var parser: SmaliParser

    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        parser = SmaliParser()
    }

    private fun indexContent(uri: String, content: String) {
        val file = parser.parse(uri, content)
        assertNotNull(file, "Failed to parse $uri")
        index.indexFile(file)
    }

    @Test
    fun `const-string instruction is parsed`() {
        val content = """
            .class public Lcom/example/App;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public greet()V
                .registers 2
                const-string v0, "Hello, World!"
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/App.smali", content)

        val file = index.findClass("Lcom/example/App;")
        assertNotNull(file)
        val greet = file.methods.find { it.name == "greet" }
        assertNotNull(greet)

        val strings = greet.instructions.filterIsInstance<ConstStringInstruction>()
        assertEquals(1, strings.size)
        assertEquals("Hello, World!", strings[0].value)
        assertEquals("const-string", strings[0].opcode)
        assertEquals("v0", strings[0].register)
    }

    @Test
    fun `string index search finds matching strings`() {
        val content = """
            .class public Lcom/example/Config;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public getUrl()Ljava/lang/String;
                .registers 2
                const-string v0, "https://api.example.com/v1"
                return-object v0
            .end method
            .method public getKey()Ljava/lang/String;
                .registers 2
                const-string v0, "api_key_12345"
                return-object v0
            .end method
        """.trimIndent()
        indexContent("file:///test/Config.smali", content)

        // Search for "api"
        val results = index.searchStrings("api")
        assertEquals(2, results.size, "Both strings contain 'api'")

        // Search for "https"
        val httpsResults = index.searchStrings("https")
        assertEquals(1, httpsResults.size)
        assertEquals("https://api.example.com/v1", httpsResults[0].value)

        // Search for non-existent string
        val noResults = index.searchStrings("nonexistent")
        assertTrue(noResults.isEmpty())
    }

    @Test
    fun `string search is case insensitive`() {
        val content = """
            .class public Lcom/example/Logger;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public log()V
                .registers 2
                const-string v0, "ERROR: Something went wrong"
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Logger.smali", content)

        val results = index.searchStrings("error")
        assertEquals(1, results.size)
        assertEquals("ERROR: Something went wrong", results[0].value)
    }

    @Test
    fun `exact string location lookup`() {
        val content = """
            .class public Lcom/example/Multi;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public a()V
                .registers 2
                const-string v0, "shared_pref_key"
                return-void
            .end method
            .method public b()V
                .registers 2
                const-string v0, "shared_pref_key"
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Multi.smali", content)

        val locations = index.findStringLocations("shared_pref_key")
        assertEquals(2, locations.size, "Same string in two methods should have two locations")
    }

    @Test
    fun `stats include string count`() {
        val content = """
            .class public Lcom/example/Strs;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public a()V
                .registers 2
                const-string v0, "one"
                const-string v1, "two"
                const-string v0, "three"
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Strs.smali", content)

        val stats = index.getStats()
        assertEquals(3, stats.strings, "Should count 3 unique strings")
    }

    @Test
    fun `multiple files string search`() {
        val content1 = """
            .class public Lcom/a/A;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public a()V
                .registers 2
                const-string v0, "https://login.example.com"
                return-void
            .end method
        """.trimIndent()
        val content2 = """
            .class public Lcom/b/B;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public b()V
                .registers 2
                const-string v0, "https://api.example.com"
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/A.smali", content1)
        indexContent("file:///test/B.smali", content2)

        val results = index.searchStrings("example.com")
        assertEquals(2, results.size)
    }
}
