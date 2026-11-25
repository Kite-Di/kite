import com.android.build.gradle.BaseExtension
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
// applied before Kite brings in KSP. Done here so the module files stay
// about the dependency shape being tested and nothing else.
if (providers.gradleProperty("agpVersion").get().startsWith("8")) {
    subprojects {
        pluginManager.withPlugin("com.android.base") {
            apply(plugin = "org.jetbrains.kotlin.android")
            // BaseExtension, not CommonExtension: its type parameters differ
            // between AGP 8 and 9, and this file has to compile against both.
            extensions.getByType(BaseExtension::class.java).compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
        }
        // Kite is built for JVM 11, so code inlining its reified lookups has to
        // be too. AGP 8's Java tasks default to 1.8, and the build refuses to run
        // with Kotlin and Java on different targets: both go to 11.
        tasks.withType(KotlinCompile::class.java).configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
        }
    }
}
