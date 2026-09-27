package com.kite.di.processor.scan

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether an interface is the project's own is read off the build's classpath, not
 * off its name. The regression this pins down: `android/architecture-samples`
 * lives in `com.google.samples.modularization.*`, and under the old prefix list
 * every class in it was classified as third-party, so no interface binding was ever
 * created and each repository parameter silently became a graph argument.
 */
class OwnedPackagesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun classesDir(vararg classes: String): File = temp.newFolder().also { root ->
        for (fqn in classes) {
            val file = File(root, fqn.replace('.', File.separatorChar) + ".class")
            file.parentFile.mkdirs()
            file.writeText("")
        }
    }

    private fun jar(vararg classes: String): File = temp.newFile("lib-${classes.size}-${classes.hashCode()}.jar")
        .also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                for (fqn in classes) {
                    zip.putNextEntry(ZipEntry(fqn.replace('.', '/') + ".class"))
                    zip.closeEntry()
                }
            }
        }

    @Test
    fun `a package under com_google is the project's own when the build produces it`() {
        val owned = OwnedPackages.of(
            listOf(classesDir("com.google.samples.modularization.core.data.MyModelRepository")),
        )

        assertTrue(owned.owns("com.google.samples.modularization.core.data"))
    }

    @Test
    fun `a library package is not the project's own even when the build has one like it`() {
        val owned = OwnedPackages.of(
            listOf(classesDir("com.google.samples.modularization.core.data.MyModelRepository")),
        )

        assertFalse(owned.owns("com.google.android.material.button"))
        assertFalse(owned.owns("okhttp3"))
        assertFalse(owned.owns("kotlinx.coroutines"))
    }

    @Test
    fun `jars and class directories are both read`() {
        val owned = OwnedPackages.of(
            listOf(
                jar("com.example.api.UserRepository"),
                classesDir("com.example.impl.NetworkUserRepository"),
            ),
        )

        assertTrue(owned.owns("com.example.api"))
        assertTrue(owned.owns("com.example.impl"))
        assertFalse(owned.owns("com.example"))          // no class lives directly there
    }

    @Test
    fun `a sibling package of an owned one is not owned by association`() {
        val owned = OwnedPackages.of(listOf(classesDir("com.example.data.Repo")))

        assertFalse(owned.owns("com.example.data.internal"))
    }

    @Test
    fun `nothing on the classpath means nothing is owned - the module's own sources aside`() {
        // Callers answer for their own sources before asking (a declaration with a
        // containing file), so an empty classpath is the single-module case, not a
        // graph with no bindings.
        assertFalse(OwnedPackages.of(emptyList()).owns("com.example.data"))
    }

    @Test
    fun `an unreadable entry is skipped rather than failing the build`() {
        val broken = temp.newFile("not-a-jar.jar").also { it.writeText("certainly not a zip") }

        val owned = OwnedPackages.of(listOf(broken, classesDir("com.example.data.Repo")))

        assertTrue(owned.owns("com.example.data"))
    }

    @Test
    fun `without the option the old prefix list still decides`() {
        // KSP wired up by hand, without the Gradle plugin: classifying everything as
        // foreign would turn every interface binding into a graph argument, which is
        // worse than the heuristic it replaces.
        assertTrue(OwnedPackages.LEGACY.owns("com.example.data"))
        assertFalse(OwnedPackages.LEGACY.owns("kotlin.collections"))
        assertFalse(OwnedPackages.LEGACY.owns("com.google.samples.modularization.core.data"))
    }
}
