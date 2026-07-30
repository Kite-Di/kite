package com.kite.di.processor.export

import com.kite.di.graph.DecisionCandidate
import com.kite.di.graph.DecisionsFile
import com.kite.di.graph.PendingDecision
import com.kite.di.graph.Provenance
import com.kite.di.processor.scan.RuleSet

/**
 * Model → [DecisionsFile]. Pure string/model assembly so the
 * card payloads are unit-testable without KSP. The processor writes the result
 * next to graph.json on every debug compilation — pending decisions when
 * inference is blocked, an empty list on success (which clears the cards).
 */
object DecisionsExporter {

    data class Candidate(val fqn: String, val displayName: String, val site: Provenance)

    /** The exact rules line that answers "bind [subjectFqn] to [implFqn]". */
    fun bindInsert(subjectFqn: String, implFqn: String): String =
        "@Bind($subjectFqn::class, to = $implFqn::class)"

    /** An E1 ambiguity as a decision card: one card per interface, a button per candidate. */
    fun bindAmbiguity(
        subjectFqn: String,
        subjectDisplay: String,
        consumers: List<String>,
        candidates: List<Candidate>,
    ): PendingDecision = PendingDecision(
        id = "bind:$subjectFqn",
        kind = "bind",
        title = "$subjectDisplay has ${candidates.size} implementations",
        subject = subjectFqn,
        consumers = consumers,
        candidates = candidates.map { c ->
            DecisionCandidate(
                fqn = c.fqn,
                displayName = c.displayName,
                file = c.site.filePath,
                line = c.site.line,
                insert = bindInsert(subjectFqn, c.fqn),
            )
        },
    )

    fun export(decisions: List<PendingDecision>, rules: RuleSet, module: String): DecisionsFile {
        val suggestion = if (rules.holderFile == null && decisions.isNotEmpty()) {
            suggestHolder(module, decisions.first().subject)
        } else {
            null
        }
        return DecisionsFile(
            module = module,
            rulesFile = rules.holderFile,
            rulesObject = rules.holderObject,
            suggestedFile = suggestion?.first,
            suggestedPackage = suggestion?.second,
            pending = decisions,
        )
    }

    /** Best-effort location for a module's first GraphRules.kt: the subject's package. */
    fun suggestHolder(module: String, subjectFqn: String): Pair<String, String> {
        val pkg = subjectFqn.substringBeforeLast('.', "")
        val moduleDir = module.trimStart(':').replace(':', '/')
        return "$moduleDir/src/main/java/${pkg.replace('.', '/')}/GraphRules.kt" to pkg
    }
}
