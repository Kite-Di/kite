package com.kite.di.board

import com.kite.di.graph.DecisionCandidate
import com.kite.di.graph.DecisionsFile
import com.kite.di.graph.DecisionsJson
import com.kite.di.graph.PendingDecision
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decision cards from every module of the build, each answered in the rules file of
 * the module it came from. The board used to read the application's decisions only,
 * so an ambiguity inside a library module never became a card.
 */
class DecisionsTest {

    private val repo: File = Files.createTempDirectory("kite-board").toFile()

    @AfterTest
    fun cleanUp() {
        repo.deleteRecursively()
    }

    private fun card(subject: String, vararg impls: String) = PendingDecision(
        id = "bind:$subject",
        kind = "bind",
        title = "${subject.substringAfterLast('.')} has ${impls.size} implementations",
        subject = subject,
        candidates = impls.map { impl ->
            DecisionCandidate(
                fqn = impl,
                displayName = impl.substringAfterLast('.'),
                file = "feature/src/main/kotlin/${impl.replace('.', '/')}.kt",
                line = 3,
                insert = "@Bind($subject::class, to = $impl::class)",
            )
        },
    )

    /** A module's build output: decisions.json beside its graph.json. */
    private fun module(name: String, decisions: DecisionsFile): File {
        val kite = repo.resolve("$name/build/kite").apply { mkdirs() }
        kite.resolve("decisions.json").writeText(DecisionsJson.encodePretty(decisions))
        return kite.resolve("graph.json")
    }

    private fun store(): GraphStore {
        val appGraph = module("app", DecisionsFile(module = ":app"))
        val featureGraph = module(
            "feature",
            DecisionsFile(
                module = ":feature",
                rulesFile = "feature/src/main/kotlin/com/example/GraphRules.kt",
                rulesObject = "GraphRules",
                pending = listOf(card("com.example.Repo", "com.example.LocalRepo", "com.example.NetworkRepo")),
            ),
        )
        repo.resolve("feature/src/main/kotlin/com/example").mkdirs()
        repo.resolve("feature/src/main/kotlin/com/example/GraphRules.kt").writeText(
            """
            package com.example

            private object GraphRules
            """.trimIndent() + "\n",
        )
        val fragments = listOf(featureGraph)
        return GraphStore(appGraph, fragments, listOf(appGraph.resolveSibling("decisions.json")) + fragments.map { it.resolveSibling("decisions.json") })
    }

    @Test
    fun `a library module's ambiguity is a card on the board`() {
        val pending = store().pendingDecisions().pending
        assertEquals(listOf("bind:com.example.Repo"), pending.map { it.id })
    }

    @Test
    fun `a card is answered in the rules file of the module it came from`() {
        val (status, body) = DecisionWriter(store(), repo)
            .apply("""{"id":"bind:com.example.Repo","candidateFqn":"com.example.NetworkRepo"}""")
        assertEquals(200, status, body)
        val rules = repo.resolve("feature/src/main/kotlin/com/example/GraphRules.kt").readText()
        assertTrue("@Bind(com.example.Repo::class, to = com.example.NetworkRepo::class)" in rules, rules)
        assertTrue("feature/src/main/kotlin/com/example/GraphRules.kt" in body, body)
    }

    @Test
    fun `a module without a rules file gets one where its processor suggested`() {
        val appGraph = module("app", DecisionsFile(module = ":app"))
        val featureGraph = module(
            "feature",
            DecisionsFile(
                module = ":feature",
                suggestedFile = "feature/src/main/kotlin/com/example/GraphRules.kt",
                suggestedPackage = "com.example",
                pending = listOf(card("com.example.Repo", "com.example.LocalRepo", "com.example.NetworkRepo")),
            ),
        )
        val store = GraphStore(appGraph, listOf(featureGraph), listOf(appGraph, featureGraph).map { it.resolveSibling("decisions.json") })
        val (status, body) = DecisionWriter(store, repo)
            .apply("""{"id":"bind:com.example.Repo","candidateFqn":"com.example.LocalRepo"}""")
        assertEquals(200, status, body)
        val created = repo.resolve("feature/src/main/kotlin/com/example/GraphRules.kt").readText()
        assertTrue(created.startsWith("package com.example"), created)
        assertTrue("@Bind(com.example.Repo::class, to = com.example.LocalRepo::class)" in created, created)
    }

    @Test
    fun `a card no module has pending any more is refused`() {
        val (status, body) = DecisionWriter(store(), repo).apply("""{"id":"bind:com.example.Gone","candidateFqn":"x"}""")
        assertEquals(400, status)
        assertTrue("no longer pending" in body, body)
    }

    @Test
    fun `the decisions route serves every module's cards`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = BoardServer(port, store(), repo)
        Thread({ server.start() }, "board-under-test").apply { isDaemon = true }.start()
        repeat(50) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return@repeat
            Thread.sleep(100)
        }
        val connection = URL("http://127.0.0.1:$port/api/decisions").openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        val body = connection.inputStream.bufferedReader().readText()
        assertEquals(200, connection.responseCode)
        assertTrue("bind:com.example.Repo" in body, body)
    }
}
