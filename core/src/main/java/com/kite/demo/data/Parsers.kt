package com.kite.demo.data

/**
 * The strategy pattern without a framework concept: the key lives on the
 * interface itself. Every implementation below is inferred as a binding (rule
 * R1), and `Set<PayloadParser>` collects them all — what used to need
 * `@IntoMap("json")` is now `parsers.first { it.format == format }`.
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

/** Receives every implementation — a new parser class appears here (and on the board) automatically. */
class PayloadDecoder(private val parsers: Set<PayloadParser>) {
    fun decode(format: String, raw: String): String =
        (parsers.firstOrNull { it.format == format }
            ?: error("no parser for '$format' — known: ${formats()}")).parse(raw)

    fun formats(): Set<String> = parsers.mapTo(linkedSetOf()) { it.format }
}
