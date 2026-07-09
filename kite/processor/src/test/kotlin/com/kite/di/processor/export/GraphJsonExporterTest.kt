package com.kite.di.processor.export

import com.kite.di.graph.Key
import com.kite.di.graph.NodeKind
import com.kite.di.graph.Provenance
import com.kite.di.graph.SiteKind
import com.kite.di.processor.model.BindingDeclKind
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.DependencyModel
import com.kite.di.processor.model.FieldInjectionModel
import com.kite.di.processor.model.MemberInjectModel
import com.kite.di.processor.model.ScanResult
import com.kite.di.processor.model.TypeRef
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
                declKind = BindingDeclKind.INJECTABLE,
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
        memberInjects = listOf(
            MemberInjectModel(
                targetType = TypeRef("a", listOf("MainActivity")),
                fields = listOf(
                    FieldInjectionModel("repo", Key("a.Repo"), TypeRef("a", listOf("Repo")), site = WHERE)
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
        assertEquals(NodeKind.ENTRY_POINT, byId.getValue("a.MainActivity").kind)
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

        val fieldEdge = snapshot.edges.first { it.from == "a.MainActivity" }
        assertEquals(SiteKind.FIELD, fieldEdge.siteKind)
        assertEquals("a.Repo", fieldEdge.to)
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
                    declKind = BindingDeclKind.INJECTABLE,
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
                    declKind = BindingDeclKind.INJECTABLE, declaration = "T",
                    provenance = WHERE, targetType = TypeRef("a", listOf("T")),
                ),
            )
        )
        val snapshot = GraphJsonExporter.export(twoParams, "x", "debug")
        val ids = snapshot.edges.map { it.id }
        assertTrue("a.S -> a.T # 0" in ids && "a.S -> a.T # 1" in ids, ids.toString())
    }
}
