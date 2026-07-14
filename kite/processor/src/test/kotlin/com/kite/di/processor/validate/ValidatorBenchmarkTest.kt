package com.kite.di.processor.validate

import com.kite.di.graph.Key
import com.kite.di.graph.Provenance
import com.kite.di.graph.SiteKind
import com.kite.di.processor.model.BindingDeclKind
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.DependencyModel
import com.kite.di.processor.model.ScanResult
import com.kite.di.processor.model.Severity
import com.kite.di.processor.model.TypeRef
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Budget: full-graph validation of 2 000 bindings in < 300 ms.
 * The synthetic graph is layered (like real DI graphs): each binding depends on
 * up to three bindings from earlier layers, ~6 000 edges total.
 */
class ValidatorBenchmarkTest {

    @Test
    fun `validates 2000 bindings within the 300ms budget`() {
        val bindings = syntheticGraph(2_000)
        // warm-up (JIT) — the budget applies to a warmed processor, matching an
        // incremental build where the daemon is hot
        GraphValidator.validate(ScanResult(bindings = bindings))

        val start = System.nanoTime()
        val issues = GraphValidator.validate(ScanResult(bindings = bindings))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(emptyList(), issues.filter { it.severity == Severity.ERROR })
        assertTrue(elapsedMs < 300, "validation took ${elapsedMs}ms (budget 300ms)")
    }

    private fun syntheticGraph(count: Int): List<BindingModel> {
        val where = Provenance(":app", "app/src/Gen.kt", 1)
        return (0 until count).map { i ->
            val fqn = "gen.p${i % 40}.Type$i"
            // deterministic pseudo-random deps into strictly earlier indices
            val deps = (1..minOf(3, i)).mapNotNull { k ->
                val target = (i * 31 + k * 17) % i
                DependencyModel(
                    key = Key("gen.p${target % 40}.Type$target"),
                    type = TypeRef("gen.p${target % 40}", listOf("Type$target")),
                    siteKind = SiteKind.CONSTRUCTOR_PARAM,
                    paramName = "d$k",
                    site = where,
                )
            }
            BindingModel(
                key = Key(fqn),
                keyType = TypeRef("gen.p${i % 40}", listOf("Type$i")),
                declKind = BindingDeclKind.INJECTABLE,
                scopeLevel = if (i % 5 == 0) 0 else null,
                scopeName = if (i % 5 == 0) "Singleton" else null,
                declaration = "Type$i",
                provenance = where,
                dependencies = deps,
                targetType = TypeRef("gen.p${i % 40}", listOf("Type$i")),
            )
        }
    }
}
