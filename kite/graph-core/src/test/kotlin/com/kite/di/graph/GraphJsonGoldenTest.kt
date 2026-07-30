package com.kite.di.graph

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pins the wire format. The same golden file is parsed by the webboard test suite
 * (`webboard/src/model/graph.test.ts`) — a drift on either side fails that side's
 * build. Regenerate deliberately with `printCanonical()` if the schema changes
 * (and bump [GraphSnapshot.SCHEMA_VERSION] if the change is breaking).
 */
class GraphJsonGoldenTest {

    @Test
    fun `canonical snapshot matches golden file`() {
        if (System.getProperty("golden.update") == "1") {
            // `./gradlew :kite:graph-core:test -PgoldenUpdate` — deliberate regeneration.
            val out = java.io.File("src/test/resources/golden/snapshot-v2.json")
            out.parentFile.mkdirs()
            out.writeText(GraphJson.encodePretty(canonicalSnapshot()) + "\n")
            return
        }
        val golden = javaClass.getResourceAsStream("/golden/snapshot-v2.json")!!
            .bufferedReader().use { it.readText() }.trimEnd()
        assertEquals(golden, GraphJson.encodePretty(canonicalSnapshot()))
    }

    @Test
    fun `golden file decodes back to the canonical snapshot`() {
        if (System.getProperty("golden.update") == "1") return
        val golden = javaClass.getResourceAsStream("/golden/snapshot-v2.json")!!
            .bufferedReader().use { it.readText() }
        assertEquals(canonicalSnapshot(), GraphJson.decode(golden))
    }

    @Test
    fun `key id round trip`() {
        assertEquals(Key("com.example.Repo"), Key.parse("com.example.Repo"))
        assertEquals(Key("okhttp3.OkHttpClient", "auth"), Key.parse("auth@okhttp3.OkHttpClient"))
        assertEquals("auth@okhttp3.OkHttpClient", Key("okhttp3.OkHttpClient", "auth").id)
    }

    companion object {
        fun canonicalSnapshot(): GraphSnapshot = GraphSnapshot(
            appId = "com.kite.demo",
            variant = "debug",
            scopes = listOf(
                ScopeDef("Singleton", 0),
                ScopeDef("ActivityScoped", 1),
                ScopeDef("FragmentScoped", 2),
            ),
            nodes = listOf(
                GraphNode(
                    id = "com.example.data.UserRepo",
                    type = "com.example.data.UserRepo",
                    displayName = "UserRepo",
                    kind = NodeKind.INJECTABLE,
                    scope = "Singleton",
                    boundTo = listOf("com.example.data.Repo"),
                    providedBy = ProvidedBy(
                        declaration = "RealUserRepo",
                        gradleModule = ":app",
                        file = "app/src/main/java/com/example/data/RealUserRepo.kt",
                        line = 8,
                    ),
                    inferredBy = "implementation",
                ),
                GraphNode(
                    id = "auth@okhttp3.OkHttpClient",
                    type = "okhttp3.OkHttpClient",
                    qualifier = "auth",
                    displayName = "OkHttpClient",
                    kind = NodeKind.PROVIDES,
                    providedBy = ProvidedBy(
                        declaration = "NetworkModule.provideOkHttp",
                        gradleModule = ":app",
                        file = "app/src/main/java/com/example/net/NetworkModule.kt",
                        line = 31,
                    ),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "com.example.ui.MainPresenter -> com.example.data.UserRepo # 0",
                    from = "com.example.ui.MainPresenter",
                    to = "com.example.data.UserRepo",
                    siteKind = SiteKind.CONSTRUCTOR_PARAM,
                    paramName = "repo",
                    site = SiteRef("app/src/main/java/com/example/ui/MainPresenter.kt", 19),
                ),
            ),
            runtime = RuntimeState(
                openScopes = listOf(
                    RuntimeScope(id = "app", name = "Singleton"),
                    RuntimeScope(id = "MainActivity@1f3a", name = "ActivityScoped", parent = "app"),
                ),
                instances = listOf(
                    RuntimeInstance(
                        nodeId = "com.example.data.UserRepo",
                        scopeId = "app",
                        createdAt = 1730000000000,
                        creationMicros = 412,
                    ),
                ),
            ),
        )
    }
}
