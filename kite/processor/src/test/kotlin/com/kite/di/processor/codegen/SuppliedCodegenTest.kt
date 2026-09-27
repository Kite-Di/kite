package com.kite.di.processor.codegen

import com.kite.di.graph.Key
import com.kite.di.graph.Provenance
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.InferredBy
import com.kite.di.processor.model.TypeRef
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val WHERE = Provenance(":core:network", "core/network/src/Network.kt", 32)

private val supplier = BindingModel(
    key = Key("com.example.net.Http"),
    keyType = TypeRef("com.example.net", listOf("Http")),
    suppliedKeys = listOf(Key("okhttp3.OkHttpClient")),
    suppliedKeyTypes = listOf(TypeRef("okhttp3", listOf("OkHttpClient"))),
    scopeLevel = 0,
    scopeName = "Singleton",
    declaration = "Http",
    inferredBy = InferredBy.IMPLEMENTATION,
    provenance = WHERE,
    targetType = TypeRef("com.example.net", listOf("Http")),
)

/** ADR 13 in generated code: a second record, not a second key on the first one. */
class SuppliedCodegenTest {

    private val registry = RegistryGenerator.registryFile(":core:network", listOf(supplier), emptyList(), emptyList()).toString()

    @Test
    fun `the supplied type gets a record of its own`() {
        assertTrue("key = Key(OkHttpClient::class.java)" in registry, registry)
        assertTrue("factory = SuppliedFactory<OkHttpClient>(Key(Http::class.java))" in registry, registry)
    }

    @Test
    fun `the supplier keeps its own record and its own factory`() {
        assertTrue("key = Key(Http::class.java)" in registry, registry)
        assertTrue("factory = Http_Factory()" in registry, registry)
    }

    @Test
    fun `it is not an extra key - the answering object is a different one`() {
        assertFalse("extraKeys = listOf(Key(OkHttpClient::class.java))" in registry, registry)
    }

    @Test
    fun `the supplied record carries the supplier's scope`() {
        // A supplied type lives exactly as long as the class that built it; without
        // this a @Fresh supplier would sit behind a cached singleton key.
        val supplied = registry.substringAfter("factory = SuppliedFactory").substringBefore("),\n  )")
        assertTrue("scopeLevel = 0" in supplied, supplied)
        assertTrue("""scopeName = "Singleton"""" in supplied, supplied)
        assertTrue("""declaration = "OkHttpClient (supplied by Http)"""" in supplied, supplied)
    }

    @Test
    fun `the supplied type is exported to other modules`() {
        // Otherwise a downstream constructor parameter of that type would bubble up
        // to Graph.start instead of resolving as a cross-module edge.
        assertTrue("OkHttpClient::class" in registry.substringAfter("@ProvidedKeys").substringBefore("class CoreNetwork"), registry)
    }

    @Test
    fun `a release build keeps the record and drops the source names`() {
        val stripped = RegistryGenerator
            .registryFile(":core:network", listOf(supplier), emptyList(), emptyList(), includeProvenance = false)
            .toString()

        assertTrue("SuppliedFactory<OkHttpClient>(Key(Http::class.java))" in stripped, stripped)
        assertFalse("supplied by" in stripped, stripped)
        assertFalse("Network.kt" in stripped, stripped)
    }
}
