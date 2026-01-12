rootProject.name = "smali-lsp"

// Exclude mcp-wrapper from Gradle build (it has its own package.json/tsconfig)
if (file("mcp-wrapper").exists()) {
    gradle.beforeProject {
        if (projectDir.name == "mcp-wrapper") {
            throw StopExecutionException()
        }
    }
}
