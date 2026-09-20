package com.kite.di.board

/**
 * The board's write path: pure text edits that turn a clicked decision card into a
 * line in the module's GraphRules.kt. Kept free of I/O so the edit logic is
 * unit-testable; the server does the reading and writing.
 */

private const val RULES_PACKAGE = "com.kite.di.rules"

/** The full new file content plus the 1-based line the rule landed on. */
data class RuleInsertion(val source: String, val line: Int)

/** `@Bind(...)` → `Bind` — the annotation the import has to cover. */
private fun annotationName(insertLine: String): String? =
    Regex("""^@(\w+)""").find(insertLine.trim())?.groupValues?.get(1)

/**
 * Inserts [insertLine] directly above the `object [holderObject]` declaration
 * (after any annotations already stacked on it — annotation order is not
 * semantic) and ensures the annotation's import exists. Throws when the holder
 * object cannot be found; the server reports that as an apply error.
 */
fun insertRule(source: String, holderObject: String, insertLine: String): RuleInsertion {
    val lines = source.split("\n").toMutableList()
    val objectRe = Regex("""(^|\s)object\s+${Regex.escape(holderObject)}\b""")
    val objectIdx = lines.indexOfFirst { objectRe.containsMatchIn(it) && !it.trimStart().startsWith("//") }
    if (objectIdx < 0) throw IllegalArgumentException("object $holderObject not found in the rules file")

    val indent = Regex("""^\s*""").find(lines[objectIdx])!!.value
    lines.add(objectIdx, indent + insertLine.trim())
    var insertedAt = objectIdx

    val annotation = annotationName(insertLine)
    if (annotation != null) {
        val importLine = "import $RULES_PACKAGE.$annotation"
        if (lines.none { it.trim() == importLine }) {
            // After the last import, else after the package line.
            var lastImport = -1
            var packageIdx = -1
            for ((i, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) lastImport = i
                else if (trimmed.startsWith("package ")) packageIdx = i
            }
            when {
                lastImport >= 0 -> {
                    lines.add(lastImport + 1, importLine)
                    insertedAt += 1
                }
                packageIdx >= 0 -> {
                    lines.addAll(packageIdx + 1, listOf("", importLine))
                    insertedAt += 2
                }
                else -> {
                    lines.add(0, importLine)
                    insertedAt += 1
                }
            }
        }
    }
    return RuleInsertion(lines.joinToString("\n"), insertedAt + 1)
}

/** A fresh GraphRules.kt for a module that has no holder object yet. */
fun newRulesFile(pkg: String, insertLine: String): String {
    val annotation = annotationName(insertLine)
    val importBlock = if (annotation != null) "import $RULES_PACKAGE.$annotation\n\n" else ""
    val packageBlock = if (pkg.isNotEmpty()) "package $pkg\n\n" else ""
    return packageBlock +
        importBlock +
        "// Graph decisions — the few facts the code can't express.\n" +
        "// Started by the dependency board's decision cards; edit freely, it is ordinary Kotlin.\n" +
        "${insertLine.trim()}\n" +
        "private object GraphRules\n"
}
