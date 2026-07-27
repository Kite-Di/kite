package com.kite.di.processor.validate

import com.kite.di.graph.DeferredKind
import com.kite.di.graph.Key
import com.kite.di.graph.Provenance
import com.kite.di.graph.ScopeDef
import com.kite.di.graph.SiteKind
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.DependencyModel
import com.kite.di.processor.model.GraphArg
import com.kite.di.processor.model.ScanResult
import com.kite.di.processor.model.Severity
import com.kite.di.processor.model.TypeRef
import com.kite.di.processor.model.ViewModelModel
import com.kite.di.processor.model.ViewModelParam
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun typeRef(fqn: String) = TypeRef(fqn.substringBeforeLast('.'), listOf(fqn.substringAfterLast('.')))

private fun binding(
    fqn: String,
    scopeLevel: Int? = null,
    scopeName: String? = null,
    qualifier: String? = null,
    extraKeys: List<Key> = emptyList(),
    deps: List<DependencyModel> = emptyList(),
    suppressions: Set<String> = emptySet(),
) = BindingModel(
    key = Key(fqn, qualifier),
    keyType = typeRef(fqn),
    extraKeys = extraKeys,
    scopeLevel = scopeLevel,
    scopeName = scopeName,
    declaration = fqn.substringAfterLast('.'),
    provenance = Provenance(":app", "app/src/${fqn.substringAfterLast('.')}.kt", 10),
    dependencies = deps,
    suppressions = suppressions,
    targetType = typeRef(fqn),
)

private fun dep(fqn: String, qualifier: String? = null, deferred: DeferredKind = DeferredKind.NONE, optional: Boolean = false) =
    DependencyModel(
        key = Key(fqn, qualifier),
        type = typeRef(fqn),
        siteKind = SiteKind.CONSTRUCTOR_PARAM,
        deferred = deferred,
        paramName = fqn.substringAfterLast('.').replaceFirstChar { it.lowercaseChar() },
        optional = optional,
        site = Provenance(":app", "app/src/Site.kt", 20),
    )

class GraphValidatorTest {

    private fun errors(scan: ScanResult) =
        GraphValidator.validate(scan).filter { it.severity == Severity.ERROR }

    private fun warnings(scan: ScanResult) =
        GraphValidator.validate(scan).filter { it.severity == Severity.WARNING }

    // V1 --------------------------------------------------------------------------

    @Test
    fun `missing binding is an error with injection site`() {
        val scan = ScanResult(bindings = listOf(binding("a.Api", deps = listOf(dep("a.Missing")))))
        val e = errors(scan).single()
        assertTrue("Missing binding: no provider for a.Missing" in e.message, e.message)
        assertTrue("app/src/Site.kt:20" in e.message, "must carry the injection site")
    }

    @Test
    fun `optional dependency with default value does not require a binding`() {
        val scan = ScanResult(bindings = listOf(binding("a.Api", deps = listOf(dep("a.Missing", optional = true)))))
        assertEquals(emptyList(), errors(scan))
    }

    @Test
    fun `built-in Application and Context keys are always available`() {
        val scan = ScanResult(
            bindings = listOf(binding("a.Api", deps = listOf(dep("android.content.Context"), dep("android.app.Application"))))
        )
        assertEquals(emptyList(), errors(scan))
    }

    @Test
    fun `graph argument keys satisfy dependencies`() {
        val scan = ScanResult(
            bindings = listOf(binding("a.Api", deps = listOf(dep("kotlin.String", qualifier = "apiKey")))),
            graphArgs = listOf(GraphArg("apiKey", typeRef("kotlin.String"), emptyList())),
        )
        assertEquals(emptyList(), errors(scan))
    }

    @Test
    fun `view model dependency of missing type is an error`() {
        val scan = ScanResult(
            viewModels = listOf(
                ViewModelModel(
                    targetType = typeRef("a.CounterViewModel"),
                    params = listOf(ViewModelParam.Injected("missing", dep("a.Missing"))),
                    provenance = Provenance(":app", "app/src/CounterViewModel.kt", 12),
                )
            )
        )
        val e = errors(scan).single()
        assertTrue("a.Missing" in e.message && "CounterViewModel" in e.message, e.message)
    }

    @Test
    fun `custom scope sharing a built-in level is an error`() {
        val scan = ScanResult(
            bindings = listOf(binding("a.A", scopeLevel = 0, scopeName = "Singleton")),
            scopes = ScanResult.BUILT_IN_SCOPES + ScopeDef("Session", 1),
        )
        val e = errors(scan).single()
        assertTrue("Scope level collision" in e.message, e.message)
        assertTrue("ActivityScoped" in e.message && "Session" in e.message, e.message)
        assertTrue("level 1" in e.message, e.message)
    }

    // V2 --------------------------------------------------------------------------

    @Test
    fun `duplicate binding including interface claims is an error`() {
        val scan = ScanResult(
            bindings = listOf(
                binding("a.RealRepo", extraKeys = listOf(Key("a.Repo"))),
                binding("a.FakeRepo", extraKeys = listOf(Key("a.Repo"))),
            )
        )
        val e = errors(scan).single()
        assertTrue("Duplicate binding for a.Repo" in e.message, e.message)
        assertTrue("RealRepo" in e.message && "FakeRepo" in e.message)
        assertTrue("graph.rules" in e.message, "hint must point at the decisions file: ${e.message}")
    }

    // V3 --------------------------------------------------------------------------

    @Test
    fun `dependency cycle is an error listing the full path`() {
        val scan = ScanResult(
            bindings = listOf(
                binding("a.A", deps = listOf(dep("a.B"))),
                binding("a.B", deps = listOf(dep("a.A"))),
            )
        )
        val e = errors(scan).single()
        assertTrue("Dependency cycle detected" in e.message, e.message)
        assertTrue("Provider<T> or Lazy<T>" in e.message)
    }

    @Test
    fun `cycle through Provider edge is legal`() {
        val scan = ScanResult(
            bindings = listOf(
                binding("a.A", deps = listOf(dep("a.B"))),
                binding("a.B", deps = listOf(dep("a.A", deferred = DeferredKind.PROVIDER))),
            )
        )
        assertEquals(emptyList(), errors(scan))
    }

    @Test
    fun `three node cycle reported once`() {
        val scan = ScanResult(
            bindings = listOf(
                binding("a.A", deps = listOf(dep("a.B"))),
                binding("a.B", deps = listOf(dep("a.C"))),
                binding("a.C", deps = listOf(dep("a.A"))),
            )
        )
        assertEquals(1, errors(scan).size)
    }

    // V4 --------------------------------------------------------------------------

    @Test
    fun `longer-lived depending on shorter-lived is an error`() {
        val scan = ScanResult(
            bindings = listOf(
                binding("a.SyncService", scopeLevel = 0, scopeName = "Singleton", deps = listOf(dep("a.UserRepo"))),
                binding("a.UserRepo", scopeLevel = 1, scopeName = "ActivityScoped"),
            )
        )
        val e = errors(scan).single()
        assertTrue("Scope violation" in e.message, e.message)
        assertTrue("Singleton" in e.message && "ActivityScoped" in e.message)
        assertTrue("graph.rules" in e.message, "hint must point at the decisions file: ${e.message}")
    }

    @Test
    fun `shorter-lived depending on longer-lived is fine`() {
        val scan = ScanResult(
            bindings = listOf(
                binding("a.Presenter", scopeLevel = 1, scopeName = "ActivityScoped", deps = listOf(dep("a.Repo"))),
                binding("a.Repo", scopeLevel = 0, scopeName = "Singleton"),
            )
        )
        assertEquals(emptyList(), errors(scan))
    }

    @Test
    fun `scoped capturing multi-consumer unscoped binding warns`() {
        val scan = ScanResult(
            bindings = listOf(
                binding("a.Service", scopeLevel = 0, scopeName = "Singleton", deps = listOf(dep("a.Helper"))),
                binding("a.Other", deps = listOf(dep("a.Helper"))),
                binding("a.Helper"),
            )
        )
        assertEquals(emptyList(), errors(scan))
        assertTrue(warnings(scan).any { "captures unscoped a.Helper" in it.message })
    }

    // unused ----------------------------------------------------------------------

    @Test
    fun `isolated binding warns unless suppressed`() {
        val lonely = ScanResult(bindings = listOf(binding("a.Lonely")))
        assertTrue(warnings(lonely).any { "Unused binding" in it.message })

        val suppressed = ScanResult(
            bindings = listOf(binding("a.Lonely", suppressions = setOf(GraphValidator.SUPPRESS_UNUSED)))
        )
        assertTrue(warnings(suppressed).none { "Unused binding" in it.message })
    }

    @Test
    fun `binding with dependencies does not warn as unused`() {
        // Presenters resolved via `by injected()` are invisible statically — no noise for them.
        val scan = ScanResult(
            bindings = listOf(
                binding("a.Presenter", deps = listOf(dep("a.Repo"))),
                binding("a.Repo"),
            )
        )
        assertTrue(warnings(scan).none { "Unused binding" in it.message })
    }
}
