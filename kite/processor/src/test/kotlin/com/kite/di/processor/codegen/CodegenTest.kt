package com.kite.di.processor.codegen

import com.kite.di.graph.DeferredKind
import com.kite.di.graph.Key
import com.kite.di.graph.Provenance
import com.kite.di.graph.SiteKind
import com.kite.di.processor.model.BindingDeclKind
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.DependencyModel
import com.kite.di.processor.model.FieldInjectionModel
import com.kite.di.processor.model.MemberInjectModel
import com.kite.di.processor.model.TypeRef
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
        declKind = BindingDeclKind.INJECTABLE,
        scopeLevel = 0,
        scopeName = "Singleton",
        declaration = "RealUserRepo",
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
        ),
        targetType = TypeRef("com.example.data", listOf("RealUserRepo")),
    )

    private val providesBinding = BindingModel(
        key = Key("okhttp3.OkHttpClient", qualifier = "auth"),
        keyType = TypeRef("okhttp3", listOf("OkHttpClient")),
        declKind = BindingDeclKind.PROVIDES,
        scopeLevel = 0,
        scopeName = "Singleton",
        declaration = "NetworkModule.provideOkHttp",
        provenance = WHERE,
        dependencies = emptyList(),
        targetType = TypeRef("com.example.net", listOf("NetworkModule")),
        providesFunction = "provideOkHttp",
        moduleIsObject = true,
    )

    private val available = setOf(
        Key("com.example.net.Api"),
        Key("com.example.data.Db"),
        Key("com.example.data.RealUserRepo"),
        Key("okhttp3.OkHttpClient", "auth"),
    )

    @Test
    fun `injectable factory resolves constructor params in order with named arguments`() {
        val code = FactoryGenerator.factoryFile(repoBinding, available).toString()
        assertTrue("class RealUserRepo_Factory : Factory<RealUserRepo>" in code, code)
        assertTrue("""api = resolver.resolve<Api>(Key(Api::class.java), scope)""" in code, code)
        assertTrue("""db = resolver.deferred<Db>(Key(Db::class.java), scope)""" in code, code)
        assertTrue("= RealUserRepo(" in code, code)
        assertFalse("\"com.example" in code, "keys must be class references, not FQN strings:\n$code")
    }

    @Test
    fun `provides factory calls the module object function`() {
        val code = FactoryGenerator.factoryFile(providesBinding, available).toString()
        assertTrue("class NetworkModule_provideOkHttp_Factory : Factory<OkHttpClient>" in code, code)
        assertTrue("= NetworkModule.provideOkHttp(" in code, code)
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
    fun `member injector assigns fields`() {
        val model = MemberInjectModel(
            targetType = TypeRef("com.example.ui", listOf("MainActivity")),
            fields = listOf(
                FieldInjectionModel(
                    "presenter", Key("com.example.ui.MainPresenter"),
                    TypeRef("com.example.ui", listOf("MainPresenter")), site = WHERE,
                ),
            ),
            provenance = WHERE,
        )
        val code = FactoryGenerator.memberInjectorFile(model).toString()
        assertTrue("class MainActivity_MemberInjector : MemberInjector<MainActivity>" in code, code)
        assertTrue("""target.presenter = resolver.resolve<MainPresenter>(Key(MainPresenter::class.java), scope)""" in code, code)
    }

    @Test
    fun `registry lists records with provenance and member injectors`() {
        val member = MemberInjectModel(
            targetType = TypeRef("com.example.ui", listOf("MainActivity")),
            fields = listOf(
                FieldInjectionModel(
                    "presenter", Key("com.example.ui.MainPresenter"),
                    TypeRef("com.example.ui", listOf("MainPresenter")), site = WHERE,
                ),
            ),
            provenance = WHERE,
        )
        val code = RegistryGenerator.registryFile(":app", listOf(repoBinding, providesBinding), listOf(member)).toString()
        assertTrue("class App_BindingRegistry : BindingRegistry" in code, code)
        assertTrue("""Key(RealUserRepo::class.java)""" in code, code)
        assertTrue("""extraKeys = listOf(Key(UserRepo::class.java))""" in code, code)
        assertTrue("scopeLevel = 0" in code)
        assertTrue("""Provenance(":app", "app/src/X.kt", 5)""" in code, code)
        assertTrue("""MainActivity::class.java to MainActivity_MemberInjector()""" in code, code)
        assertTrue("""Key(OkHttpClient::class.java, qualifier = "auth")""" in code, "qualified key must keep its qualifier")
    }

    @Test
    fun `provenance-stripped registry ships no source-name strings at all`() {
        val member = MemberInjectModel(
            targetType = TypeRef("com.example.ui", listOf("MainActivity")),
            fields = listOf(
                FieldInjectionModel(
                    "presenter", Key("com.example.ui.MainPresenter"),
                    TypeRef("com.example.ui", listOf("MainPresenter")), site = WHERE,
                ),
            ),
            provenance = WHERE,
        )
        val code = RegistryGenerator.registryFile(
            ":app", listOf(repoBinding, providesBinding), listOf(member), includeProvenance = false,
        ).toString()
        // Class names appear only as symbolic references (imports / ::class.java),
        // never as string literals R8 cannot rewrite.
        assertFalse("\"com.example" in code, code)
        assertFalse("declaration =" in code, code)
        assertFalse("provenance =" in code, code)
        assertFalse("app/src/X.kt" in code, code)
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
}
