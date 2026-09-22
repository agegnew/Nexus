package com.example.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModuleDetectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(path: String, content: String = "{}"): File {
        val file = File(temp.root, path)
        file.parentFile.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun `a react package is a frontend module`() {
        write("web/package.json", """{"dependencies": {"react": "^18.0.0", "react-dom": "^18.0.0"}}""")

        val web = ModuleDetector.detect(temp.root).single { it.path == "web" }

        assertEquals("frontend", web.kind)
        assertEquals("web", web.name)
    }

    @Test
    fun `an express package is a backend module`() {
        write("api/package.json", """{"dependencies": {"express": "^4.0.0"}}""")

        assertEquals("backend", ModuleDetector.detect(temp.root).single { it.path == "api" }.kind)
    }

    @Test
    fun `a package with both is fullstack`() {
        write("app/package.json", """{"dependencies": {"next": "^14.0.0", "prisma": "^5.0.0"}}""")

        assertEquals("fullstack", ModuleDetector.detect(temp.root).single { it.path == "app" }.kind)
    }

    @Test
    fun `gradle maven python go and rust projects are backends`() {
        write("jvm/build.gradle.kts", "")
        write("maven/pom.xml", "<project/>")
        write("py/requirements.txt", "flask")
        write("go/go.mod", "module x")
        write("rs/Cargo.toml", "[package]")

        val kinds = ModuleDetector.detect(temp.root)
            .filter { it.path.isNotEmpty() }
            .associate { it.path to it.kind }

        assertEquals(
            mapOf("jvm" to "backend", "maven" to "backend", "py" to "backend", "go" to "backend", "rs" to "backend"),
            kinds
        )
    }

    @Test
    fun `a project with no manifest still gets a root module`() {
        write("notes.md", "hello")

        val modules = ModuleDetector.detect(temp.root)

        assertEquals(1, modules.size)
        assertEquals("", modules.single().path)
        // Without a root module nothing would own the files, and every scope would come back empty.
        assertNotNull(ScopeFilter.owner("notes.md", modules))
    }

    @Test
    fun `build output and dependency directories are skipped`() {
        write("web/package.json", """{"dependencies": {"react": "^18.0.0"}}""")
        write("web/node_modules/left-pad/package.json", """{"name": "left-pad"}""")
        write("build/package.json", """{"name": "output"}""")
        write(".git/package.json", """{"name": "nope"}""")

        val paths = ModuleDetector.detect(temp.root).map { it.path }

        assertTrue("node_modules leaked in: $paths", paths.none { it.contains("node_modules") })
        assertTrue("build leaked in: $paths", paths.none { it.startsWith("build") })
        assertTrue(".git leaked in: $paths", paths.none { it.startsWith(".git") })
    }

    @Test
    fun `this project is detected as a kotlin backend plus a react frontend`() {
        val root = File(".").absoluteFile
            .let { generateSequence(it) { parent -> parent.parentFile }.first { File(it, ".git").exists() } }

        val modules = ModuleDetector.detect(root)
        val scopes = ScopeFilter.options(modules).map { it.id }

        val rootModule = modules.single { it.path.isEmpty() }
        assertEquals("backend", rootModule.kind)
        // A "project/." style path must not leave the root module named ".".
        assertEquals("plugin-test", rootModule.name)
        assertEquals("frontend", modules.single { it.path == "visualizer-ui" }.kind)
        assertTrue("Expected both buckets in $scopes", scopes.containsAll(listOf("all", "frontend", "backend")))
    }
}
