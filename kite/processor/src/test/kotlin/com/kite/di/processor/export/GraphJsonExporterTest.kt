package com.kite.di.processor.export

import com.kite.di.graph.Key
import com.kite.di.graph.NodeKind
import com.kite.di.graph.Provenance
import com.kite.di.graph.SiteKind
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.DependencyModel
import com.kite.di.processor.model.ScanResult
import com.kite.di.processor.model.SetBindingModel
import com.kite.di.processor.model.TypeRef
import com.kite.di.processor.model.ViewModelModel
import com.kite.di.processor.model.ViewModelParam
import com.kite.di.processor.model.setKeyOf
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val WHERE = Provenance(":app", "app/src/X.kt", 5)

class GraphJsonExporterTest {

    private val scan = ScanResult(
        bindings = listOf(
            BindingModel(
                key = Key("a.RealRepo"),
                keyType = TypeRef("a", listOf("RealRepo")),
                extraKeys = listOf(Key("a.Repo")),
                extraKeyTypes = listOf(TypeRef("a", listOf("Repo"))),
                scopeLevel = 0,
                scopeName = "Singleton",
                declaration = "RealRepo",
                provenance = WHERE,
                dependencies = listOf(
                    DependencyModel(
                        key = Key("android.content.Context"),
                        type = TypeRef("android.content", listOf("Context")),
                        siteKind = SiteKind.CONSTRUCTOR_PARAM,
                        paramName = "context",
                        site = WHERE,
                    )
                ),
                targetType = TypeRef("a", listOf("RealRepo")),
            ),
        ),
        viewModels = listOf(
            ViewModelModel(
                targetType = TypeRef("a", listOf("MainViewModel")),
                params = listOf(
                    ViewModelParam.Injected(
                        "repo",
                        DependencyModel(
                            key = Key("a.Repo"),
                            type = TypeRef("a", listOf("Repo")),
                            siteKind = SiteKind.CONSTRUCTOR_PARAM,
                            paramName = "repo",
                            site = WHERE,
                        ),
                    ),
                ),
                provenance = WHERE,
            )
        ),
    )

    @Test
    fun `exports binding, satellite interface, entry point and external nodes`() {
        val snapshot = GraphJsonExporter.export(scan, "com.example.app", "debug")
        val byId = snapshot.nodes.associateBy { it.id }

        assertEquals(NodeKind.INJECTABLE, byId.getValue("a.RealRepo").kind)
        assertEquals(listOf("a.Repo"), byId.getValue("a.RealRepo").boundTo)
        assertEquals(NodeKind.BOUND_INTERFACE, byId.getValue("a.Repo").kind)
        assertEquals(NodeKind.ENTRY_POINT, byId.getValue("a.MainViewModel").kind)
        assertEquals(NodeKind.EXTERNAL, byId.getValue("android.content.Context").kind)

        val providedBy = byId.getValue("a.RealRepo").providedBy
        assertNotNull(providedBy)
        assertEquals("app/src/X.kt", providedBy.file)
        assertEquals(5, providedBy.line)
    }

    @Test
    fun `edges carry site info and stable ids`() {
        val snapshot = GraphJsonExporter.export(scan, "com.example.app", "debug")
        val ctorEdge = snapshot.edges.first { it.from == "a.RealRepo" }
        assertEquals("a.RealRepo -> android.content.Context # 0", ctorEdge.id)
        assertEquals("context", ctorEdge.paramName)
        assertEquals(5, ctorEdge.site?.line)

        val vmEdge = snapshot.edges.first { it.from == "a.MainViewModel" }
        assertEquals(SiteKind.CONSTRUCTOR_PARAM, vmEdge.siteKind)
        assertEquals("a.Repo", vmEdge.to)
    }

    @Test
    fun `set aggregate exports one node with edges to each implementation`() {
        val sets = ScanResult(
            bindings = listOf(
                BindingModel(
                    key = Key("a.TaskA"), keyType = TypeRef("a", listOf("TaskA")),
                    declaration = "TaskA", provenance = WHERE, targetType = TypeRef("a", listOf("TaskA")),
                ),
                BindingModel(
                    key = Key("a.TaskB"), keyType = TypeRef("a", listOf("TaskB")),
                    declaration = "TaskB", provenance = WHERE, targetType = TypeRef("a", listOf("TaskB")),
                ),
            ),
            setBindings = listOf(
                SetBindingModel(
                    key = setKeyOf("a.Task"),
                    elementType = TypeRef("a", listOf("Task")),
                    elementKeys = listOf(Key("a.TaskA"), Key("a.TaskB")),
                    elementTypes = listOf(TypeRef("a", listOf("TaskA")), TypeRef("a", listOf("TaskB"))),
                    provenance = WHERE,
                )
            ),
        )
        val snapshot = GraphJsonExporter.export(sets, "x", "debug")
        val aggregate = snapshot.nodes.first { it.kind == NodeKind.SET }
        assertEquals("kotlin.collections.Set<a.Task>", aggregate.id)
        assertEquals("Set<Task>", aggregate.displayName)
        val contributionEdges = snapshot.edges.filter { it.from == aggregate.id }
        assertEquals(setOf("a.TaskA", "a.TaskB"), contributionEdges.map { it.to }.toSet())
        assertTrue(contributionEdges.all { it.siteKind == SiteKind.SET_CONTRIBUTION })
    }

    @Test
    fun `graph argument dependencies surface as external nodes`() {
        val args = ScanResult(
            bindings = listOf(
                BindingModel(
                    key = Key("a.Api"), keyType = TypeRef("a", listOf("Api")),
                    declaration = "Api", provenance = WHERE, targetType = TypeRef("a", listOf("Api")),
                    dependencies = listOf(
                        DependencyModel(
                            key = Key("kotlin.String", qualifier = "apiKey"),
                            type = TypeRef("kotlin", listOf("String")),
                            siteKind = SiteKind.CONSTRUCTOR_PARAM,
                            paramName = "apiKey",
                            isGraphArg = true,
                            site = WHERE,
                        )
                    ),
                ),
            ),
        )
        val snapshot = GraphJsonExporter.export(args, "x", "debug")
        val external = snapshot.nodes.first { it.kind == NodeKind.EXTERNAL }
        assertEquals("apiKey@kotlin.String", external.id)
        assertEquals("apiKey: String", external.displayName)
    }

    @Test
    fun `output is deterministic - sorted nodes and edges, no timestamp`() {
        val a = GraphJsonExporter.export(scan, "com.example.app", "debug")
        val b = GraphJsonExporter.export(scan, "com.example.app", "debug")
        assertEquals(a, b)
        assertEquals(a.nodes.map { it.id }, a.nodes.map { it.id }.sorted())
        assertEquals(a.edges.map { it.id }, a.edges.map { it.id }.sorted())
        assertEquals(null, a.generatedAt)
    }

    @Test
    fun `two params of same type get distinct edge ids`() {
        val twoParams = ScanResult(
            bindings = listOf(
                BindingModel(
                    key = Key("a.S"),
                    keyType = TypeRef("a", listOf("S")),
                    declaration = "S",
                    provenance = WHERE,
                    dependencies = listOf(
                        DependencyModel(Key("a.T"), TypeRef("a", listOf("T")), SiteKind.CONSTRUCTOR_PARAM, paramName = "first", site = WHERE),
                        DependencyModel(Key("a.T"), TypeRef("a", listOf("T")), SiteKind.CONSTRUCTOR_PARAM, paramName = "second", site = WHERE),
                    ),
                    targetType = TypeRef("a", listOf("S")),
                ),
                BindingModel(
                    key = Key("a.T"), keyType = TypeRef("a", listOf("T")),
                    declaration = "T",
                    provenance = WHERE, targetType = TypeRef("a", listOf("T")),
                ),
            )
        )
        val snapshot = GraphJsonExporter.export(twoParams, "x", "debug")
        val ids = snapshot.edges.map { it.id }
        assertTrue("a.S -> a.T # 0" in ids && "a.S -> a.T # 1" in ids, ids.toString())
    }
}
