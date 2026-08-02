package com.kite.di.processor.codegen

import com.kite.di.graph.DeferredKind
import com.kite.di.graph.Key
import com.kite.di.graph.Provenance
import com.kite.di.graph.SiteKind
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.DependencyModel
import com.kite.di.processor.model.GraphArg
import com.kite.di.processor.model.InferredBy
import com.kite.di.processor.model.SetBindingModel
import com.kite.di.processor.model.TypeRef
import com.kite.di.processor.model.ViewModelModel
import com.kite.di.processor.model.ViewModelParam
import com.kite.di.processor.model.setKeyOf
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val WHERE = Provenance(":app", "app/src/X.kt", 5)

class CodegenTest {

    private val repoBinding = BindingModel(
        key = Key("com.example.data.RealUserRepo"),
        keyType = TypeRef("com.example.data", listOf("RealUserRepo")),
        extraKeys = listOf(Key("com.example.data.UserRepo")),
        extraKeyTypes = listOf(TypeRef("com.example.data", listOf("UserRepo"))),
        scopeLevel = 0,
        scopeName = "Singleton",
        declaration = "RealUserRepo",
        inferredBy = InferredBy.IMPLEMENTATION,
        provenance = WHERE,
        dependencies = listOf(
            DependencyModel(
                key = Key("com.example.net.Api"),
                type = TypeRef("com.example.net", listOf("Api")),
                siteKind = SiteKind.CONSTRUCTOR_PARAM,
                paramName = "api",
                site = WHERE,
            ),
            DependencyModel(
                key = Key("com.example.data.Db"),
                type = TypeRef("com.example.data", listOf("Db")),
                siteKind = SiteKind.CONSTRUCTOR_PARAM,
                deferred = DeferredKind.LAZY,
                paramName = "db",
                site = WHERE,
            ),
            // R5: a leaf parameter — resolved as a graph argument by param name.
            DependencyModel(
                key = Key("kotlin.String", qualifier = "apiKey"),
                type = TypeRef("kotlin", listOf("String")),
                siteKind = SiteKind.CONSTRUCTOR_PARAM,
                paramName = "apiKey",
                isGraphArg = true,
                site = WHERE,
            ),
        ),
        targetType = TypeRef("com.example.data", listOf("RealUserRepo")),
    )

    private val available = setOf(
        Key("com.example.net.Api"),
        Key("com.example.data.Db"),
        Key("com.example.data.RealUserRepo"),
        Key("kotlin.String", qualifier = "apiKey"),
    )

    @Test
    fun `factory resolves constructor params in order with named arguments`() {
        val code = FactoryGenerator.factoryFile(repoBinding, available).toString()
        assertTrue("class RealUserRepo_Factory : Factory<RealUserRepo>" in code, code)
        assertTrue("""api = resolver.resolve<Api>(Key(Api::class.java), scope)""" in code, code)
        assertTrue("""db = resolver.deferred<Db>(Key(Db::class.java), scope)""" in code, code)
        assertTrue("= RealUserRepo(" in code, code)
        assertFalse("\"com.example" in code, "keys must be class references, not FQN strings:\n$code")
    }

    @Test
    fun `graph argument resolves by parameter-name qualifier`() {
        val code = FactoryGenerator.factoryFile(repoBinding, available).toString()
        assertTrue("""apiKey = resolver.resolve<String>(Key(String::class.java, qualifier = "apiKey"), scope)""" in code, code)
    }

    @Test
    fun `optional param with missing binding is omitted`() {
        val withOptional = repoBinding.copy(
            dependencies = repoBinding.dependencies + DependencyModel(
                key = Key("com.example.Missing"),
                type = TypeRef("com.example", listOf("Missing")),
                siteKind = SiteKind.CONSTRUCTOR_PARAM,
                paramName = "missing",
                optional = true,
                site = WHERE,
            )
        )
        val code = FactoryGenerator.factoryFile(withOptional, available).toString()
        assertFalse("missing =" in code, "optional param without a binding must be omitted:\n$code")
        assertTrue("api =" in code)
    }

    @Test
    fun `set record aggregates implementations by their own keys`() {
        val set = SetBindingModel(
            key = setKeyOf("com.example.boot.StartupTask"),
            elementType = TypeRef("com.example.boot", listOf("StartupTask")),
            elementKeys = listOf(Key("com.example.boot.WarmUpCaches"), Key("com.example.boot.TrackLaunch")),
            elementTypes = listOf(
                TypeRef("com.example.boot", listOf("WarmUpCaches")),
                TypeRef("com.example.boot", listOf("TrackLaunch")),
            ),
            provenance = WHERE,
        )
        val registry = RegistryGenerator.registryFile(":app", emptyList(), listOf(set), emptyList()).toString()
        assertTrue("Key(Set::class.java, element = StartupTask::class.java)" in registry, registry)
        assertTrue("SetFactory(listOf(Key(WarmUpCaches::class.java), Key(TrackLaunch::class.java)))" in registry, registry)
    }

    @Test
    fun `registry lists records with provenance and carries graph args as an annotation`() {
        val args = listOf(GraphArg("apiKey", TypeRef("kotlin", listOf("String")), listOf(WHERE)))
        val code = RegistryGenerator.registryFile(":app", listOf(repoBinding), emptyList(), args).toString()
        assertTrue("class App_BindingRegistry : BindingRegistry" in code, code)
        assertTrue("""Key(RealUserRepo::class.java)""" in code, code)
        assertTrue("""extraKeys = listOf(Key(UserRepo::class.java))""" in code, code)
        assertTrue("scopeLevel = 0" in code)
        assertTrue("""Provenance(":app", "app/src/X.kt", 5)""" in code, code)
        assertTrue("GraphArgs" in code, code)
        assertTrue("""names = ["apiKey"]""" in code, code)
        assertTrue("types = [String::class]" in code, code)
    }

    @Test
    fun `provenance-stripped registry ships no source-name strings at all`() {
        val code = RegistryGenerator.registryFile(
            ":app", listOf(repoBinding), emptyList(), emptyList(), includeProvenance = false,
        ).toString()
        // Class names appear only as symbolic references (imports / ::class.java),
        // never as string literals R8 cannot rewrite.
        assertFalse("\"com.example" in code, code)
        assertFalse("declaration =" in code, code)
        assertFalse("provenance =" in code, code)
        assertFalse("app/src/X.kt" in code, code)
    }

    @Test
    fun `view model adapter fills graph deps, runtime args and saved state`() {
        val vm = ViewModelModel(
            targetType = TypeRef("com.example.ui", listOf("CheckoutViewModel")),
            params = listOf(
                ViewModelParam.Injected(
                    "api",
                    DependencyModel(
                        key = Key("com.example.net.Api"),
                        type = TypeRef("com.example.net", listOf("Api")),
                        siteKind = SiteKind.CONSTRUCTOR_PARAM,
                        paramName = "api",
                        site = WHERE,
                    ),
                ),
                ViewModelParam.Runtime("orderId", TypeRef("kotlin", listOf("String"))),
                ViewModelParam.SavedState("saved"),
            ),
            provenance = WHERE,
        )
        val code = AdapterGenerator.adapterFile(vm).toString()
        assertTrue("fun ComponentActivity.checkoutViewModel(orderId: String): Lazy<CheckoutViewModel>" in code, code)
        assertTrue("fun Fragment.checkoutViewModel(orderId: String): Lazy<CheckoutViewModel>" in code, code)
        assertTrue("resolveViewModel(this, this, CheckoutViewModel::class.java)" in code, code)
        assertTrue("""api = resolver.resolve<Api>(Key(Api::class.java), scope)""" in code, code)
        assertTrue("orderId = orderId" in code, code)
        assertTrue("saved = extras.createSavedStateHandle()" in code, code)
        assertFalse("Composable" in code, "no compose adapter unless the module enables compose:\n$code")

        val composeCode = AdapterGenerator.adapterFile(vm, compose = true).toString()
        assertTrue("@Composable" in composeCode, composeCode)
        assertTrue("fun rememberCheckoutViewModel(orderId: String): CheckoutViewModel" in composeCode, composeCode)
        assertTrue("injectedViewModel(CheckoutViewModel::class.java)" in composeCode, composeCode)
    }

    @Test
    fun `graph facade takes every argument sorted by name and registers instance bindings`() {
        val args = listOf(
            GraphArg("timeoutMillis", TypeRef("kotlin", listOf("Long")), listOf(WHERE)),
            GraphArg("apiKey", TypeRef("kotlin", listOf("String")), listOf(WHERE)),
        )
        val code = GraphGenerator.graphFile(args, includeProvenance = true).toString()
        assertTrue("object Graph" in code, code)
        assertTrue("apiKey: String" in code, code)
        assertTrue("timeoutMillis: Long" in code, code)
        assertTrue("""Key(String::class.java, qualifier = "apiKey")""" in code, code)
        assertTrue("InstanceFactory(apiKey)" in code, code)
        assertTrue("InstanceFactory(timeoutMillis)" in code, code)
        assertTrue(code.indexOf("apiKey:") < code.indexOf("timeoutMillis:"), "parameters must sort by name:\n$code")
    }

    @Test
    fun `merged registry lists own and classpath registries`() {
        val code = RegistryGenerator.mergedRegistryFile(
            "App_BindingRegistry",
            listOf("com.kite.di.generated.CoreLib_BindingRegistry"),
        ).toString()
        assertTrue("object MergedRegistry" in code, code)
        assertTrue("App_BindingRegistry()" in code)
        assertTrue("CoreLib_BindingRegistry()" in code)
    }

    @Test
    fun `module names sanitize to registry class names`() {
        assertEquals("App_BindingRegistry", RegistryGenerator.registryName(":app"))
        assertEquals("FeatureLoginUi_BindingRegistry", RegistryGenerator.registryName(":feature:login-ui"))
    }

    @Test
    fun `registry exports its keys and scopes for downstream modules`() {
        val code = RegistryGenerator.registryFile(":core", listOf(repoBinding), emptyList(), emptyList()).toString()
        assertTrue("ProvidedKeys" in code, code)
        assertTrue("""module = ":core"""" in code, code)
        // Own key plus the interface it is bound to, both as class references.
        assertTrue("types = [RealUserRepo::class, UserRepo::class]" in code, code)
        assertTrue("""scopeNames = ["Singleton", "Singleton"]""" in code, code)
        assertTrue("scopeLevels = [0, 0]" in code, code)
        assertTrue("ambiguous" !in code, "no ambiguous member when the module has none:\n$code")
    }

    @Test
    fun `registry exports unresolved ambiguities so consumers get a directed error`() {
        val code = RegistryGenerator.registryFile(
            ":core", emptyList(), emptyList(), emptyList(),
            ambiguousInterfaces = listOf(TypeRef("com.example.pay", listOf("PaymentGateway"))),
        ).toString()
        assertTrue("ambiguous = [PaymentGateway::class]" in code, code)
    }
}
