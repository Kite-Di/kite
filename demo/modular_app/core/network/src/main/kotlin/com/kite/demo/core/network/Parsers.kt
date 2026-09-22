package com.kite.demo.core.network

import com.kite.demo.core.analytics.Analytics

/**
 * The strategy pattern without a framework concept: the key lives on the
 * interface itself. Every implementation below is inferred as a binding (rule
 * R1), and `Set<PayloadParser>` collects them all — what used to need
 * `@IntoMap("json")` is now `parsers.first { it.format == format }`.
 *
 * (Set multibindings aggregate per module — the parsers and their consumer
 * [ParsingPayloadDecoder] live together here; downstream modules depend on the
 * [PayloadDecoder] interface.)
 */
interface PayloadParser {
    val format: String
    fun parse(raw: String): String
}

class JsonParser : PayloadParser {
    override val format = "json"
    override fun parse(raw: String): String = "json(${raw.length} chars)"
}

class XmlParser(private val analytics: Analytics) : PayloadParser {
    override val format = "xml"
    override fun parse(raw: String): String {
        analytics.track("xml_parsed")
        return "xml(${raw.length} chars)"
    }
}

/** The module's public contract for decoding — the parser set stays an implementation detail. */
interface PayloadDecoder {
    fun decode(format: String, raw: String): String
    fun formats(): Set<String>
}

/** Receives every implementation — a new parser class appears here (and on the board) automatically. */
class ParsingPayloadDecoder(private val parsers: Set<PayloadParser>) : PayloadDecoder {
    override fun decode(format: String, raw: String): String =
        (parsers.firstOrNull { it.format == format }
            ?: error("no parser for '$format' — known: ${formats()}")).parse(raw)

    override fun formats(): Set<String> = parsers.mapTo(linkedSetOf()) { it.format }
}
