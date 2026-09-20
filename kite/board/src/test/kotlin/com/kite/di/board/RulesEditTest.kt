package com.kite.di.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The board's only write path. These cases are the ones that would corrupt a
 * developer's source file if the edit were sloppy — a rule landing inside the
 * object, a duplicated import, a holder that isn't there.
 */
class RulesEditTest {

    private val source = """
        package com.example.app

        import com.kite.di.rules.Root

        @Root(Foo::class)
        private object GraphRules
    """.trimIndent()

    @Test
    fun `rule lands above the holder, under the annotations already stacked on it`() {
        val result = insertRule(source, "GraphRules", "@Bind(Analytics::class, to = LogAnalytics::class)")
        val lines = result.source.split("\n")

        assertEquals("@Bind(Analytics::class, to = LogAnalytics::class)", lines[result.line - 1])
        assertTrue(lines[result.line].startsWith("@Root") || lines[result.line].contains("object GraphRules"))
        assertTrue(result.source.indexOf("@Bind") < result.source.indexOf("private object GraphRules"))
    }

    @Test
    fun `the annotation's import is added once`() {
        val once = insertRule(source, "GraphRules", "@Bind(A::class, to = B::class)").source
        val twice = insertRule(once, "GraphRules", "@Bind(C::class, to = D::class)").source

        assertEquals(1, Regex("import com\\.kite\\.di\\.rules\\.Bind").findAll(twice).count())
    }

    @Test
    fun `an import that is already there is left alone`() {
        val result = insertRule(source, "GraphRules", "@Root(Bar::class)").source
        assertEquals(1, Regex("import com\\.kite\\.di\\.rules\\.Root").findAll(result).count())
    }

    @Test
    fun `no imports yet - the import goes after the package line`() {
        val bare = "package com.example.app\n\nprivate object GraphRules\n"
        val result = insertRule(bare, "GraphRules", "@Bind(A::class, to = B::class)").source

        val lines = result.split("\n")
        assertEquals("package com.example.app", lines[0])
        assertTrue(lines.take(4).any { it == "import com.kite.di.rules.Bind" })
    }

    @Test
    fun `a missing holder is an error, not a silently mangled file`() {
        assertFailsWith<IllegalArgumentException> {
            insertRule(source, "SomeOtherObject", "@Root(Foo::class)")
        }
    }

    @Test
    fun `a commented-out holder does not count as the holder`() {
        val commented = "package p\n\n// private object GraphRules\n"
        assertFailsWith<IllegalArgumentException> { insertRule(commented, "GraphRules", "@Root(Foo::class)") }
    }

    @Test
    fun `a fresh file carries the package, the import and the rule`() {
        val created = newRulesFile("com.example.feature", "@Bind(A::class, to = B::class)")

        assertTrue(created.startsWith("package com.example.feature\n"))
        assertTrue(created.contains("import com.kite.di.rules.Bind"))
        assertTrue(created.contains("@Bind(A::class, to = B::class)"))
        assertTrue(created.trimEnd().endsWith("private object GraphRules"))
    }

    @Test
    fun `a fresh file in the default package has no package line`() {
        val created = newRulesFile("", "@Root(Foo::class)")
        assertTrue(!created.startsWith("package"))
        assertTrue(created.contains("@Root(Foo::class)"))
    }
}
