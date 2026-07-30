package com.kite.di.processor.export

import com.kite.di.graph.Provenance
import com.kite.di.processor.scan.RuleSet
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure decision-model assembly: no KSP, no filesystem. Pins the
 * exact card payloads the board renders and the exact `@Bind(...)` line a click
 * writes into GraphRules.kt.
 */
class DecisionsExporterTest {

    private val repo = "com.app.data.UserRepository"
    private val network = "com.app.data.NetworkUserRepository"
    private val fake = "com.app.data.FakeUserRepository"

    @Test
    fun `bind insert is a compile-checked class-literal line, no strings`() {
        assertEquals(
            "@Bind(com.app.data.UserRepository::class, to = com.app.data.NetworkUserRepository::class)",
            DecisionsExporter.bindInsert(repo, network),
        )
    }

    @Test
    fun `an ambiguity becomes one card with a candidate per implementation`() {
        val decision = DecisionsExporter.bindAmbiguity(
            subjectFqn = repo,
            subjectDisplay = "UserRepository",
            consumers = listOf("GreetingUseCase (app/src/Presenters.kt:14)"),
            candidates = listOf(
                DecisionsExporter.Candidate(network, "NetworkUserRepository", Provenance(":app", "app/src/UserRepository.kt", 18)),
                DecisionsExporter.Candidate(fake, "FakeUserRepository", Provenance(":app", "app/src/Fakes.kt", 7)),
            ),
        )

        assertEquals("bind:$repo", decision.id)
        assertEquals("bind", decision.kind)
        assertEquals("UserRepository has 2 implementations", decision.title)
        assertEquals(repo, decision.subject)
        assertEquals(listOf("GreetingUseCase (app/src/Presenters.kt:14)"), decision.consumers)

        val first = decision.candidates.first()
        assertEquals(network, first.fqn)
        assertEquals("NetworkUserRepository", first.displayName)
        assertEquals("app/src/UserRepository.kt", first.file)
        assertEquals(18, first.line)
        assertEquals(DecisionsExporter.bindInsert(repo, network), first.insert)
    }

    @Test
    fun `export names the module holder when one exists and suggests nothing`() {
        val rules = RuleSet(
            holderFile = "app/src/main/java/com/app/di/GraphRules.kt",
            holderObject = "GraphRules",
        )
        val decision = DecisionsExporter.bindAmbiguity(repo, "UserRepository", emptyList(), emptyList())
        val file = DecisionsExporter.export(listOf(decision), rules, ":app")

        assertEquals(":app", file.module)
        assertEquals("app/src/main/java/com/app/di/GraphRules.kt", file.rulesFile)
        assertEquals("GraphRules", file.rulesObject)
        assertNull(file.suggestedFile)
        assertNull(file.suggestedPackage)
        assertEquals(1, file.pending.size)
    }

    @Test
    fun `export suggests a holder from the subject package when the module has none`() {
        val decision = DecisionsExporter.bindAmbiguity(repo, "UserRepository", emptyList(), emptyList())
        val file = DecisionsExporter.export(listOf(decision), RuleSet.EMPTY, ":app")

        assertNull(file.rulesFile)
        assertEquals("app/src/main/java/com/app/data/GraphRules.kt", file.suggestedFile)
        assertEquals("com.app.data", file.suggestedPackage)
    }

    @Test
    fun `an empty pending list exports verbatim - that is what clears the board cards`() {
        val file = DecisionsExporter.export(emptyList(), RuleSet.EMPTY, ":app")
        assertTrue(file.pending.isEmpty())
        assertNull(file.suggestedFile) // no decisions → no holder to suggest
    }

    @Test
    fun `suggest holder maps a nested gradle path and the subject package to a source path`() {
        val (path, pkg) = DecisionsExporter.suggestHolder(":feature:cart", "com.app.cart.Cart")
        assertEquals("feature/cart/src/main/java/com/app/cart/GraphRules.kt", path)
        assertEquals("com.app.cart", pkg)
    }
}
