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

    private fun file(root: File, path: String, content: String): File =
        File(root, path).apply {
            parentFile.mkdirs()
            writeText(content)
        }
}
