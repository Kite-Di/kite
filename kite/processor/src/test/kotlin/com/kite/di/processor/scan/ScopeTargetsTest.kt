package com.kite.di.processor.scan

import com.kite.di.graph.ScopeDef
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `@Scoped(..., scope, level)` → [ScopeDef] mapping. Pure and
 * KSP-free: pins the built-in scope names and the custom-scope level contract.
 */
class ScopeTargetsTest {

    private fun ok(scope: String, level: Int = RulesNames.UNSET_LEVEL): ScopeDef? {
        val r = ScopeTargets.resolve(scope, level)
        assertTrue(r is ScopeTargets.Resolution.Ok, "expected Ok for \"$scope\", got $r")
        return (r as ScopeTargets.Resolution.Ok).def
    }

    private fun errorOf(scope: String, level: Int = RulesNames.UNSET_LEVEL): String {
        val r = ScopeTargets.resolve(scope, level)
        assertTrue(r is ScopeTargets.Resolution.Error, "expected Error for \"$scope\", got $r")
        return (r as ScopeTargets.Resolution.Error).message
    }

    @Test
    fun `built-in names carry their own fixed level and ignore any level argument`() {
        assertEquals(ScopeDef("Singleton", 0), ok("singleton"))
        assertEquals(ScopeDef("ActivityScoped", 1), ok("activity"))
        assertEquals(ScopeDef("FragmentScoped", 2), ok("fragment", level = 99))
    }

    @Test
    fun `none is an explicit unscoped override`() {
        assertNull(ok("none"))
    }

    @Test
    fun `a custom name with a level declares that scope`() {
        assertEquals(ScopeDef("Session", 10), ok("Session", level = 10))
    }

    @Test
    fun `a custom name without a level is an error that names the fix`() {
        assertTrue("needs a level" in errorOf("Session"))
    }

    @Test
    fun `a blank scope name is an error`() {
        assertTrue("blank" in errorOf("   "))
    }
}
