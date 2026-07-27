package com.kite.di.processor.scan

import com.kite.di.graph.ScopeDef
import com.kite.di.processor.model.Issue
import com.kite.di.processor.model.Severity

/**
 * `graph.rules` — the decisions file. One optional file per
 * module, next to build.gradle.kts. Dev-side compile input only: its FQN strings
 * never reach generated code (runtime keys stay Class references).
 *
 * ```
 * root  com.app.ui.FirstPresenter
 * bind  com.app.data.UserRepository -> com.app.data.NetworkUserRepository
 * scope com.app.ui.SessionState    -> activity
 * scope com.app.di.SessionCart     -> Session:10
 * ```
 */
data class GraphRules(
    val roots: List<RootRule> = emptyList(),
    val binds: List<BindRule> = emptyList(),
    val scopes: List<ScopeRule> = emptyList(),
    val issues: List<Issue> = emptyList(),
) {
    companion object {
        val EMPTY = GraphRules()
    }
}

data class RootRule(val fqn: String, val line: Int)
data class BindRule(val interfaceFqn: String, val implFqn: String, val line: Int)
data class ScopeRule(val fqn: String, val scope: ScopeDef?, val line: Int)

object GraphRulesParser {

    private val BUILT_IN_TARGETS = mapOf(
        "singleton" to ScopeDef("Singleton", 0),
        "activity" to ScopeDef("ActivityScoped", 1),
        "fragment" to ScopeDef("FragmentScoped", 2),
    )

    fun parse(text: String, filePath: String): GraphRules {
        val roots = mutableListOf<RootRule>()
        val binds = mutableListOf<BindRule>()
        val scopes = mutableListOf<ScopeRule>()
        val issues = mutableListOf<Issue>()

        fun error(line: Int, message: String) {
            issues += Issue(Severity.ERROR, "$filePath:$line — $message")
        }

        text.lineSequence().forEachIndexed { index, raw ->
            val line = index + 1
            val content = raw.substringBefore('#').trim()
            if (content.isEmpty()) return@forEachIndexed
            val tokens = content.split(Regex("\\s+"))
            when (tokens.first()) {
                "root" -> when (tokens.size) {
                    2 -> roots += RootRule(tokens[1], line)
                    else -> error(line, "expected: root <class-fqn>")
                }
                "bind" -> when {
                    tokens.size == 4 && tokens[2] == "->" -> binds += BindRule(tokens[1], tokens[3], line)
                    else -> error(line, "expected: bind <interface-fqn> -> <implementation-fqn>")
                }
                "scope" -> when {
                    tokens.size == 4 && tokens[2] == "->" -> {
                        val target = parseScopeTarget(tokens[3])
                        if (target == null && tokens[3] != "none") {
                            error(
                                line,
                                "unknown scope '${tokens[3]}' — use singleton | activity | fragment | none | <Name>:<level>",
                            )
                        } else {
                            scopes += ScopeRule(tokens[1], target, line)
                        }
                    }
                    else -> error(line, "expected: scope <class-fqn> -> singleton | activity | fragment | none | <Name>:<level>")
                }
                else -> error(line, "unknown directive '${tokens.first()}' — known: root, bind, scope")
            }
        }
        return GraphRules(roots, binds, scopes, issues)
    }

    /** `singleton`/`activity`/`fragment` → built-ins; `Name:level` → custom; `none` → null (unscoped). */
    private fun parseScopeTarget(token: String): ScopeDef? {
        BUILT_IN_TARGETS[token]?.let { return it }
        if (token == "none") return null
        val name = token.substringBefore(':')
        val level = token.substringAfter(':', "").toIntOrNull()
        if (name.isEmpty() || ':' !in token || level == null) return null
        return ScopeDef(name, level)
    }
}
