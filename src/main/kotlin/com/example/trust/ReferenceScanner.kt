package com.example.trust

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers "does anything else in this project mention this file", using the IDE's word index.
 *
 * This is the half that coverage cannot supply. A coverage report says a file never ran; it
 * cannot say whether that is because nobody tested it or because nobody uses it. The word
 * index says whether the name appears anywhere else. Neither answer is worth much alone, and
 * together they are the difference between "write a test" and "delete this".
 *
 * Deliberately conservative in one direction. Being wrong about "referenced" costs nothing:
 * the file stays in the untested pile where it was already. Being wrong about "unreferenced"
 * tells somebody to delete working code, so every doubt resolves to [Reachability.REFERENCED]
 * or [Reachability.UNKNOWN], never to dead.
 */
class ReferenceScanner(private val project: Project) {

    private val logger = Logger.getInstance(ReferenceScanner::class.java)

    /** Keyed by project-relative path. Cleared whenever a fresh report is read. */
    private val cache = ConcurrentHashMap<String, Reachability>()

    fun invalidate() = cache.clear()

    /**
     * Classifies only the files that could possibly be dead.
     *
     * A file with even one executed line is alive by definition, so asking the index about it
     * would be work spent to confirm something already known. On a real project that keeps
     * this to a few dozen lookups instead of a few thousand.
     */
    fun classify(files: Collection<FileTrust>): Map<String, Reachability> {
        val candidates = files.filter { it.totalLines > 0 && it.unprovenLines == it.totalLines }
        if (candidates.isEmpty()) return emptyMap()

        // The indexes are not available in dumb mode, and a lookup that silently returns
        // nothing would read as "nothing references this", which is the one wrong answer
        // this class must never give.
        if (DumbService.getInstance(project).isDumb) {
            return candidates.associate { it.path to Reachability.UNKNOWN }
        }

        return candidates.take(MAX_LOOKUPS).associate { file ->
            file.path to cache.getOrPut(file.path) { lookUp(file) }
        }
    }

    private fun lookUp(file: FileTrust): Reachability {
        val name = file.fileName.substringBeforeLast('.')

        // Entry points are started by a runner, a web server or a build tool, so no file in
        // the project imports them and the index is simply the wrong question to ask.
        if (name.lowercase() in ENTRY_POINTS) return Reachability.REFERENCED

        // A one or two character name matches half the codebase and proves nothing.
        if (name.length < MIN_NAME) return Reachability.UNKNOWN

        return runCatching {
            ReadAction.compute<Reachability, RuntimeException> {
                if (project.isDisposed) return@compute Reachability.UNKNOWN
                var foundElsewhere = false

                PsiSearchHelper.getInstance(project).processElementsWithWord(
                    { element, _ ->
                        val inFile = element.containingFile?.virtualFile?.path
                        // Its own name inside itself is not a reference to it.
                        if (inFile != null && !inFile.endsWith(file.path)) {
                            foundElsewhere = true
                        }
                        // Stop at the first hit somewhere else: one caller is all it takes.
                        !foundElsewhere
                    },
                    GlobalSearchScope.projectScope(project),
                    name,
                    // Code and strings only. A name in a comment is a note about the file,
                    // not something that would break if the file went away.
                    (UsageSearchContext.IN_CODE.toInt() or UsageSearchContext.IN_STRINGS.toInt()).toShort(),
                    true,
                )

                if (foundElsewhere) Reachability.REFERENCED else Reachability.UNREFERENCED
            }
        }.getOrElse {
            logger.warn("Nexus Trust could not search for ${file.path}", it)
            Reachability.UNKNOWN
        }
    }

    private companion object {
        /** Names a runner invokes directly, which nothing in the project would ever import. */
        val ENTRY_POINTS = setOf(
            "main", "__main__", "__init__", "index", "app", "application", "server",
            "setup", "conftest", "manage", "wsgi", "asgi", "cli", "run",
        )

        const val MIN_NAME = 4

        /** A ceiling, so a pathological project cannot turn the tab into a spinner. */
        const val MAX_LOOKUPS = 400
    }
}
