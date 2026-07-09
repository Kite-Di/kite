package com.kite.di.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One incremental change to a graph. The same shapes exist in TypeScript
 * (`webboard/src/model/diff.ts`) — the board uses them to animate snapshot diffs.
 * JSON encoding uses `"op"` as the discriminator: `{"op":"addNode","node":{...}}`.
 */
@Serializable
sealed class PatchOp {
    @Serializable
    @SerialName("addNode")
    data class AddNode(val node: GraphNode) : PatchOp()

    @Serializable
    @SerialName("updateNode")
    data class UpdateNode(val node: GraphNode) : PatchOp()

    @Serializable
    @SerialName("removeNode")
    data class RemoveNode(val nodeId: String) : PatchOp()

    @Serializable
    @SerialName("addEdge")
    data class AddEdge(val edge: GraphEdge) : PatchOp()

    @Serializable
    @SerialName("removeEdge")
    data class RemoveEdge(val edgeId: String) : PatchOp()
}

object GraphDiff {

    /**
     * Structural diff by stable node/edge id. Op order is apply-safe:
     * removed edges → removed nodes → added nodes → updated nodes → added edges.
     * Node updates compare [GraphNode.scope], [GraphNode.kind], [GraphNode.boundTo]
     * and [GraphNode.providedBy]; runtime state is not part of the structural diff.
     */
    fun diff(old: GraphSnapshot, new: GraphSnapshot): List<PatchOp> {
        val oldNodes = old.nodes.associateBy { it.id }
        val newNodes = new.nodes.associateBy { it.id }
        val oldEdges = old.edges.associateBy { it.id }
        val newEdges = new.edges.associateBy { it.id }

        val ops = mutableListOf<PatchOp>()
        for (edge in old.edges) if (edge.id !in newEdges) ops += PatchOp.RemoveEdge(edge.id)
        for (node in old.nodes) if (node.id !in newNodes) ops += PatchOp.RemoveNode(node.id)
        for (node in new.nodes) {
            val prev = oldNodes[node.id]
            when {
                prev == null -> ops += PatchOp.AddNode(node)
                !structurallyEqual(prev, node) -> ops += PatchOp.UpdateNode(node)
            }
        }
        for (edge in new.edges) {
            val prev = oldEdges[edge.id]
            if (prev == null) {
                ops += PatchOp.AddEdge(edge)
            } else if (prev != edge) {
                // Same id but changed payload (e.g. site moved): re-add.
                ops += PatchOp.RemoveEdge(edge.id)
                ops += PatchOp.AddEdge(edge)
            }
        }
        return ops
    }

    /** Applies [ops] to [base]; `apply(base, diff(base, target))` structurally equals `target`. */
    fun apply(base: GraphSnapshot, ops: List<PatchOp>): GraphSnapshot {
        val nodes = base.nodes.associateBy { it.id }.toMutableMap()
        val edges = base.edges.associateBy { it.id }.toMutableMap()
        for (op in ops) when (op) {
            is PatchOp.AddNode -> nodes[op.node.id] = op.node
            is PatchOp.UpdateNode -> nodes[op.node.id] = op.node
            is PatchOp.RemoveNode -> nodes.remove(op.nodeId)
            is PatchOp.AddEdge -> edges[op.edge.id] = op.edge
            is PatchOp.RemoveEdge -> edges.remove(op.edgeId)
        }
        return base.copy(
            nodes = nodes.values.sortedBy { it.id },
            edges = edges.values.sortedBy { it.id },
        )
    }

    private fun structurallyEqual(a: GraphNode, b: GraphNode): Boolean =
        a.scope == b.scope && a.kind == b.kind && a.boundTo == b.boundTo && a.providedBy == b.providedBy
}
