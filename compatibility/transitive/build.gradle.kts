import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("com.google.devtools.ksp") apply false
    id("com.kitedi") apply false
}

// AGP 9 carries Kotlin itself and rejects the standalone plugin; AGP 8 needs it,
// applied before Kite brings in KSP. Done here so the three module files stay
// about the dependency shape being tested and nothing else.
if (providers.gradleProperty("agpVersion").get().startsWith("8")) {
    subprojects {
        pluginManager.withPlugin("com.android.base") {
            apply(plugin = "org.jetbrains.kotlin.android")
        }
        // AGP 8's Java tasks default to 1.8; the build refuses to run with Kotlin
        // on a different target.
        tasks.withType(KotlinCompile::class.java).configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
}
