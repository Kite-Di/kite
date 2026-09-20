package com.kite.di.board

import com.kite.di.graph.GraphEdge
import com.kite.di.graph.GraphNode
import com.kite.di.graph.GraphSnapshot
import com.kite.di.graph.NodeKind
import com.kite.di.graph.ScopeDef
import com.kite.di.graph.SiteKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Merging the per-module fragments into the one canvas the board draws. The case
 * that matters is the cross-module dependency: the consuming module only knows it
 * as a dangling `external` placeholder, and the owning module knows what it is.
 */
class MergeTest {

    private fun node(id: String, kind: NodeKind = NodeKind.INJECTABLE) =
        GraphNode(id = id, type = id, displayName = id.substringAfterLast('.'), kind = kind)

    private fun edge(id: String, from: String, to: String) =
        GraphEdge(id = id, from = from, to = to, siteKind = SiteKind.CONSTRUCTOR_PARAM)

    private fun snapshot(
        appId: String = "com.example",
        nodes: List<GraphNode> = emptyList(),
        edges: List<GraphEdge> = emptyList(),
        scopes: List<ScopeDef> = emptyList(),
    ) = GraphSnapshot(appId = appId, variant = "debug", nodes = nodes, edges = edges, scopes = scopes)

    @Test
    fun `the owning module's real node replaces a consumer's external placeholder`() {
        val app = snapshot(nodes = listOf(node("core.Analytics", NodeKind.EXTERNAL)))
        val core = snapshot(nodes = listOf(node("core.Analytics", NodeKind.INJECTABLE)))

        val merged = mergeSnapshots(app, listOf(core))

        assertEquals(1, merged.nodes.size)
        assertEquals(NodeKind.INJECTABLE, merged.nodes.single().kind)
    }

    @Test
    fun `a real node is not downgraded by a placeholder from another fragment`() {
        val app = snapshot(nodes = listOf(node("core.Analytics", NodeKind.INJECTABLE)))
        val other = snapshot(nodes = listOf(node("core.Analytics", NodeKind.EXTERNAL)))

        assertEquals(NodeKind.INJECTABLE, mergeSnapshots(app, listOf(other)).nodes.single().kind)
    }

    @Test
    fun `nodes, edges and scopes unite without duplicates and come out sorted`() {
        val app = snapshot(
            nodes = listOf(node("b.B"), node("a.A")),
            edges = listOf(edge("e2", "a.A", "b.B")),
            scopes = listOf(ScopeDef("Singleton", 0)),
        )
        val fragment = snapshot(
            nodes = listOf(node("a.A"), node("c.C")),
            edges = listOf(edge("e2", "a.A", "b.B"), edge("e1", "c.C", "a.A")),
            scopes = listOf(ScopeDef("Singleton", 0), ScopeDef("ActivityScoped", 1)),
        )

        val merged = mergeSnapshots(app, listOf(fragment))

        assertEquals(listOf("a.A", "b.B", "c.C"), merged.nodes.map { it.id })
        assertEquals(listOf("e1", "e2"), merged.edges.map { it.id })
        assertEquals(listOf("Singleton", "ActivityScoped"), merged.scopes.map { it.name })
    }

    @Test
    fun `identity comes from the application fragment, not the libraries`() {
        val app = snapshot(appId = "com.example.app")
        val library = snapshot(appId = "com.example.core")

        assertEquals("com.example.app", mergeSnapshots(app, listOf(library)).appId)
    }

    @Test
    fun `merging is deterministic regardless of fragment order`() {
        val app = snapshot(nodes = listOf(node("a.A")))
        val one = snapshot(nodes = listOf(node("b.B")))
        val two = snapshot(nodes = listOf(node("c.C")))

        assertEquals(
            mergeSnapshots(app, listOf(one, two)).nodes.map { it.id },
            mergeSnapshots(app, listOf(two, one)).nodes.map { it.id },
        )
    }
}
