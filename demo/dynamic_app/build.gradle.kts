plugins {
    id("com.android.application") apply false
    id("com.android.dynamic-feature") apply false
    // Not applied here: AGP 9 has Kotlin built in and rejects this plugin, while
    // AGP 8 requires it. The app module decides, see its build file.
    id("org.jetbrains.kotlin.android") apply false
    id("com.google.devtools.ksp") apply false
    id("com.kitedi") apply false
}
