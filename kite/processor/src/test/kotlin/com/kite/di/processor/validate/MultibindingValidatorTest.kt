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
import com.kite.di.processor.model.setKeyOf
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun typeRef(fqn: String) = TypeRef(fqn.substringBeforeLast('.'), listOf(fqn.substringAfterLast('.')))

private val WHERE = Provenance(":app", "app/src/M.kt", 5)

private fun contribution(name: String, elementFqn: String, deps: List<DependencyModel> = emptyList()) = BindingModel(
    key = Key(elementFqn),
    keyType = typeRef(elementFqn),
    declKind = BindingDeclKind.PROVIDES,
    declaration = "Mod.$name",
    provenance = WHERE,
    dependencies = deps,
    targetType = typeRef("m.Mod"),
    providesFunction = name,
    intoSet = true,
)

private fun consumer(fqn: String, elementFqn: String) = BindingModel(
    key = Key(fqn),
    keyType = typeRef(fqn),
    declKind = BindingDeclKind.INJECTABLE,
    declaration = fqn.substringAfterLast('.'),
    provenance = WHERE,
    dependencies = listOf(
        DependencyModel(
            key = setKeyOf(elementFqn, null),
            type = typeRef(elementFqn),
            siteKind = SiteKind.CONSTRUCTOR_PARAM,
            paramName = "items",
            setElement = typeRef(elementFqn),
            site = WHERE,
        )
    ),
    targetType = typeRef(fqn),
)

class MultibindingValidatorTest {

    private fun errors(scan: ScanResult) = GraphValidator.validate(scan).filter { it.severity == Severity.ERROR }

    @Test
    fun `two contributions of the same element type are not duplicates`() {
        val scan = ScanResult(
            bindings = listOf(
                contribution("a", "m.Task"),
                contribution("b", "m.Task"),
                consumer("m.Runner", "m.Task"),
            )
        )
        assertEquals(emptyList(), errors(scan))
    }

    @Test
    fun `Set consumer without any contribution is a missing binding with IntoSet hint`() {
        val scan = ScanResult(bindings = listOf(consumer("m.Runner", "m.Task")))
        val e = errors(scan).single()
        assertTrue("kotlin.collections.Set<m.Task>" in e.message, e.message)
        assertTrue("@IntoSet" in e.message, e.message)
    }

    @Test
    fun `direct binding of the Set key conflicts with IntoSet contributions`() {
        val direct = BindingModel(
            key = setKeyOf("m.Task", null),
            keyType = typeRef("m.TaskSet"),
            declKind = BindingDeclKind.PROVIDES,
            declaration = "Mod.wholeSet",
            provenance = WHERE,
            targetType = typeRef("m.Mod"),
            providesFunction = "wholeSet",
        )
        val scan = ScanResult(bindings = listOf(direct, contribution("a", "m.Task"), consumer("m.Runner", "m.Task")))
        val e = errors(scan).single()
        assertTrue("Conflict for kotlin.collections.Set<m.Task>" in e.message, e.message)
    }

    @Test
    fun `cycle through a set contribution is detected`() {
        // Runner -> Set<Task>; contribution needs Runner -> cycle
        val scan = ScanResult(
            bindings = listOf(
                contribution(
                    "needsRunner", "m.Task",
                    deps = listOf(
                        DependencyModel(
                            key = Key("m.Runner"), type = typeRef("m.Runner"),
                            siteKind = SiteKind.PROVIDES_PARAM, paramName = "runner", site = WHERE,
                        )
                    ),
                ),
                consumer("m.Runner", "m.Task"),
            )
        )
        val e = errors(scan).single()
        assertTrue("Dependency cycle detected" in e.message, e.message)
    }

    @Test
    fun `contribution dependencies are validated`() {
        val scan = ScanResult(
            bindings = listOf(
                contribution(
                    "broken", "m.Task",
                    deps = listOf(
                        DependencyModel(
                            key = Key("m.Missing"), type = typeRef("m.Missing"),
                            siteKind = SiteKind.PROVIDES_PARAM, paramName = "x", site = WHERE,
                        )
                    ),
                ),
                consumer("m.Runner", "m.Task"),
            )
        )
        val e = errors(scan).single()
        assertTrue("m.Missing" in e.message, e.message)
    }
}
