package com.kite.di.processor.validate

import com.kite.di.graph.DeferredKind
import com.kite.di.graph.Key
import com.kite.di.graph.Provenance
import com.kite.di.graph.SiteKind
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.DependencyModel
import com.kite.di.processor.model.ScanResult
import com.kite.di.processor.model.SetBindingModel
import com.kite.di.processor.model.Severity
import com.kite.di.processor.model.TypeRef
import com.kite.di.processor.model.setKeyOf
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Inferred `Set<I>` multibindings against the validator. */
class MultibindingValidatorTest {

    private val where = Provenance(":app", "app/src/X.kt", 5)

    private fun typeRef(fqn: String) = TypeRef(fqn.substringBeforeLast('.'), listOf(fqn.substringAfterLast('.')))

    private fun binding(fqn: String, deps: List<DependencyModel> = emptyList()) = BindingModel(
        key = Key(fqn),
        keyType = typeRef(fqn),
        declaration = fqn.substringAfterLast('.'),
        provenance = where,
        dependencies = deps,
        targetType = typeRef(fqn),
    )

    private fun setDep(elementFqn: String, deferred: DeferredKind = DeferredKind.NONE) = DependencyModel(
        key = setKeyOf(elementFqn),
        type = typeRef(elementFqn),
        siteKind = SiteKind.CONSTRUCTOR_PARAM,
        deferred = deferred,
        paramName = "tasks",
        setElement = typeRef(elementFqn),
        site = where,
    )

    private fun setBinding(elementFqn: String, vararg implFqns: String) = SetBindingModel(
        key = setKeyOf(elementFqn),
        elementType = typeRef(elementFqn),
        elementKeys = implFqns.map { Key(it) },
        elementTypes = implFqns.map { typeRef(it) },
        provenance = where,
    )

    private fun errors(scan: ScanResult) =
        GraphValidator.validate(scan).filter { it.severity == Severity.ERROR }

    @Test
    fun `set consumer resolves against the aggregate key`() {
        val scan = ScanResult(
            bindings = listOf(
                binding("a.Runner", deps = listOf(setDep("a.Task"))),
                binding("a.TaskA"),
                binding("a.TaskB"),
            ),
            setBindings = listOf(setBinding("a.Task", "a.TaskA", "a.TaskB")),
        )
        assertEquals(emptyList(), errors(scan))
    }

    @Test
    fun `set dependency without an aggregate is a missing binding`() {
        val scan = ScanResult(bindings = listOf(binding("a.Runner", deps = listOf(setDep("a.Task")))))
        val e = errors(scan).single()
        assertTrue("Missing binding: no provider for kotlin.collections.Set<a.Task>" in e.message, e.message)
    }

    @Test
    fun `direct binding clashing with a set aggregate key is an error`() {
        val direct = BindingModel(
            key = setKeyOf("a.Task"),
            keyType = typeRef("a.Tasks"),
            declaration = "Tasks",
            provenance = where,
            targetType = typeRef("a.Tasks"),
        )
        val scan = ScanResult(
            bindings = listOf(direct, binding("a.TaskA")),
            setBindings = listOf(setBinding("a.Task", "a.TaskA")),
        )
        val e = errors(scan).single()
        assertTrue("Conflict for kotlin.collections.Set<a.Task>" in e.message, e.message)
    }

    @Test
    fun `cycle through a set element is detected`() {
        // Runner → Set<Task> → TaskA → Runner
        val scan = ScanResult(
            bindings = listOf(
                binding("a.Runner", deps = listOf(setDep("a.Task"))),
                binding("a.TaskA", deps = listOf(
                    DependencyModel(
                        key = Key("a.Runner"),
                        type = typeRef("a.Runner"),
                        siteKind = SiteKind.CONSTRUCTOR_PARAM,
                        paramName = "runner",
                        site = where,
                    )
                )),
            ),
            setBindings = listOf(setBinding("a.Task", "a.TaskA")),
        )
        val e = errors(scan).single()
        assertTrue("Dependency cycle detected" in e.message, e.message)
    }

    @Test
    fun `cycle through a deferred set edge is legal`() {
        val scan = ScanResult(
            bindings = listOf(
                binding("a.Runner", deps = listOf(setDep("a.Task", deferred = DeferredKind.LAZY))),
                binding("a.TaskA", deps = listOf(
                    DependencyModel(
                        key = Key("a.Runner"),
                        type = typeRef("a.Runner"),
                        siteKind = SiteKind.CONSTRUCTOR_PARAM,
                        paramName = "runner",
                        site = where,
                    )
                )),
            ),
            setBindings = listOf(setBinding("a.Task", "a.TaskA")),
        )
        assertEquals(emptyList(), errors(scan))
    }
}
