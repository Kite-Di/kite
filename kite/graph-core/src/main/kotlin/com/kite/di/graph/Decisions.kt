package com.kite.di.graph

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `decisions.json` — the pending-decisions artifact behind board decision cards
 *. Written by the KSP processor next to graph.json on every
 * debug compilation: with the decisions blocking inference when the build
 * fails, with an empty [DecisionsFile.pending] on success (which clears the
 * cards). A development-only artifact, never packaged — same rules as
 * graph.json.
 */

/** One way to answer a decision — a click on this candidate writes [insert]. */
@Serializable
data class DecisionCandidate(
    val fqn: String,
    val displayName: String,
    /** Where the candidate is declared (repo-root-relative). */
    val file: String,
    val line: Int,
    /** The exact rules line this candidate resolves the decision with. */
    val insert: String,
)

@Serializable
data class PendingDecision(
    /** Stable id, e.g. `"bind:com.app.data.UserRepository"`. */
    val id: String,
    /** `"bind"` today; `"root"` / `"scope"` reserved for later cards. */
    val kind: String,
    /** Card headline, e.g. `"UserRepository has 2 implementations"`. */
    val title: String,
    /** The type the decision is about (FQN). */
    val subject: String,
    /** Human-readable consumer sites that force the decision. */
    val consumers: List<String> = emptyList(),
    val candidates: List<DecisionCandidate> = emptyList(),
)

@Serializable
data class DecisionsFile(
    val module: String,
    /** Repo-root-relative path of the module's rules holder file, when one exists. */
    val rulesFile: String? = null,
    /** Simple name of the holder object inside [rulesFile]. */
    val rulesObject: String? = null,
    /** Where the board server should create a holder when the module has none. */
    val suggestedFile: String? = null,
    val suggestedPackage: String? = null,
    val pending: List<PendingDecision> = emptyList(),
)

object DecisionsJson {
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val prettyJson: Json = Json(json) { prettyPrint = true }

    fun encodePretty(file: DecisionsFile): String =
        prettyJson.encodeToString(DecisionsFile.serializer(), file)

    fun decode(text: String): DecisionsFile =
        json.decodeFromString(DecisionsFile.serializer(), text)
}
