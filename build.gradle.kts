// Plugin versions are resolved once here, with `apply false`, and reused by the
// subprojects that apply them. Without this, applying the Kotlin plugin (with a version)
// in more than one subproject loads its classpath multiple times — a warning Gradle says
// "may break the build".
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}
