package com.kite.demo.data

import com.kite.di.annotations.Inject
import com.kite.di.annotations.Injectable
import com.kite.di.annotations.IntoMap
import com.kite.di.annotations.Module
import com.kite.di.annotations.Provides
import com.kite.di.annotations.Singleton

/** The strategy pattern via @IntoMap: pick an implementation by key at runtime. */
fun interface PayloadParser {
    fun parse(raw: String): String
}

@Module
object ParserModule {

    @Provides
    @IntoMap("json")
    fun json(): PayloadParser = PayloadParser { raw -> "json(${raw.length} chars)" }

    @Provides
    @IntoMap("xml")
    fun xml(analytics: Analytics): PayloadParser = PayloadParser { raw ->
        analytics.track("xml_parsed")
        "xml(${raw.length} chars)"
    }
}

/** Receives every @IntoMap entry — a new format appears here (and on the board) automatically. */
@Injectable
@Singleton
class PayloadDecoder @Inject constructor(
    private val parsers: Map<String, PayloadParser>,
) {
    fun decode(format: String, raw: String): String =
        (parsers[format] ?: error("no parser for '$format' — known: ${parsers.keys}")).parse(raw)

    fun formats(): Set<String> = parsers.keys
}
