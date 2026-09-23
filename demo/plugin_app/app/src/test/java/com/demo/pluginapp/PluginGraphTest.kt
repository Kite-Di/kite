package com.demo.pluginapp

import com.demo.core.plugin.Router
import com.demo.core.plugin.SettingsCatalog
import com.demo.core.plugin.Startup
import com.kite.di.generated.MergedRegistry
import com.kite.di.runtime.BindingRecord
import com.kite.di.runtime.Container
import com.kite.di.runtime.InstanceFactory
import com.kite.di.runtime.Key
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every assertion here is about contributions that live in another module. */
class PluginGraphTest {

    private fun container() = Container(
        MergedRegistry.load(),
        builtIns = listOf(
            BindingRecord(
                key = Key(String::class.java, qualifier = "baseUrl"),
                factory = InstanceFactory("https://api.example.com"),
                scopeLevel = 0, scopeName = "Singleton",
                declaration = "Graph.start(baseUrl)",
            ),
        ),
    )

    private inline fun <reified T : Any> resolve(): T {
        val c = container()
        return c.resolve(Key(T::class.java), c.scopeTree.root)
    }

    @Test fun `startup collects every module's tasks in priority order`() {
        assertEquals(
            listOf("warm-up-pool", "track-launch", "prefetch-profile", "connect-socket", "sync-orders"),
            resolve<Startup>().runAll(),
        )
    }

    @Test fun `router dispatches to the feature that claims the host`() {
        val router = resolve<Router>()
        assertEquals("profile:https://api.example.com/me", router.open("app://profile/x"))
        assertEquals(null, router.open("app://nobody/x"))
    }

    @Test fun `settings catalog is ordered across features`() {
        assertEquals(listOf("Profile", "Orders"), resolve<SettingsCatalog>().titles())
    }
}
