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

private fun supplier(
    fqn: String,
    supplies: String,
    scopeLevel: Int? = 0,
    scopeName: String? = "Singleton",
    deps: List<DependencyModel> = emptyList(),
) = BindingModel(
    key = Key(fqn),
    keyType = typeRef(fqn),
    suppliedKeys = listOf(Key(supplies)),
    suppliedKeyTypes = listOf(typeRef(supplies)),
    scopeLevel = scopeLevel,
    scopeName = scopeName,
    declaration = fqn.substringAfterLast('.'),
    provenance = Provenance(":core", "core/src/${fqn.substringAfterLast('.')}.kt", 7),
    dependencies = deps,
    targetType = typeRef(fqn),
)

private fun consumer(
    fqn: String,
    deps: List<DependencyModel>,
    scopeLevel: Int? = 0,
    scopeName: String? = "Singleton",
) = BindingModel(
    key = Key(fqn),
    keyType = typeRef(fqn),
    scopeLevel = scopeLevel,
    scopeName = scopeName,
    declaration = fqn.substringAfterLast('.'),
    provenance = Provenance(":app", "app/src/${fqn.substringAfterLast('.')}.kt", 3),
    dependencies = deps,
    targetType = typeRef(fqn),
)

private fun dep(fqn: String, param: String) = DependencyModel(
    key = Key(fqn),
    type = typeRef(fqn),
    siteKind = SiteKind.CONSTRUCTOR_PARAM,
    paramName = param,
    site = Provenance(":app", "app/src/Site.kt", 4),
)

private val SCOPES = listOf(ScopeDef("Singleton", 0), ScopeDef("ActivityScoped", 1))

/**
 * A supplied key is answered by its supplier, so every check that reads "which
 * binding provides this key" has to reach the supplier — otherwise the type the
 * project does not own would be exempt from the guarantees the rest of the graph has.
 */
class SuppliedBindingValidatorTest {

    @Test
    fun `a supplied type provides the key its consumer asks for`() {
        val scan = ScanResult(
            bindings = listOf(
                supplier("core.Http", supplies = "okhttp3.OkHttpClient"),
                consumer("app.Api", deps = listOf(dep("okhttp3.OkHttpClient", "client"))),
            ),
            scopes = SCOPES,
        )

        assertEquals(emptyList(), GraphValidator.validate(scan).filter { it.severity == Severity.ERROR })
    }

    @Test
    fun `two suppliers of one type are a duplicate binding`() {
        val scan = ScanResult(
            bindings = listOf(
                supplier("core.Http", supplies = "okhttp3.OkHttpClient"),
                supplier("core.OtherHttp", supplies = "okhttp3.OkHttpClient"),
            ),
            scopes = SCOPES,
        )

        val error = GraphValidator.validate(scan).single { it.severity == Severity.ERROR }
        assertTrue("Duplicate binding for okhttp3.OkHttpClient" in error.message, error.message)
        assertTrue("Http" in error.message && "OtherHttp" in error.message, error.message)
    }

    @Test
    fun `a supplier that needs what it supplies is a cycle`() {
        val scan = ScanResult(
            bindings = listOf(
                supplier("core.Http", supplies = "okhttp3.OkHttpClient", deps = listOf(dep("okhttp3.OkHttpClient", "self"))),
            ),
            scopes = SCOPES,
        )

        val error = GraphValidator.validate(scan).single { it.severity == Severity.ERROR }
        assertTrue("Dependency cycle detected" in error.message, error.message)
    }

    @Test
    fun `a supplied type is as short-lived as its supplier`() {
        // The supplier is activity-scoped, so what it supplies cannot be held by a
        // singleton — the whole point of the scope carrying over to the supplied key.
        val scan = ScanResult(
            bindings = listOf(
                supplier("core.Http", supplies = "okhttp3.OkHttpClient", scopeLevel = 1, scopeName = "ActivityScoped"),
                consumer("app.Api", deps = listOf(dep("okhttp3.OkHttpClient", "client"))),
            ),
            scopes = SCOPES,
        )

        val error = GraphValidator.validate(scan).single { it.severity == Severity.ERROR }
        assertTrue("Scope violation" in error.message, error.message)
        assertTrue("ActivityScoped" in error.message, error.message)
    }

    @Test
    fun `a supplier whose type is consumed is not unused`() {
        val scan = ScanResult(
            bindings = listOf(
                supplier("core.Http", supplies = "okhttp3.OkHttpClient"),
                consumer("app.Api", deps = listOf(dep("okhttp3.OkHttpClient", "client"))),
            ),
            scopes = SCOPES,
        )

        assertTrue(GraphValidator.validate(scan).none { "Unused binding" in it.message })
    }

    @Test
    fun `a supplier nobody consumes is reported once, by its own name`() {
        val scan = ScanResult(bindings = listOf(supplier("core.Http", supplies = "okhttp3.OkHttpClient")), scopes = SCOPES)

        val warning = GraphValidator.validate(scan).single { it.severity == Severity.WARNING }
        assertTrue("Unused binding: Http" in warning.message, warning.message)
    }

    @Test
    fun `a library module does not warn about a supplier its consumers cannot be seen from`() {
        val scan = ScanResult(bindings = listOf(supplier("core.Http", supplies = "okhttp3.OkHttpClient")), scopes = SCOPES)

        assertEquals(emptyList(), GraphValidator.validate(scan, aggregate = false))
    }
}
