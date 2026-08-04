package com.kite.di.processor.scan

import com.kite.di.graph.ScopeDef
import com.kite.di.processor.model.Severity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One class, one lifetime decision (ADR 11) — the pure merge behind
 * `RulesScanner`: conflicting decisions error naming both spellings and
 * locations; a `"singleton"` rule restates the default and warns.
 */
class ScopeRulesTest {

    private fun scoped(fqn: String, scope: ScopeDef?, where: String = "app/src/GraphRules.kt:10") =
        ScopeRule(fqn, scope, where, "@Scoped(${fqn.substringAfterLast('.')}::class, \"${scope?.name ?: "none"}\")")

    private fun fresh(fqn: String, where: String = "app/src/Thing.kt:5") =
        ScopeRule(fqn, scope = null, where = where, display = "@Fresh")

    @Test
    fun `distinct classes pass through untouched`() {
        val rules = listOf(scoped("a.A", ScopeDef("ActivityScoped", 1)), fresh("a.B"))
        val merged = ScopeRules.merge(rules)
        assertEquals(rules, merged.scopes)
        assertEquals(emptyList(), merged.issues)
    }

    @Test
    fun `Fresh plus Scoped for one class is a conflict naming both locations`() {
        val merged = ScopeRules.merge(
            listOf(fresh("a.A", where = "app/src/A.kt:3"), scoped("a.A", ScopeDef("ActivityScoped", 1))),
        )
        val error = merged.issues.single()
        assertEquals(Severity.ERROR, error.severity)
        assertTrue("@Fresh" in error.message && "@Scoped" in error.message, error.message)
        assertTrue("app/src/A.kt:3" in error.message && "app/src/GraphRules.kt:10" in error.message, error.message)
        assertEquals(1, merged.scopes.size) // the first decision survives so downstream checks keep working
    }

    @Test
    fun `the same decision twice is still a conflict - one class, one decision`() {
        val merged = ScopeRules.merge(listOf(fresh("a.A"), fresh("a.A")))
        assertEquals(Severity.ERROR, merged.issues.single().severity)
    }

    @Test
    fun `a singleton rule is redundant - singleton is the default`() {
        val merged = ScopeRules.merge(listOf(scoped("a.A", ScopeDef("Singleton", 0))))
        val warning = merged.issues.single()
        assertEquals(Severity.WARNING, warning.severity)
        assertTrue("singleton is the default" in warning.message, warning.message)
        assertEquals(1, merged.scopes.size) // kept: harmless, and the class stays decided
    }
}
