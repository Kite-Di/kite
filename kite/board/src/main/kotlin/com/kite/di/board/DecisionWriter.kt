package com.kite.di.board

import com.kite.di.graph.GraphJson
import java.io.File

/**
 * A decision card was clicked: the chosen candidate's rule line goes into the
 * module's GraphRules.kt. The board is the only place that writes source, and it
 * writes exactly one line — the same line the build printed as a hint.
 *
 * The next build re-runs inference, the decision disappears from decisions.json,
 * and the card goes away on its own.
 */
class DecisionWriter(private val store: GraphStore, private val repoRoot: File) {

    /** Returns an HTTP status and the JSON body to answer with. */
    fun apply(body: String): Pair<Int, String> {
        val id = field(body, "id") ?: return 400 to error("missing id")
        val candidateFqn = field(body, "candidateFqn") ?: return 400 to error("missing candidateFqn")

        val decisions = store.decisions() ?: return 400 to error("no decisions.json — build the module first")
        val decision = decisions.pending.firstOrNull { it.id == id }
            ?: return 400 to error("decision $id is no longer pending")
        val candidate = decision.candidates.firstOrNull { it.fqn == candidateFqn }
            ?: return 400 to error("$candidateFqn is not a candidate of $id")

        val rulesPath = decisions.rulesFile ?: decisions.suggestedFile
            ?: return 400 to error("nowhere to write the rule: no rules file and no suggestion")
        val target = repoRoot.resolve(rulesPath)

        return runCatching {
            val line: Int
            if (target.isFile && decisions.rulesObject != null) {
                val insertion = insertRule(target.readText(), decisions.rulesObject!!, candidate.insert)
                target.writeText(insertion.source)
                line = insertion.line
            } else {
                target.parentFile?.mkdirs()
                val source = newRulesFile(decisions.suggestedPackage.orEmpty(), candidate.insert)
                target.writeText(source)
                line = source.split("\n").indexOfFirst { it.trim() == candidate.insert.trim() } + 1
            }
            200 to """{"ok":true,"file":${json(rulesPath)},"line":$line}"""
        }.getOrElse { 500 to error(it.message ?: "could not write the rule") }
    }

    private fun error(message: String) = """{"error":${json(message)}}"""

    private fun json(text: String) =
        GraphJson.json.encodeToString(kotlinx.serialization.serializer<String>(), text)

    /** The board posts `{"id": "...", "candidateFqn": "..."}` — two string fields. */
    private fun field(body: String, name: String): String? =
        Regex("\"${Regex.escape(name)}\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
}
