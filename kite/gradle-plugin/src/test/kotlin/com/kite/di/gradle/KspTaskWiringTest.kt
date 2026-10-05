package com.kite.di.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which KSP task ends with the board, read back from a real build of an app with
 * test fixtures — and that the wiring holds under the configuration cache.
 */
class KspTaskWiringTest {

    @Test
    fun `only the debug variant's own KSP task ends with the board`() {
        assertEquals(listOf("kiteBoard"), finalizers.getValue("kspDebugKotlin"))
        for (task in AndroidFixture.mainTasks + AndroidFixture.otherTasks - "kspDebugKotlin") {
            assertEquals(emptyList(), finalizers.getValue(task), task)
        }
    }

    /**
     * Also pins that a board which will not start is not downloaded: the fixture runs
     * with `kite.startBoard=false` and has no repository serving `com.kitedi:board`,
     * so storing the cache fails if the task action captured its classpath.
     */
    @Test
    fun `the wiring survives the configuration cache without fetching the board`() {
        val args = listOf(":app:kspDebugKotlin", "--dry-run", "--configuration-cache")
        val stored = runner(args).build().output
        assertTrue(":app:kiteBoard SKIPPED" in stored, stored)
        val reused = runner(args).build().output
        assertTrue("Reusing configuration cache" in reused, reused)
        assertTrue(":app:kiteBoard SKIPPED" in reused, reused)
    }

    companion object {
        @get:ClassRule
        @JvmStatic
        val dir = TemporaryFolder()

        private lateinit var finalizers: Map<String, List<String>>

        @BeforeClass
        @JvmStatic
        fun buildFixture() {
            AndroidFixture.writeBuild(
                dir.root,
                appScript = """
                    // Reads the finalizers back once every variant is known.
                    val kspTasks = tasks.withType(com.google.devtools.ksp.gradle.KspAATask::class.java)
                    tasks.register("kiteProbe") {
                        doLast {
                            // A snapshot of the names: realizing a task while iterating the live set throws.
                            kspTasks.names.sorted().map(kspTasks::getByName).forEach { t ->
                                println("PROBE ${'$'}{t.name} " + t.finalizedBy.getDependencies(t).joinToString(",") { it.name })
                            }
                        }
                    }
                """.trimIndent(),
            )
            val output = runner(listOf(":app:kiteProbe")).build().output
            finalizers = output.lineSequence()
                .filter { it.startsWith("PROBE ") }
                .map { it.removePrefix("PROBE ").split(" ", limit = 2) }
                .associate { (name, tasks) -> name to tasks.split(",").filter(String::isNotBlank) }
            check(finalizers.keys.containsAll(AndroidFixture.mainTasks + AndroidFixture.otherTasks)) {
                "the fixture's KSP tasks are not the expected ones: ${finalizers.keys}"
            }
        }

        private fun runner(args: List<String>): GradleRunner = GradleRunner.create()
            .withProjectDir(dir.root)
            .withPluginClasspath()
            .withArguments(args + "--stacktrace")
    }
}
