package com.kite.di.processor.validate

import com.kite.di.graph.Key
import com.kite.di.graph.Provenance
import com.kite.di.graph.ScopeDef
import com.kite.di.graph.SiteKind
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.DependencyModel
import com.kite.di.processor.model.ScanResult
import com.kite.di.processor.model.Severity
import com.kite.di.processor.model.TypeRef
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun typeRef(fqn: String) = TypeRef(fqn.substringBeforeLast('.'), listOf(fqn.substringAfterLast('.')))

private fun binding(
    fqn: String,
    scopeLevel: Int? = null,
    scopeName: String? = null,
    extraKeys: List<Key> = emptyList(),
    deps: List<DependencyModel> = emptyList(),
) = BindingModel(
    key = Key(fqn),
    keyType = typeRef(fqn),
    extraKeys = extraKeys,
    scopeLevel = scopeLevel,
    scopeName = scopeName,
    declaration = fqn.substringAfterLast('.'),
    provenance = Provenance(":app", "app/src/${fqn.substringAfterLast('.')}.kt", 10),
    dependencies = deps,
    targetType = typeRef(fqn),
)

private fun dep(fqn: String) = DependencyModel(
    key = Key(fqn),
    type = typeRef(fqn),
    siteKind = SiteKind.CONSTRUCTOR_PARAM,
    paramName = fqn.substringAfterLast('.').replaceFirstChar { it.lowercaseChar() },
    site = Provenance(":app", "app/src/Site.kt", 20),
)

/**
 * Cross-module resolution (multi-module apps): keys provided by other source
 * modules' registries arrive as [ScanResult.classpathKeys] and must satisfy V1
 * and participate in V4 with their scopes.
 */
class CrossModuleValidatorTest {

    private fun errors(scan: ScanResult, aggregate: Boolean = true) =
        GraphValidator.validate(scan, aggregate).filter { it.severity == Severity.ERROR }

    private fun warnings(scan: ScanResult, aggregate: Boolean = true) =
        GraphValidator.validate(scan, aggregate).filter { it.severity == Severity.WARNING }

    @Test
    fun `a key provided by another module satisfies V1`() {
        val scan = ScanResult(
            bindings = listOf(binding("app.Presenter", deps = listOf(dep("core.UserRepository")))),
            classpathKeys = mapOf(Key("core.UserRepository") to ScopeDef("Singleton", 0)),
        )
        assertEquals(emptyList(), errors(scan))
    }

    @Test
    fun `a dependency no module provides is still a missing binding`() {
        val scan = ScanResult(
            bindings = listOf(binding("app.Presenter", deps = listOf(dep("core.UserRepository")))),
            classpathKeys = mapOf(Key("core.SomethingElse") to null),
        )
        val e = errors(scan).single()
        assertTrue("Missing binding: no provider for core.UserRepository" in e.message, e.message)
    }

    @Test
    fun `V4 sees the scope of a cross-module dependency`() {
        // Singleton (level 0) depending on another module's activity-scoped binding (level 1).
        val scan = ScanResult(
            bindings = listOf(
                binding(
                    "app.SyncService", scopeLevel = 0, scopeName = "Singleton",
                    deps = listOf(dep("core.SessionState")),
                ),
            ),
            classpathKeys = mapOf(Key("core.SessionState") to ScopeDef("ActivityScoped", 1)),
        )
        val e = errors(scan).single()
        assertTrue("Scope violation: Singleton SyncService depends on ActivityScoped core.SessionState" in e.message, e.message)
        assertTrue("another module's binding" in e.message, e.message)
    }

    @Test
    fun `unscoped cross-module dependency is fine for a scoped consumer`() {
        val scan = ScanResult(
            bindings = listOf(
                binding("app.Service", scopeLevel = 0, scopeName = "Singleton", deps = listOf(dep("core.Helper"))),
            ),
            classpathKeys = mapOf(Key("core.Helper") to null),
        )
        assertEquals(emptyList(), errors(scan))
    }

    @Test
    fun `library modules do not warn about unused interface implementations`() {
        // In :core nothing consumes NetworkUserRepository — its consumers live downstream.
        val impl = binding("core.NoDepsRepo", extraKeys = listOf(Key("core.UserRepository")))
        assertEquals(emptyList(), warnings(ScanResult(bindings = listOf(impl)), aggregate = false))
        // In the aggregate module the same shape still warns.
        assertEquals(1, warnings(ScanResult(bindings = listOf(impl)), aggregate = true).size)
    }
}
