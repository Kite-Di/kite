package com.kite.di.graph

import kotlinx.serialization.json.Json

/**
 * The single JSON configuration for everything graph-shaped: `graph.json`, the
 * inspector's HTTP/WS payloads, and patch ops. The TypeScript types in
 * `webboard/src/model/graph.ts` mirror exactly this encoding (pinned by the golden
 * file `src/test/resources/golden/snapshot-v2.json`).
 */
object GraphJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "op"
    }

    private val prettyJson: Json = Json(json) { prettyPrint = true }

    fun encode(snapshot: GraphSnapshot): String = json.encodeToString(GraphSnapshot.serializer(), snapshot)

    /** Deterministic pretty output for the build artifact (CI-diffable). */
    fun encodePretty(snapshot: GraphSnapshot): String =
        prettyJson.encodeToString(GraphSnapshot.serializer(), snapshot)

    fun decode(text: String): GraphSnapshot = json.decodeFromString(GraphSnapshot.serializer(), text)
}
