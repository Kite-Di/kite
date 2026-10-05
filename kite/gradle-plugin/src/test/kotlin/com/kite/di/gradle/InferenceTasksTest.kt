package com.kite.di.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.google.devtools.ksp.gradle.KspAATask
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Only a variant's own KSP task infers; every other one — unit tests, androidTest,
 * test fixtures — gets `kite.skip`. Read in-process: the option map itself cannot be
 * queried before the classpath tasks it depends on have run.
 */
class InferenceTasksTest {

    @Test
    fun `a variant's own KSP task infers`() {
        for (task in AndroidFixture.mainTasks) assertTrue(plugin.infers(project, task), task)
    }

    @Test
    fun `every other KSP task skips inference, test fixtures included`() {
        for (task in AndroidFixture.otherTasks) assertFalse(plugin.infers(project, task), task)
    }

    @Test
    fun `the fixture has exactly the KSP tasks the test reasons about`() {
        assertEquals(
            (AndroidFixture.mainTasks + AndroidFixture.otherTasks).sorted(),
            project.tasks.withType(KspAATask::class.java).map { it.name }.sorted(),
        )
    }

    companion object {
        @get:ClassRule
        @JvmStatic
        val dir = TemporaryFolder()

        private lateinit var project: Project
        private lateinit var plugin: KitePlugin

        @BeforeClass
        @JvmStatic
        fun evaluate() {
            dir.root.resolve("local.properties").writeText("sdk.dir=${AndroidFixture.sdkDir}\n")
            project = ProjectBuilder.builder().withProjectDir(dir.root).build()
            project.pluginManager.apply("com.android.application")
            project.pluginManager.apply(KitePlugin::class.java)
            project.extensions.configure(ApplicationExtension::class.java) { android ->
                android.namespace = "com.example.fixture"
                android.compileSdk = 34
                android.defaultConfig.minSdk = 23
                android.testFixtures.enable = true
            }
            // Variants, and with them the KSP tasks, exist once the project is evaluated.
            (project as ProjectInternal).evaluate()
            plugin = project.plugins.getPlugin(KitePlugin::class.java)
        }
    }
}
