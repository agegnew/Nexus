package com.example.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The parser, against the two report shapes it will actually meet.
 *
 * No IDE fixture here on purpose: parsing a text file and turning hit counts into line ranges is
 * ordinary logic, and logic that needs a running IDE to be tested does not get tested.
 */
class CoverageReportSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val source = CoverageReportSource()

    @Test
    fun `cobertura misses become unproven ranges`() {
        val root = temp.newFolder("project")
        write(root, "app/main.py", lines = 12)

        val report = file(
            root, "coverage.xml",
            """
            <coverage>
              <packages><package name="app"><classes>
                <class filename="app/main.py">
                  <lines>
                    <line number="1" hits="4"/>
                    <line number="2" hits="1"/>
                    <line number="7" hits="0"/>
                    <line number="8" hits="0"/>
                    <line number="9" hits="0"/>
                    <line number="12" hits="0"/>
                  </lines>
                </class>
              </classes></package></packages>
            </coverage>
            """.trimIndent(),
        )

        val trust = source.parse(report, root).getValue("app/main.py")

        // 7-9 are consecutive and collapse into one band; 12 stands alone.
        assertEquals(listOf(LineRange(7, 9), LineRange(12, 12)), trust.unproven)
        assertEquals(4, trust.unprovenLines)
        // Six executable lines were reported, four of them never ran.
        assertEquals(6, trust.totalLines)
        assertEquals(66, trust.percentUnproven())
    }

    @Test
    fun `a line executed anywhere counts as proven`() {
        val root = temp.newFolder("project")
        write(root, "app/main.py", lines = 5)

        // Merged reports mention the same line twice, once from each run.
        val report = file(
            root, "coverage.xml",
            """
            <coverage><class filename="app/main.py"><lines>
              <line number="3" hits="0"/>
              <line number="3" hits="2"/>
            </lines></class></coverage>
            """.trimIndent(),
        )

        assertTrue(source.parse(report, root).getValue("app/main.py").isClean)
    }

    @Test
    fun `lcov records are read per file`() {
        val root = temp.newFolder("project")
        write(root, "web/src/api.ts", lines = 30)
        write(root, "web/src/cart.ts", lines = 30)

        val report = file(
            root, "lcov.info",
            """
            SF:web/src/api.ts
            DA:1,5
            DA:2,0
            DA:3,0
            end_of_record
            SF:web/src/cart.ts
            DA:1,1
            end_of_record
            """.trimIndent(),
        )

        val parsed = source.parse(report, root)

        assertEquals(listOf(LineRange(2, 3)), parsed.getValue("web/src/api.ts").unproven)
        assertTrue(parsed.getValue("web/src/cart.ts").isClean)
    }

    @Test
    fun `a report written inside a sub folder still finds its files`() {
        val root = temp.newFolder("project")
        write(root, "backend/app/orders.py", lines = 20)

        // `coverage xml` run from backend/ names app/orders.py, not backend/app/orders.py.
        val report = file(
            root, "backend/coverage.xml",
            """
            <coverage><class filename="app/orders.py"><lines>
              <line number="4" hits="0"/>
            </lines></class></coverage>
            """.trimIndent(),
        )

        val parsed = source.parse(report, root)

        assertEquals(setOf("backend/app/orders.py"), parsed.keys)
    }

    @Test
    fun `a file edited after the run is flagged, not silently trusted`() {
        val root = temp.newFolder("project")
        val source_ = write(root, "app/main.py", lines = 10)

        val report = file(
            root, "coverage.xml",
            """
            <coverage><class filename="app/main.py"><lines>
              <line number="2" hits="0"/>
            </lines></class></coverage>
            """.trimIndent(),
        )
        report.setLastModified(System.currentTimeMillis() - 60_000)
        source_.setLastModified(System.currentTimeMillis())

        assertTrue(source.parse(report, root).getValue("app/main.py").changedSinceRun)
    }

    @Test
    fun `a path that exists nowhere is dropped rather than guessed at`() {
        val root = temp.newFolder("project")

        val report = file(
            root, "coverage.xml",
            """
            <coverage><class filename="/ci/build/gone.py"><lines>
              <line number="1" hits="0"/>
            </lines></class></coverage>
            """.trimIndent(),
        )

        assertTrue(source.parse(report, root).isEmpty())
    }

    private fun write(root: File, path: String, lines: Int): File =
        file(root, path, (1..lines).joinToString("\n") { "# line $it" })

    private fun file(root: File, path: String, content: String): File =
        File(root, path).apply {
            parentFile.mkdirs()
            writeText(content)
        }
}
