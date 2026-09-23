package com.example.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * How the one button decides what to run.
 *
 * The guess is shown to the user before it runs, so a wrong guess costs a glance. A guess
 * that silently ran the wrong thing would cost a minute and then change nothing, which is
 * exactly the "the button does nothing" experience this replaces.
 */
class TrustRunnerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the project's own command wins over any guess`() {
        val root = temp.newFolder("project")
        file(root, "requirements.txt", "pytest")
        file(root, ".nexus/trust.json", """{ "autoEnable": true, "command": "make coverage" }""")

        val command = TrustRunner.commandFor(root)!!

        assertEquals("make coverage", command.command)
        assertTrue(command.origin.contains(".nexus/trust.json"))
    }

    @Test
    fun `a python project with a virtualenv runs that interpreter`() {
        val root = temp.newFolder("project")
        file(root, "pyproject.toml", "[tool.pytest]")
        file(root, ".venv/bin/python", "#!/bin/sh").setExecutable(true)

        val command = TrustRunner.commandFor(root)!!

        assertTrue(command.command.startsWith(File(root, ".venv/bin/python").path))
        assertTrue(command.command.contains("coverage run -m pytest"))
        assertTrue(command.command.contains("coverage xml"))
    }

    @Test
    fun `a python project without a virtualenv falls back to python3`() {
        val root = temp.newFolder("project")
        file(root, "requirements.txt", "")

        assertTrue(TrustRunner.commandFor(root)!!.command.startsWith("python3 -m coverage run"))
    }

    @Test
    fun `vitest and jest are told apart from package json`() {
        val vitest = temp.newFolder("vitest")
        file(vitest, "package.json", """{ "devDependencies": { "vitest": "^2" } }""")
        val jest = temp.newFolder("jest")
        file(jest, "package.json", """{ "devDependencies": { "jest": "^29" } }""")

        assertTrue(TrustRunner.commandFor(vitest)!!.command.contains("vitest run"))
        assertTrue(TrustRunner.commandFor(vitest)!!.command.contains("lcov"))
        assertTrue(TrustRunner.commandFor(jest)!!.command.contains("jest --coverage"))
    }

    @Test
    fun `a failing test must not stop the report from being written`() {
        val py = temp.newFolder("py")
        file(py, "requirements.txt", "")
        val js = temp.newFolder("js")
        file(js, "package.json", """{ "devDependencies": { "vitest": "^4" } }""")

        // pytest exits non-zero on any failure; && would skip the xml step entirely.
        val python = TrustRunner.commandFor(py)!!.command
        assertTrue(python, python.contains("; ") && !python.contains("&&"))

        // vitest writes no report on failure unless told to.
        assertTrue(TrustRunner.commandFor(js)!!.command.contains("--coverage.reportOnFailure"))
    }

    @Test
    fun `an unrecognised project gets no guess rather than a wrong one`() {
        val root = temp.newFolder("project")
        file(root, "build.gradle.kts", "plugins {}")

        assertNull(TrustRunner.commandFor(root))
    }

    @Test
    fun `a blank configured command is ignored`() {
        val root = temp.newFolder("project")
        file(root, "requirements.txt", "")
        file(root, ".nexus/trust.json", """{ "command": "   " }""")

        // Falls through to the guess instead of trying to run whitespace.
        assertTrue(TrustRunner.commandFor(root)!!.command.contains("pytest"))
    }

    /**
     * The shape that made this button useless: a repository whose root holds no manifest at all
     * because the Python lives in backend/. CoverageReportSource already reads backend/coverage.xml
     * on such a project, so the tab showed real coverage above a button that said it did not know
     * how to produce any.
     */
    @Test
    fun `a project whose tests live in a subdirectory is still recognised`() {
        val root = temp.newFolder("project")
        file(root, "README.md", "a repo with no manifest at its root")
        file(root, "backend/requirements.txt", "fastapi\n")
        file(root, "backend/pytest.ini", "[pytest]\n")
        file(root, "frontend/index.html", "<!doctype html>")

        val command = TrustRunner.commandFor(root)!!

        assertTrue(command.command.contains("pytest"))
        // The command has to RUN there too, or it writes its report next to the wrong tests.
        assertEquals("backend", command.directory)
        assertTrue("the origin should say where it looked: ${command.origin}", command.origin.contains("backend"))
    }

    /** The root wins when both could answer, because that is where a person would look first. */
    @Test
    fun `the root is preferred over a subdirectory`() {
        val root = temp.newFolder("project")
        file(root, "requirements.txt", "fastapi\n")
        file(root, "backend/requirements.txt", "fastapi\n")

        assertEquals("", TrustRunner.commandFor(root)!!.directory)
    }

    /** A virtual environment at the repository root serves tests that live a folder down. */
    @Test
    fun `a venv at the root is used by tests in a subdirectory`() {
        val root = temp.newFolder("project")
        file(root, "backend/requirements.txt", "fastapi\n")
        val python = file(root, ".venv/bin/python", "#!/bin/sh\n")
        python.setExecutable(true)

        assertTrue(TrustRunner.commandFor(root)!!.command.contains(python.path))
    }

    private fun file(root: File, path: String, content: String): File =
        File(root, path).apply {
            parentFile.mkdirs()
            writeText(content)
        }
}
