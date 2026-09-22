package com.example.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeFilterTest {

    private fun module(id: String, name: String, path: String, kind: String) =
        ActivityModule(id, name, path, kind)

    private val modules = listOf(
        module("root", "plugin-test", "", "library"),
        module("ui", "visualizer-ui", "visualizer-ui", "frontend"),
        module("plugin", "plugin", "src/main/kotlin", "backend"),
        module("worker", "worker", "services/worker", "backend")
    )

    private fun commit(hash: String, vararg files: String) = Commit(
        hash = hash.padEnd(40, '0'),
        shortHash = hash.take(7),
        author = "agegnew",
        email = "agegnew.mersha@gmail.com",
        date = "2026-09-21",
        time = "21:22",
        timestamp = 1790000000L,
        subject = "work",
        body = "",
        merge = false,
        files = files.map { FileChange(it, 1, 0) }
    )

    @Test
    fun `a file belongs to the deepest matching module`() {
        assertEquals("ui", ScopeFilter.owner("visualizer-ui/src/App.jsx", modules)?.id)
        assertEquals("plugin", ScopeFilter.owner("src/main/kotlin/com/example/Foo.kt", modules)?.id)
        assertEquals("worker", ScopeFilter.owner("services/worker/main.py", modules)?.id)
        // Nothing deeper claims it, so the root module owns it.
        assertEquals("root", ScopeFilter.owner("README.md", modules)?.id)
    }

    @Test
    fun `a prefix must stop at a path boundary`() {
        // "visualizer-ui-legacy" must not be swallowed by the "visualizer-ui" module.
        assertEquals("root", ScopeFilter.owner("visualizer-ui-legacy/index.js", modules)?.id)
    }

    @Test
    fun `frontend scope keeps only frontend commits and files`() {
        val commits = listOf(
            commit("aaaaaaa", "visualizer-ui/src/App.jsx", "src/main/kotlin/com/example/Panel.kt"),
            commit("bbbbbbb", "src/main/kotlin/com/example/Other.kt"),
            commit("ccccccc", "visualizer-ui/src/model.js")
        )

        val frontend = ScopeFilter.apply(commits, modules, "frontend")

        assertEquals(listOf("aaaaaaa", "ccccccc"), frontend.map { it.shortHash })
        // The backend file is dropped from the mixed commit so the summary stays on topic.
        assertEquals(listOf("visualizer-ui/src/App.jsx"), frontend.first().files.map { it.path })
    }

    @Test
    fun `backend scope covers every backend module`() {
        val commits = listOf(
            commit("aaaaaaa", "services/worker/main.py"),
            commit("bbbbbbb", "src/main/kotlin/com/example/Panel.kt"),
            commit("ccccccc", "visualizer-ui/src/App.jsx")
        )

        assertEquals(listOf("aaaaaaa", "bbbbbbb"), ScopeFilter.apply(commits, modules, "backend").map { it.shortHash })
    }

    @Test
    fun `a single module can be selected`() {
        val commits = listOf(
            commit("aaaaaaa", "services/worker/main.py"),
            commit("bbbbbbb", "src/main/kotlin/com/example/Panel.kt")
        )

        val worker = ScopeFilter.apply(commits, modules, "${ActivityRequest.MODULE_PREFIX}worker")
        assertEquals(listOf("aaaaaaa"), worker.map { it.shortHash })
    }

    @Test
    fun `all scope passes everything through untouched`() {
        val commits = listOf(commit("aaaaaaa", "visualizer-ui/src/App.jsx", "README.md"))
        assertEquals(commits, ScopeFilter.apply(commits, modules, ActivityRequest.ALL))
    }

    @Test
    fun `options offer all frontend backend and each module`() {
        val ids = ScopeFilter.options(modules).map { it.id }
        assertEquals("all", ids.first())
        assertTrue(ids.containsAll(listOf("frontend", "backend", "module:worker", "module:ui")))
    }

    @Test
    fun `a backend only project offers no frontend option`() {
        val backendOnly = listOf(module("plugin", "plugin", "", "backend"))
        assertTrue(ScopeFilter.options(backendOnly).none { it.id == "frontend" })
    }

    @Test
    fun `themes can name the modules a commit touched`() {
        val scopes = ScopeFilter.scopesOf(commit("aaaaaaa", "visualizer-ui/src/App.jsx", "services/worker/main.py"), modules)
        assertEquals(listOf("visualizer-ui", "worker"), scopes)
    }
}
