package com.kite.di.graph

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphDiffTest {

    private fun node(id: String, scope: String? = null) = GraphNode(
        id = id, type = id, displayName = id.substringAfterLast('.'),
        kind = NodeKind.INJECTABLE, scope = scope,
    )

    private fun edge(from: String, to: String, index: Int = 0) = GraphEdge(
        id = GraphEdge.edgeId(from, to, index), from = from, to = to,
        siteKind = SiteKind.CONSTRUCTOR_PARAM,
    )

    private fun snapshot(nodes: List<GraphNode>, edges: List<GraphEdge>) = GraphSnapshot(
        appId = "test", variant = "debug",
        nodes = nodes.sortedBy { it.id }, edges = edges.sortedBy { it.id },
    )

    @Test
    fun `identical snapshots produce no ops`() {
        val s = snapshot(listOf(node("a.A"), node("b.B")), listOf(edge("a.A", "b.B")))
        assertTrue(GraphDiff.diff(s, s).isEmpty())
    }

    @Test
    fun `added node and edge are reported`() {
        val old = snapshot(listOf(node("a.A")), emptyList())
        val new = snapshot(listOf(node("a.A"), node("b.B")), listOf(edge("a.A", "b.B")))
        val ops = GraphDiff.diff(old, new)
        assertEquals(
            listOf<PatchOp>(
                PatchOp.AddNode(node("b.B")),
                PatchOp.AddEdge(edge("a.A", "b.B")),
            ),
            ops,
        )
    }

    @Test
    fun `scope change is an update not add-remove`() {
        val old = snapshot(listOf(node("a.A")), emptyList())
        val new = snapshot(listOf(node("a.A", scope = "Singleton")), emptyList())
        assertEquals(listOf<PatchOp>(PatchOp.UpdateNode(node("a.A", scope = "Singleton"))), GraphDiff.diff(old, new))
    }

    @Test
    fun `apply of diff reaches the target`() {
        val old = snapshot(
            listOf(node("a.A"), node("b.B"), node("c.C", scope = "Singleton")),
            listOf(edge("a.A", "b.B"), edge("b.B", "c.C")),
        )
        val new = snapshot(
            listOf(node("a.A", scope = "ActivityScoped"), node("c.C", scope = "Singleton"), node("d.D")),
            listOf(edge("a.A", "c.C"), edge("d.D", "c.C")),
        )
        val applied = GraphDiff.apply(old, GraphDiff.diff(old, new))
        assertEquals(new.nodes, applied.nodes)
        assertEquals(new.edges, applied.edges)
    }

    @Test
    fun `patch ops serialize with op discriminator`() {
        val op: PatchOp = PatchOp.RemoveNode("a.A")
        val text = GraphJson.json.encodeToString(PatchOp.serializer(), op)
        assertEquals("""{"op":"removeNode","nodeId":"a.A"}""", text)
    }
}
