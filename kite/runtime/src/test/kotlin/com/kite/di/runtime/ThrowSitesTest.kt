package com.kite.di.runtime

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every place the runtime can throw at an application, and why it may (ADR 15).
 *
 * A failure the build can rule out must be ruled out there; what is left is listed
 * here with the reason it cannot be. A new `throw` fails this test until it is
 * classified — "it can't happen" has to name the check that makes it so.
 */
class ThrowSitesTest {

    private enum class Why {
        /** Only reachable through `com.kitedi:test` (`@KiteTestApi`). */
        TEST_ONLY,

        /** A build error first: the check named in [Site.reason] makes this unreachable. */
        COMPILE_TIME,

        /** Only behind `@UncheckedLookup`: the call site opted in to a runtime failure. */
        UNCHECKED,

        /** Reachable, and the build cannot see why — reported through `KiteConfig.onError`. */
        RESIDUAL,

        /** Not a failure of its own: rethrows one already classified. */
        RETHROW,
    }

    private class Site(val file: String, val marker: String, val why: Why, val reason: String)

    private val sites = listOf(
        Site("Container.kt", "Duplicate binding", Why.COMPILE_TIME,
            "V2 in one module, MergeValidator conflicts across siblings, the downstream 'already bound' check; " +
                "reachable only with registries from different Kite versions, which the plugin pins"),
        Site("Container.kt", "nothing in the graph provides it", Why.TEST_ONLY, "replace<T>"),
        Site("Container.kt", "pass the test's value to Graph.start", Why.TEST_ONLY, "replace<T>"),
        Site("Container.kt", "No open scope", Why.COMPILE_TIME,
            "accessors and ViewModel adapters exist only on receivers that have the binding's reach " +
                "(AccessorTest, V4 ScopeReachTest); otherwise an @UncheckedLookup"),
        Site("Container.kt", "No open custom scope", Why.RESIDUAL,
            "a lookup into a scope of the application's own while none is open: the build cannot see the order of calls"),
        Site("Container.kt", "No binding for", Why.COMPILE_TIME,
            "an accessor exists only for a key with a binding (AccessorTest); otherwise an @UncheckedLookup"),
        Site("Kite.kt", "with different arguments", Why.RESIDUAL,
            "two Graph.start calls with different values: the build cannot see the order of calls"),
        Site("Kite.kt", "throw e", Why.RETHROW, "KiteConfig.onError sees it, then it propagates"),
        Site("Kite.kt", "Graph.start was not called", Why.RESIDUAL,
            "a lookup before Graph.start: the build cannot see the order of calls"),
        Site("Kite.kt", "Generated registry", Why.COMPILE_TIME,
            "Kite.init is @KiteGeneratedApi: only the generated Graph.start calls it, and it is generated " +
                "together with MergedRegistry"),
        Site("ScopeTree.kt", "Cannot open scope", Why.COMPILE_TIME,
            "every open names an existing parent: custom scopes the app, Fragments the Activity found in the tree " +
                "(FragmentScopes falls back to the app scope)"),
        Site("ScopeTree.kt", "The app scope cannot be closed", Why.COMPILE_TIME,
            "nothing closes the app scope: Activity, Fragment and custom-scope ids are never ScopeId.App"),
        Site("KiteTesting.kt", "after Graph.start", Why.TEST_ONLY, "replace<T>"),
        Site("KiteTesting.kt", "require(", Why.TEST_ONLY, "replace<T> with an instance of the wrong type"),
    )

    /** Each throw in the runtime's sources: its file and the text that follows it. */
    private fun throwSites(): List<Pair<String, String>> {
        val root = File("src/main/kotlin")
        check(root.isDirectory) { "run from the runtime module: ${File(".").absolutePath}" }
        val pattern = Regex("""\bthrow\b|\brequire\(|\bcheck\(|\berror\(""")
        return root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            val lines = file.readLines()
            lines.indices.filter { i ->
                val code = lines[i].substringBefore("//").trim()
                !code.startsWith("*") && !code.startsWith("/*") && pattern.containsMatchIn(code)
            }.map { i -> file.name to (lines[i] + "\n" + lines.getOrElse(i + 1) { "" }) }
        }.toList()
    }

    @Test
    fun `every throw in the runtime is classified`() {
        val unclassified = throwSites().filter { (file, text) ->
            sites.none { it.file == file && it.marker in text }
        }
        assertEquals(emptyList(), unclassified.map { (file, text) -> "$file: ${text.trim()}" })
    }

    @Test
    fun `every classification still has its throw`() {
        val found = throwSites()
        val stale = sites.filter { site -> found.none { (file, text) -> file == site.file && site.marker in text } }
        assertEquals(emptyList(), stale.map { "${it.file}: ${it.marker}" })
    }

    @Test
    fun `what an application can still hit is short`() {
        val residual = sites.filter { it.why == Why.RESIDUAL }.map { it.marker }
        // Changing this list is a decision: say why in ADR 15 and in the errors guide.
        assertEquals(listOf("No open custom scope", "with different arguments", "Graph.start was not called"), residual)
        assertTrue(sites.filter { it.why == Why.COMPILE_TIME }.all { it.reason.isNotBlank() })
    }
}
